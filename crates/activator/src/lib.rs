use std::collections::BTreeMap;
use std::error::Error;
use std::fs;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Deserialize;
use vpnhide_protocol::Target;
use vpnhide_protocol::format_config;
use vpnhide_protocol::hook_ids::{HOOK_NAMES, KERNEL_HOOK_MASK};

pub type Result<T> = std::result::Result<T, Box<dyn Error + Send + Sync>>;

pub const CANONICAL_CONFIG: &str = "/data/system/vpnhide_config.json";
pub const KMOD_CTL: &str = "/proc/vpnhide_ctl";
pub const ZYGISK_RUNTIME_CONFIG: &str = "/data/adb/modules/vpnhide_zygisk/targets.txt";
pub const KPM_MODULE_FILE: &str = "/data/adb/modules/vpnhide_kpm/vpnhide.kpm";
pub const SUPERKEY_FILE: &str = "/data/adb/vpnhide/superkey";
const KPM_NAME: &str = "vpnhide";

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CanonicalConfig {
    #[serde(default = "schema_version")]
    pub version: u32,
    #[serde(default)]
    pub debug: bool,
    #[serde(default)]
    pub apps: BTreeMap<String, AppConfig>,
    #[serde(default)]
    pub settings: Settings,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    #[serde(default)]
    pub remember_superkey: bool,
}

#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    #[serde(default)]
    pub java: bool,
    #[serde(default)]
    pub native: NativeSelection,
    #[serde(default)]
    pub app_hiding: bool,
    #[serde(default)]
    pub ports: bool,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(untagged)]
pub enum NativeSelection {
    Enabled(bool),
    Hooks(Vec<String>),
}

impl Default for NativeSelection {
    fn default() -> Self {
        Self::Enabled(false)
    }
}

impl NativeSelection {
    fn hookmask(&self) -> Option<u32> {
        match self {
            NativeSelection::Enabled(false) => None,
            NativeSelection::Enabled(true) => Some(KERNEL_HOOK_MASK),
            NativeSelection::Hooks(names) => {
                let mask = names.iter().fold(0u32, |acc, name| acc | hook_bit(name));
                (mask != 0).then_some(mask)
            }
        }
    }
}

fn schema_version() -> u32 {
    1
}

fn hook_bit(name: &str) -> u32 {
    HOOK_NAMES
        .iter()
        .position(|known| *known == name)
        .map(|id| 1u32 << id)
        .unwrap_or(0)
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PackageUidMap {
    packages: BTreeMap<String, Vec<u32>>,
}

impl PackageUidMap {
    pub fn from_pm() -> Result<Self> {
        let out = Command::new("pm")
            .args(["list", "packages", "-U", "--user", "all"])
            .output()?;
        if !out.status.success() {
            return Err(format!("pm list packages failed with status {}", out.status).into());
        }
        let stdout = String::from_utf8(out.stdout)?;
        Ok(parse_pm_packages(&stdout))
    }

    fn uids_for(&self, package: &str) -> &[u32] {
        self.packages.get(package).map(Vec::as_slice).unwrap_or(&[])
    }
}

pub fn parse_pm_packages(output: &str) -> PackageUidMap {
    let mut packages = BTreeMap::<String, Vec<u32>>::new();
    for line in output.lines() {
        let mut pkg: Option<&str> = None;
        let mut uid_csv: Option<&str> = None;
        for token in line.split_whitespace() {
            if let Some(rest) = token.strip_prefix("package:") {
                pkg = Some(rest);
            } else if let Some(rest) = token.strip_prefix("uid:") {
                uid_csv = Some(rest);
            }
        }
        let (Some(pkg), Some(uid_csv)) = (pkg, uid_csv) else {
            continue;
        };
        let uids = uid_csv
            .split(',')
            .filter_map(|s| s.parse::<u32>().ok())
            .collect::<Vec<_>>();
        if !uids.is_empty() {
            packages.insert(pkg.to_owned(), uids);
        }
    }
    PackageUidMap { packages }
}

pub fn parse_canonical(json: &str) -> Result<CanonicalConfig> {
    let cfg: CanonicalConfig = serde_json::from_str(json)?;
    if cfg.version > schema_version() {
        return Err(format!("unsupported vpnhide config version {}", cfg.version).into());
    }
    Ok(cfg)
}

pub fn project_native(json: &str) -> Result<String> {
    let cfg = parse_canonical(json)?;
    let resolver = PackageUidMap::from_pm()?;
    Ok(project_native_with_resolver(&cfg, &resolver))
}

pub fn project_native_with_resolver(cfg: &CanonicalConfig, resolver: &PackageUidMap) -> String {
    let mut by_uid = BTreeMap::<u32, u32>::new();
    for (pkg, app) in &cfg.apps {
        let Some(mask) = app.native.hookmask() else {
            continue;
        };
        for uid in resolver.uids_for(pkg) {
            by_uid
                .entry(*uid)
                .and_modify(|existing| *existing |= mask)
                .or_insert(mask);
        }
    }
    let targets = by_uid
        .into_iter()
        .map(|(uid, hookmask)| Target { uid, hookmask })
        .collect::<Vec<_>>();
    format_config(cfg.debug, &targets)
}

pub fn read_canonical() -> Result<String> {
    Ok(fs::read_to_string(CANONICAL_CONFIG)?)
}

pub fn activate_kmod() -> Result<()> {
    let wire = project_native(&read_canonical()?)?;
    fs::write(KMOD_CTL, wire)?;
    Ok(())
}

pub fn activate_zygisk() -> Result<()> {
    let wire = project_native(&read_canonical()?)?;
    write_atomic(Path::new(ZYGISK_RUNTIME_CONFIG), wire.as_bytes(), 0o644)
}

pub fn activate_kpm() -> Result<()> {
    let wire = project_native(&read_canonical()?)?;
    let kpatch = find_kpatch().ok_or("kpatch CLI not found")?;
    let key = read_superkey().ok();
    ensure_kpm_loaded(&kpatch, key.as_deref())?;
    run_kpm_ctl0(&kpatch, key.as_deref(), &wire)
}

pub fn write_atomic(path: &Path, content: &[u8], mode: u32) -> Result<()> {
    let parent = path.parent().ok_or("path has no parent")?;
    fs::create_dir_all(parent)?;
    let tmp = path.with_extension("tmp");
    {
        let mut file = fs::File::create(&tmp)?;
        file.write_all(content)?;
        file.sync_all()?;
    }
    fs::set_permissions(&tmp, fs::Permissions::from_mode(mode))?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn find_kpatch() -> Option<PathBuf> {
    [
        "kpatch",
        "/data/adb/ksu/bin/kpatch",
        "/data/adb/ap/bin/kpatch",
    ]
    .into_iter()
    .find_map(|candidate| {
        if candidate.contains('/') {
            let p = PathBuf::from(candidate);
            p.is_file().then_some(p)
        } else {
            std::env::var_os("PATH").and_then(|paths| {
                std::env::split_paths(&paths)
                    .map(|dir| dir.join(candidate))
                    .find(|path| path.is_file())
            })
        }
    })
}

fn read_superkey() -> Result<String> {
    let key = fs::read_to_string(SUPERKEY_FILE)?;
    let key = key.trim().to_owned();
    if key.is_empty() {
        Err("superkey file is empty".into())
    } else {
        Ok(key)
    }
}

fn ensure_kpm_loaded(kpatch: &Path, key: Option<&str>) -> Result<()> {
    if kpm_list_contains(kpatch, key)? {
        return Ok(());
    }
    if !Path::new(KPM_MODULE_FILE).is_file() {
        return Err(format!("{KPM_MODULE_FILE} not found").into());
    }
    let mut cmd = kpatch_command(kpatch, key);
    cmd.args(["kpm", "load", KPM_MODULE_FILE]);
    let out = cmd.output()?;
    if !out.status.success() {
        return Err(format!("kpm load failed with status {}", out.status).into());
    }
    if kpm_list_contains(kpatch, key)? {
        Ok(())
    } else {
        Err("kpm load returned success but vpnhide is not listed".into())
    }
}

fn kpm_list_contains(kpatch: &Path, key: Option<&str>) -> Result<bool> {
    let mut cmd = kpatch_command(kpatch, key);
    cmd.args(["kpm", "list"]);
    let out = cmd.output()?;
    if !out.status.success() {
        return Ok(false);
    }
    let stdout = String::from_utf8_lossy(&out.stdout);
    Ok(stdout.split_whitespace().any(|token| token == KPM_NAME))
}

fn run_kpm_ctl0(kpatch: &Path, key: Option<&str>, wire: &str) -> Result<()> {
    let mut cmd = kpatch_command(kpatch, key);
    cmd.args(["kpm", "ctl0", KPM_NAME, wire]);
    let out = cmd.output()?;
    if out.status.success() {
        Ok(())
    } else {
        Err(format!("kpm ctl0 failed with status {}", out.status).into())
    }
}

fn kpatch_command(kpatch: &Path, key: Option<&str>) -> Command {
    let mut cmd = Command::new(kpatch);
    if let Some(key) = key {
        cmd.arg(key);
    }
    cmd
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_pm_package_uids_for_all_profiles() {
        let map = parse_pm_packages(
            "package:com.example.one uid:10123,1010123\n\
             package:com.example.two uid:10234\n\
             package:bad.without.uid\n",
        );
        assert_eq!(map.uids_for("com.example.one"), &[10123, 1010123]);
        assert_eq!(map.uids_for("com.example.two"), &[10234]);
        assert!(map.uids_for("bad.without.uid").is_empty());
    }

    #[test]
    fn projects_native_roles_to_wire() {
        let cfg = parse_canonical(
            r#"{
              "version": 1,
              "debug": true,
              "apps": {
                "com.example.disabled": { "native": false },
                "com.example.full": { "native": true },
                "com.example.partial": { "native": ["sock_ioctl"] }
              }
            }"#,
        )
        .unwrap();
        let resolver = parse_pm_packages(
            "package:com.example.full uid:10123,1010123\n\
             package:com.example.partial uid:10234\n\
             package:com.example.disabled uid:10345\n",
        );
        assert_eq!(
            project_native_with_resolver(&cfg, &resolver),
            "vpnhide 1 config\n\
             debug 1\n\
             target 0x278b 0x3ff\n\
             target 0x27fa 0x40\n\
             target 0xf69cb 0x3ff\n",
        );
    }
}

use std::collections::BTreeMap;
use std::error::Error;
use std::fs;
use std::io::ErrorKind;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::Duration;

use serde::Deserialize;
use vpnhide_protocol::Target;
use vpnhide_protocol::format_config;
use vpnhide_protocol::hook_ids::{HOOK_NAMES, KERNEL_HOOK_MASK};

pub type Result<T> = std::result::Result<T, Box<dyn Error + Send + Sync>>;

pub const CANONICAL_CONFIG: &str = "/data/system/vpnhide_config.json";
pub const KMOD_CTL: &str = "/proc/vpnhide_ctl";
pub const KMOD_MODULE_DIR: &str = "/data/adb/modules/vpnhide_kmod";
pub const ZYGISK_RUNTIME_CONFIG: &str = "/data/adb/modules/vpnhide_zygisk/targets.txt";
pub const KPM_MODULE_FILE: &str = "/data/adb/modules/vpnhide_kpm/vpnhide.kpm";
pub const SUPERKEY_FILE: &str = "/data/adb/vpnhide/superkey";
const APP_PACKAGE: &str = "dev.okhsunrog.vpnhide";
const KPM_NAME: &str = "vpnhide";
const MAX_NATIVE_TARGETS: usize = 64;
const PM_READY_ATTEMPTS: u32 = 60;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PmReadyWait {
    Bounded(u32),
    Forever,
}

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
        Self::from_pm_with_wait(PmReadyWait::Bounded(PM_READY_ATTEMPTS))
    }

    fn from_pm_with_wait(wait: PmReadyWait) -> Result<Self> {
        wait_for_pm_ready(wait)?;
        let stdout = pm_list_packages(&["list", "packages", "-U", "--user", "all"])?;
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
    project_native_with_pm_wait(json, PmReadyWait::Bounded(PM_READY_ATTEMPTS))
}

fn project_native_with_pm_wait(json: &str, wait: PmReadyWait) -> Result<String> {
    let cfg = parse_canonical(json)?;
    if !has_native_targets(&cfg) {
        return Ok(format_config(cfg.debug, &[]));
    }
    let resolver = PackageUidMap::from_pm_with_wait(wait)?;
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
        .take(MAX_NATIVE_TARGETS)
        .map(|(uid, hookmask)| Target { uid, hookmask })
        .collect::<Vec<_>>();
    format_config(cfg.debug, &targets)
}

pub fn read_canonical() -> Result<String> {
    match fs::read_to_string(CANONICAL_CONFIG) {
        Ok(raw) => Ok(raw),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(empty_canonical_json().to_owned()),
        Err(e) => Err(e.into()),
    }
}

pub fn activate_kmod() -> Result<()> {
    activate_kmod_with_pm_wait(PmReadyWait::Bounded(PM_READY_ATTEMPTS))
}

pub fn activate_kmod_boot() -> Result<()> {
    wait_for_path(KMOD_CTL);
    activate_kmod_with_pm_wait(PmReadyWait::Forever)
}

fn activate_kmod_with_pm_wait(wait: PmReadyWait) -> Result<()> {
    let wire = project_native_with_pm_wait(&read_canonical()?, wait)?;
    // /proc/vpnhide_ctl replaces the entire config per write(), so keep this
    // bounded to MAX_NATIVE_TARGETS and deliver one complete snapshot.
    fs::write(KMOD_CTL, wire)?;
    Ok(())
}

pub fn activate_zygisk() -> Result<()> {
    activate_zygisk_with_pm_wait(PmReadyWait::Bounded(PM_READY_ATTEMPTS))
}

pub fn activate_zygisk_boot() -> Result<()> {
    activate_zygisk_with_pm_wait(PmReadyWait::Forever)
}

fn activate_zygisk_with_pm_wait(wait: PmReadyWait) -> Result<()> {
    let wire = project_native_with_pm_wait(&read_canonical()?, wait)?;
    write_atomic(Path::new(ZYGISK_RUNTIME_CONFIG), wire.as_bytes(), 0o644)
}

pub fn activate_kpm() -> Result<()> {
    activate_kpm_with_pm_wait(PmReadyWait::Bounded(PM_READY_ATTEMPTS), true)
}

pub fn activate_kpm_boot() -> Result<()> {
    activate_kpm_with_pm_wait(PmReadyWait::Forever, false)
}

fn activate_kpm_with_pm_wait(wait: PmReadyWait, conflict_is_error: bool) -> Result<()> {
    if skip_kpm_for_kmod_conflict(conflict_is_error)? {
        return Ok(());
    }
    let wire = project_native_with_pm_wait(&read_canonical()?, wait)?;
    if skip_kpm_for_kmod_conflict(conflict_is_error)? {
        return Ok(());
    }
    let kpatch = find_kpatch().ok_or("kpatch CLI not found")?;
    let key = read_superkey().ok();
    ensure_kpm_loaded(&kpatch, key.as_deref())?;
    run_kpm_ctl0(&kpatch, key.as_deref(), &wire)
}

pub fn boot_wait_requested_from_env() -> Result<bool> {
    let mut boot_wait = false;
    for arg in std::env::args().skip(1) {
        match arg.as_str() {
            "--boot-wait" => boot_wait = true,
            _ => {
                return Err(
                    format!("unknown argument {arg}; usage: activator [--boot-wait]").into(),
                );
            }
        }
    }
    Ok(boot_wait)
}

fn has_native_targets(cfg: &CanonicalConfig) -> bool {
    cfg.apps.values().any(|app| app.native.hookmask().is_some())
}

fn empty_canonical_json() -> &'static str {
    "{\"version\":1,\"debug\":false,\"apps\":{},\"settings\":{\"rememberSuperkey\":false}}\n"
}

fn wait_for_pm_ready(wait: PmReadyWait) -> Result<()> {
    let mut attempts = 0;
    loop {
        attempts += 1;
        if let Ok(stdout) = pm_list_packages(&["list", "packages", "-U"])
            && pm_output_has_package(&stdout, APP_PACKAGE)
        {
            return Ok(());
        }
        if matches!(wait, PmReadyWait::Bounded(max) if attempts >= max) {
            return Err(
                format!("PackageManager did not expose {APP_PACKAGE} within {attempts}s").into(),
            );
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn wait_for_path(path: &str) {
    while !Path::new(path).exists() {
        thread::sleep(Duration::from_secs(1));
    }
}

fn pm_list_packages(args: &[&str]) -> Result<String> {
    let out = Command::new("pm").args(args).output()?;
    if !out.status.success() {
        return Err(format!("pm list packages failed with status {}", out.status).into());
    }
    Ok(String::from_utf8(out.stdout)?)
}

fn pm_output_has_package(output: &str, package: &str) -> bool {
    let expected = format!("package:{package}");
    output
        .lines()
        .any(|line| line.split_whitespace().next() == Some(expected.as_str()))
}

fn kmod_backend_present() -> bool {
    Path::new(KMOD_CTL).exists()
        || (Path::new(KMOD_MODULE_DIR).is_dir()
            && !Path::new(KMOD_MODULE_DIR).join("disable").exists())
}

fn skip_kpm_for_kmod_conflict(conflict_is_error: bool) -> Result<bool> {
    if !kmod_backend_present() {
        return Ok(false);
    }
    if conflict_is_error {
        return Err("kmod backend present; refusing to load/configure KPM".into());
    }
    Ok(true)
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

    #[test]
    fn parses_shared_storage_fixture() {
        let cfg =
            parse_canonical(include_str!("../../../testdata/storage_config_v1.json")).unwrap();

        assert!(cfg.debug);
        assert!(cfg.settings.remember_superkey);
        assert_eq!(
            cfg.apps.get("com.example.bank").unwrap().native,
            NativeSelection::Enabled(true),
        );
        assert_eq!(
            cfg.apps.get("org.example.proxy").unwrap().native,
            NativeSelection::Hooks(vec![
                "fib_route_seq_show".to_owned(),
                "sock_ioctl".to_owned()
            ]),
        );
    }

    #[test]
    fn absent_canonical_projects_to_empty_config_without_pm() {
        assert_eq!(
            project_native(empty_canonical_json()).unwrap(),
            "vpnhide 1 config\ndebug 0\n",
        );
    }

    #[test]
    fn pm_ready_check_matches_literal_package_token() {
        assert!(pm_output_has_package(
            "package:dev.okhsunrog.vpnhide uid:10123\n",
            APP_PACKAGE,
        ));
        assert!(!pm_output_has_package(
            "package:dev.okhsunrog.vpnhide.extra uid:10123\n",
            APP_PACKAGE,
        ));
    }

    #[test]
    fn projection_is_bounded_to_backend_target_capacity() {
        let apps = (0..70)
            .map(|i| {
                (
                    format!("com.example.{i:02}"),
                    AppConfig {
                        native: NativeSelection::Enabled(true),
                        ..AppConfig::default()
                    },
                )
            })
            .collect::<BTreeMap<_, _>>();
        let cfg = CanonicalConfig {
            version: 1,
            debug: false,
            apps,
            settings: Settings::default(),
        };
        let pm = (0..70)
            .map(|i| format!("package:com.example.{i:02} uid:{}", 10_000 + i))
            .collect::<Vec<_>>()
            .join("\n");
        let wire = project_native_with_resolver(&cfg, &parse_pm_packages(&pm));

        assert_eq!(
            wire.lines()
                .filter(|line| line.starts_with("target "))
                .count(),
            64
        );
    }
}

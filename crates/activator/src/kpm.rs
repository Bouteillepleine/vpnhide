use super::*;

fn find_kpatch() -> Option<PathBuf> {
    [
        "kpatch",
        "/data/adb/modules/KPatch-Next/bin/kpatch",
        "/data/adb/modules/kpatch-next/bin/kpatch",
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

fn apatch_auth_candidates() -> Vec<String> {
    let mut keys = Vec::new();
    if let Ok(key) = read_superkey() {
        keys.push(key);
    }
    if !keys.iter().any(|key| key == APATCH_TRUSTED_SU_KEY) {
        keys.push(APATCH_TRUSTED_SU_KEY.to_owned());
    }
    keys
}

pub(crate) enum KpmClient {
    KpatchCli {
        path: PathBuf,
    },
    ApatchSupercall {
        key: String,
        style: ApatchCommandStyle,
    },
}

pub(crate) enum KpmClientDetection {
    Ready(KpmClient),
    AwaitingAuthentication(String),
}

/// Cross-process serialization for this project's KPM ctl0 callers. The
/// KernelPatch runtime stores ctl args in one module-owned buffer before
/// dispatching the handler, so boot activation, app reconciliation, and stats
/// reads must not enter ctl0 concurrently even though the handler also guards
/// its own live config snapshot.
struct KpmCtlLock {
    _file: fs::File,
}

impl KpmCtlLock {
    fn acquire() -> Result<Self> {
        if let Some(parent) = Path::new(KPM_CTL_LOCK).parent() {
            fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(KPM_CTL_LOCK)?;
        fs::set_permissions(KPM_CTL_LOCK, fs::Permissions::from_mode(0o600))?;
        if unsafe { flock(file.as_raw_fd(), LOCK_EX) } != 0 {
            return Err(std::io::Error::last_os_error().into());
        }
        Ok(Self { _file: file })
    }
}

impl KpmClient {
    pub(crate) fn detect() -> Result<Self> {
        match Self::detect_outcome()? {
            KpmClientDetection::Ready(client) => Ok(client),
            KpmClientDetection::AwaitingAuthentication(detail) => Err(detail.into()),
        }
    }

    pub(crate) fn detect_outcome() -> Result<KpmClientDetection> {
        if Path::new(APATCH_DIR).is_dir() {
            let mut failures = Vec::new();
            for key in apatch_auth_candidates() {
                match apatch_probe(&key) {
                    Ok(style) => {
                        return Ok(KpmClientDetection::Ready(Self::ApatchSupercall {
                            key,
                            style,
                        }));
                    }
                    Err(e) => {
                        let label = if key == APATCH_TRUSTED_SU_KEY {
                            "trusted su"
                        } else {
                            "saved key"
                        };
                        failures.push(format!("{label}: {e}"));
                    }
                }
            }
            return Ok(KpmClientDetection::AwaitingAuthentication(format!(
                "APatch/FolkPatch KPM requires a valid saved superkey at {SUPERKEY_FILE} \
                 or a trusted '{APATCH_TRUSTED_SU_KEY}' supercall grant (attempts: {})",
                failures.join("; ")
            )));
        }
        let path = find_kpatch().ok_or("kpatch CLI not found")?;
        kpatch_hello(&path)?;
        Ok(KpmClientDetection::Ready(Self::KpatchCli { path }))
    }

    pub(crate) fn ensure_loaded(&self) -> Result<()> {
        if self.list_contains()? {
            return Ok(());
        }
        if !Path::new(KPM_MODULE_FILE).is_file() {
            return Err(format!("{KPM_MODULE_FILE} not found").into());
        }
        self.load()?;
        if self.list_contains()? {
            Ok(())
        } else {
            Err("kpm load returned success but vpnhide is not listed".into())
        }
    }

    fn list_contains(&self) -> Result<bool> {
        match self {
            Self::KpatchCli { path } => kpatch_kpm_list_contains(path),
            Self::ApatchSupercall { key, style } => {
                let list = apatch_kpm_list(key, *style)?;
                Ok(list.split_whitespace().any(|token| token == KPM_NAME))
            }
        }
    }

    fn load(&self) -> Result<()> {
        match self {
            Self::KpatchCli { path } => {
                let mut cmd = Command::new(path);
                cmd.args(["kpm", "load", KPM_MODULE_FILE]);
                let out = cmd.output()?;
                if out.status.success() {
                    Ok(())
                } else {
                    Err(format!("kpm load failed with status {}", out.status).into())
                }
            }
            Self::ApatchSupercall { key, style } => {
                let path = CString::new(KPM_MODULE_FILE)?;
                let key = CString::new(key.as_str())?;
                let rc = unsafe {
                    syscall(
                        APATCH_SUPERCALL_NR,
                        key.as_ptr(),
                        supercall_cmd(*style, SUPERCALL_KPM_LOAD),
                        path.as_ptr(),
                        ptr::null::<c_char>(),
                        ptr::null_mut::<c_void>(),
                    )
                };
                supercall_ok(rc, "kpm load")
            }
        }
    }

    pub(crate) fn ctl0_config(&self, wire: &str) -> Result<()> {
        let _lock = KpmCtlLock::acquire()?;
        const ATTEMPTS: usize = 4;
        for attempt in 0..ATTEMPTS {
            let result = match self {
                Self::KpatchCli { path } => run_kpatch_kpm_ctl0_config(path, wire),
                Self::ApatchSupercall { key, style } => apatch_kpm_ctl0_config(key, *style, wire),
            };
            match result {
                Ok(()) => return Ok(()),
                Err(_) if attempt + 1 < ATTEMPTS => {
                    // A concurrent boot/app ctl0 config gets the KPM's short
                    // busy return instead of spinning inside the kernel. The
                    // critical section is only a 64-entry copy, so a brief
                    // retry also covers runtimes that flatten negative return
                    // codes to a generic CLI failure.
                    thread::sleep(Duration::from_millis(20));
                }
                Err(error) => return Err(error),
            }
        }
        unreachable!()
    }

    pub(crate) fn ctl0_read(&self, wire: &str) -> Result<String> {
        let _lock = KpmCtlLock::acquire()?;
        match self {
            Self::KpatchCli { path } => run_kpatch_kpm_ctl0_read(path, wire),
            Self::ApatchSupercall { key, style } => apatch_kpm_ctl0_read(key, *style, wire),
        }
    }
}

fn kpatch_kpm_list_contains(kpatch: &Path) -> Result<bool> {
    let mut cmd = Command::new(kpatch);
    cmd.args(["kpm", "list"]);
    let out = cmd.output()?;
    if !out.status.success() {
        return Ok(false);
    }
    let stdout = String::from_utf8_lossy(&out.stdout);
    Ok(stdout.split_whitespace().any(|token| token == KPM_NAME))
}

fn kpatch_hello(kpatch: &Path) -> Result<()> {
    let mut cmd = Command::new(kpatch);
    cmd.arg("hello");
    let out = cmd.output()?;
    if out.status.success() && !String::from_utf8_lossy(&out.stdout).trim().is_empty() {
        Ok(())
    } else {
        Err(format!(
            "KernelPatch inactive or kpatch hello failed with status {}",
            out.status
        )
        .into())
    }
}

fn run_kpatch_kpm_ctl0_config(kpatch: &Path, wire: &str) -> Result<()> {
    let mut cmd = Command::new(kpatch);
    cmd.args(["kpm", "ctl0", KPM_NAME, wire]);
    let out = cmd.output()?;
    if kpatch_ctl0_config_status_ok(out.status, wire) {
        Ok(())
    } else {
        Err(format!("kpm ctl0 failed with status {}", out.status).into())
    }
}

fn run_kpatch_kpm_ctl0_read(kpatch: &Path, wire: &str) -> Result<String> {
    let mut cmd = Command::new(kpatch);
    cmd.args(["kpm", "ctl0", KPM_NAME, wire]);
    let out = cmd.output()?;
    // The kpatch CLI prints the reply to stdout (`fprintf(stdout, "%s", buf)`)
    // and exits with the supercall's return value — for a READ that is the reply
    // BYTE COUNT (e.g. 64), NOT 0. So a non-zero exit is the normal success case
    // here; treating it as failure (the old behaviour) dropped every status/stats
    // read on KPatch-Next, leaving the dashboard with no KPM stats. On a real
    // error the supercall returns a negative rc and never fills the buffer, so
    // stdout is empty. Trust stdout: the reply text is authoritative, the exit
    // code is not (it can't even round-trip a reply longer than 255 bytes).
    normalize_kpm_reply(wire, String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Validate a KPM readback and preserve the protocol's missing-newline
/// truncation signal as an explicit comment. KPatch's CLI and the APatch client
/// both cap ctl0 replies at 4096 bytes, so retrying with a larger userspace
/// buffer is not portable. Keeping the complete-line prefix plus a marker lets
/// the app retain backend status while refusing to total partial counters.
pub(crate) fn normalize_kpm_reply(wire: &str, mut reply: String) -> Result<String> {
    if reply.is_empty() {
        return Err("KPM ctl0 returned an empty reply".into());
    }
    let expected = peek_kind(wire.as_bytes()).ok_or("invalid KPM ctl0 request header")?;
    let actual = peek_kind(reply.as_bytes()).ok_or("invalid KPM ctl0 reply header")?;
    if actual != expected || !matches!(actual, Kind::Status | Kind::Stats) {
        return Err(format!("unexpected KPM ctl0 reply kind {actual:?} for {expected:?}").into());
    }
    if !reply.ends_with('\n') {
        reply.push('\n');
        reply.push_str(KPM_TRUNCATION_MARKER);
        reply.push('\n');
    }
    Ok(reply)
}

pub(crate) fn kpatch_ctl0_config_status_ok(status: std::process::ExitStatus, wire: &str) -> bool {
    if status.success() {
        return true;
    }
    let Some(expected_targets) = parse_config(wire.as_bytes()).map(|cfg| cfg.targets.len()) else {
        return false;
    };
    // Compatibility with older vpnhide KPM builds: `ctl0 config` returned the
    // applied target count, which KPatch-Next exposes as the shell exit status.
    status.code() == Some(expected_targets as i32)
}

fn apatch_probe(key: &str) -> Result<ApatchCommandStyle> {
    let candidates = apatch_command_candidates();
    let mut failures = Vec::new();
    for style in candidates {
        let rc = apatch_hello(key, style)?;
        if rc == SUPERCALL_HELLO_MAGIC {
            return Ok(style);
        }
        failures.push(format!("{style:?}: rc={rc}"));
    }
    Err(format!(
        "KernelPatch inactive or bad APatch SuperKey (hello attempts: {})",
        failures.join(", ")
    )
    .into())
}

fn apatch_hello(key: &str, style: ApatchCommandStyle) -> Result<c_long> {
    let key = CString::new(key)?;
    let rc = unsafe {
        syscall(
            APATCH_SUPERCALL_NR,
            key.as_ptr(),
            supercall_cmd(style, SUPERCALL_HELLO),
        )
    };
    Ok(rc)
}

fn apatch_kpm_list(key: &str, style: ApatchCommandStyle) -> Result<String> {
    let key = CString::new(key)?;
    let mut buf = [0u8; 4096];
    let rc = unsafe {
        syscall(
            APATCH_SUPERCALL_NR,
            key.as_ptr(),
            supercall_cmd(style, SUPERCALL_KPM_LIST),
            buf.as_mut_ptr().cast::<c_char>(),
            buf.len() as c_long,
        )
    };
    supercall_ok(rc, "kpm list")?;
    let len = buf.iter().position(|b| *b == 0).unwrap_or(buf.len());
    Ok(String::from_utf8_lossy(&buf[..len]).into_owned())
}

fn apatch_kpm_ctl0_config(key: &str, style: ApatchCommandStyle, wire: &str) -> Result<()> {
    let (rc, _) = apatch_kpm_ctl0_raw(key, style, wire)?;
    supercall_ok(rc, "kpm ctl0")
}

fn apatch_kpm_ctl0_read(key: &str, style: ApatchCommandStyle, wire: &str) -> Result<String> {
    let (rc, out) = apatch_kpm_ctl0_raw(key, style, wire)?;
    supercall_ok(rc, "kpm ctl0")?;
    let len = apatch_output_len(rc, &out);
    normalize_kpm_reply(wire, String::from_utf8_lossy(&out[..len]).into_owned())
}

fn apatch_kpm_ctl0_raw(
    key: &str,
    style: ApatchCommandStyle,
    wire: &str,
) -> Result<(c_long, [u8; 4096])> {
    let key = CString::new(key)?;
    let name = CString::new(KPM_NAME)?;
    let wire = CString::new(wire)?;
    let mut out = [0u8; 4096];
    let rc = unsafe {
        syscall(
            APATCH_SUPERCALL_NR,
            key.as_ptr(),
            supercall_cmd(style, SUPERCALL_KPM_CONTROL),
            name.as_ptr(),
            wire.as_ptr(),
            out.as_mut_ptr().cast::<c_char>(),
            out.len() as c_long,
        )
    };
    Ok((rc, out))
}

fn apatch_output_len(rc: c_long, out: &[u8]) -> usize {
    if rc > 0 {
        return usize::try_from(rc).unwrap_or(out.len()).min(out.len());
    }
    out.iter().position(|b| *b == 0).unwrap_or(0)
}

pub(crate) fn apatch_command_candidates() -> Vec<ApatchCommandStyle> {
    apatch_command_candidates_for_hint(apatch_kernel_version_hint())
}

fn apatch_kernel_version_hint() -> Option<c_long> {
    let out = Command::new("dmesg").output().ok()?;
    if !out.status.success() {
        return None;
    }
    parse_apatch_kernel_version_hint(&String::from_utf8_lossy(&out.stdout))
}

fn supercall_ok(rc: c_long, op: &str) -> Result<()> {
    if rc >= 0 {
        Ok(())
    } else {
        Err(format!("{op} supercall failed with rc={rc}").into())
    }
}

//! Best-effort libc view for the optional filesystem interface-path feature.
//!
//! Kernel backends classify resolved dentries. Zygisk cannot provide that
//! contract: raw syscalls and paths reached through unrelated bind mounts or
//! symlink aliases bypass these hooks. It still closes the ordinary bionic
//! paths used by Java, Rust, Dart, and native libraries in a target process.

use core::ffi::{c_char, c_int, c_void};
use core::sync::atomic::{AtomicPtr, Ordering};
use core::{mem, ptr, slice};

use crate::filter::is_vpn_iface_bytes;
use crate::hooks::{filesystem_enabled, get_errno, set_errno};

const MAX_CALLER_PATH: usize = 1024;
const MAX_RESOLVED_PATH: usize = 2048;
// Split remote reads at 4 KiB boundaries. This is conservative on Android
// devices with larger pages and prevents a valid path from sharing one read
// with the next potentially-unmapped 4 KiB region.
const COPY_CHUNK_BOUNDARY: usize = 4096;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PathKind {
    Other,
    InterfaceListing,
    HiddenInterface,
}

pub(crate) struct CallerPath {
    bytes: [u8; MAX_CALLER_PATH],
    len: usize,
}

impl CallerPath {
    pub(crate) fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.len]
    }
}

struct ResolvedPath {
    bytes: [u8; MAX_RESOLVED_PATH],
    len: usize,
}

impl ResolvedPath {
    fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.len]
    }
}

/// Read an untrusted NUL-terminated path without dereferencing it in-process.
/// A bad caller pointer must retain libc's normal EFAULT instead of crashing
/// the target app inside the hook.
pub(crate) fn read_caller_path(path: *const c_char) -> Option<CallerPath> {
    if path.is_null() {
        return None;
    }

    let mut result = CallerPath {
        bytes: [0; MAX_CALLER_PATH],
        len: 0,
    };
    while result.len < result.bytes.len() {
        let remote_addr = path as usize + result.len;
        let page_left = COPY_CHUNK_BOUNDARY - (remote_addr & (COPY_CHUNK_BOUNDARY - 1));
        let wanted = page_left.min(result.bytes.len() - result.len);
        let local = libc::iovec {
            iov_base: result.bytes[result.len..].as_mut_ptr().cast(),
            iov_len: wanted,
        };
        let remote = libc::iovec {
            iov_base: remote_addr as *mut c_void,
            iov_len: wanted,
        };
        let copied = unsafe {
            libc::syscall(
                libc::SYS_process_vm_readv,
                libc::getpid(),
                ptr::from_ref(&local),
                1usize,
                ptr::from_ref(&remote),
                1usize,
                0usize,
            )
        };
        if copied <= 0 {
            return None;
        }
        let copied = copied as usize;
        let start = result.len;
        result.len += copied;
        if let Some(nul) = result.bytes[start..result.len]
            .iter()
            .position(|byte| *byte == 0)
        {
            result.len = start + nul;
            return Some(result);
        }
        if copied < wanted {
            return None;
        }
    }
    None
}

fn decimal(buf: &mut [u8], mut value: u32) -> usize {
    if value == 0 {
        buf[0] = b'0';
        return 1;
    }
    let mut reversed = [0u8; 10];
    let mut len = 0;
    while value != 0 {
        reversed[len] = b'0' + (value % 10) as u8;
        value /= 10;
        len += 1;
    }
    for index in 0..len {
        buf[index] = reversed[len - index - 1];
    }
    len
}

fn readlink_raw(path: &[u8], output: &mut [u8]) -> Option<usize> {
    if path.len() + 1 > 64 {
        return None;
    }
    let mut nul_path = [0u8; 64];
    nul_path[..path.len()].copy_from_slice(path);
    let len = unsafe {
        libc::syscall(
            libc::SYS_readlinkat,
            libc::AT_FDCWD,
            nul_path.as_ptr().cast::<c_char>(),
            output.as_mut_ptr(),
            output.len(),
        )
    };
    (len >= 0).then_some(len as usize)
}

fn resolve_fd_path(fd: c_int, output: &mut [u8]) -> Option<usize> {
    if fd == libc::AT_FDCWD {
        return readlink_raw(b"/proc/self/cwd", output);
    }
    if fd < 0 {
        return None;
    }
    let prefix = b"/proc/self/fd/";
    let mut path = [0u8; 32];
    path[..prefix.len()].copy_from_slice(prefix);
    let digits = decimal(&mut path[prefix.len()..], fd as u32);
    readlink_raw(&path[..prefix.len() + digits], output)
}

fn normalize_absolute(input: &[u8]) -> Option<ResolvedPath> {
    if input.first() != Some(&b'/') {
        return None;
    }
    let mut result = ResolvedPath {
        bytes: [0; MAX_RESOLVED_PATH],
        len: 1,
    };
    result.bytes[0] = b'/';
    let mut component_starts = [0usize; 96];
    let mut depth = 0usize;
    let mut cursor = 1usize;

    while cursor < input.len() {
        while input.get(cursor) == Some(&b'/') {
            cursor += 1;
        }
        if cursor == input.len() {
            break;
        }
        let start = cursor;
        while cursor < input.len() && input[cursor] != b'/' {
            cursor += 1;
        }
        let component = &input[start..cursor];
        if component == b"." {
            continue;
        }
        if component == b".." {
            if depth != 0 {
                depth -= 1;
                result.len = component_starts[depth];
            }
            continue;
        }
        if depth == component_starts.len() {
            return None;
        }
        component_starts[depth] = result.len;
        depth += 1;
        if result.len != 1 {
            if result.len == result.bytes.len() {
                return None;
            }
            result.bytes[result.len] = b'/';
            result.len += 1;
        }
        if component.len() > result.bytes.len() - result.len {
            return None;
        }
        result.bytes[result.len..result.len + component.len()].copy_from_slice(component);
        result.len += component.len();
    }
    Some(result)
}

fn resolve_path_at(dirfd: c_int, path: &[u8], allow_empty: bool) -> Option<ResolvedPath> {
    if path.first() == Some(&b'/') {
        return normalize_absolute(path);
    }
    if path.is_empty() && !allow_empty {
        return None;
    }

    let mut combined = [0u8; MAX_RESOLVED_PATH];
    let base_len = resolve_fd_path(dirfd, &mut combined)?;
    if base_len == 0 || base_len >= combined.len() || combined[0] != b'/' {
        return None;
    }
    let mut len = base_len;
    if !path.is_empty() {
        if len + 1 + path.len() > combined.len() {
            return None;
        }
        combined[len] = b'/';
        len += 1;
        combined[len..len + path.len()].copy_from_slice(path);
        len += path.len();
    }
    normalize_absolute(&combined[..len])
}

fn proc_listing(path: &[u8]) -> bool {
    matches!(
        path,
        b"/proc/sys/net/ipv4/conf"
            | b"/proc/sys/net/ipv4/neigh"
            | b"/proc/sys/net/ipv6/conf"
            | b"/proc/sys/net/ipv6/neigh"
    )
}

fn proc_hidden(path: &[u8]) -> bool {
    const PREFIXES: [&[u8]; 4] = [
        b"/proc/sys/net/ipv4/conf/",
        b"/proc/sys/net/ipv4/neigh/",
        b"/proc/sys/net/ipv6/conf/",
        b"/proc/sys/net/ipv6/neigh/",
    ];
    PREFIXES.iter().any(|prefix| {
        path.strip_prefix(*prefix).is_some_and(|tail| {
            let end = tail
                .iter()
                .position(|byte| *byte == b'/')
                .unwrap_or(tail.len());
            end != 0 && is_vpn_iface_bytes(&tail[..end])
        })
    })
}

pub(crate) fn classify_path(path: &[u8]) -> PathKind {
    if proc_hidden(path) {
        return PathKind::HiddenInterface;
    }
    if proc_listing(path) {
        return PathKind::InterfaceListing;
    }
    if !path.starts_with(b"/sys/") {
        return PathKind::Other;
    }

    let mut cursor = 0usize;
    while let Some(relative) = path.get(cursor..).and_then(|tail| {
        tail.windows(5)
            .position(|window| window == b"/net/")
            .map(|position| cursor + position + 5)
    }) {
        let tail = &path[relative..];
        let end = tail
            .iter()
            .position(|byte| *byte == b'/')
            .unwrap_or(tail.len());
        if end != 0 && is_vpn_iface_bytes(&tail[..end]) {
            return PathKind::HiddenInterface;
        }
        cursor = relative;
        if cursor >= path.len() {
            break;
        }
    }
    if path == b"/sys/net" || path.ends_with(b"/net") {
        PathKind::InterfaceListing
    } else {
        PathKind::Other
    }
}

pub(crate) fn classify_c_path_at(dirfd: c_int, path: *const c_char, allow_empty: bool) -> PathKind {
    let Some(path) = read_caller_path(path) else {
        return PathKind::Other;
    };
    resolve_path_at(dirfd, path.as_bytes(), allow_empty)
        .map_or(PathKind::Other, |path| classify_path(path.as_bytes()))
}

pub(crate) fn hidden_path(dirfd: c_int, path: *const c_char, allow_empty: bool) -> bool {
    if !filesystem_enabled() {
        return false;
    }
    let saved_errno = get_errno();
    let hidden = classify_c_path_at(dirfd, path, allow_empty) == PathKind::HiddenInterface;
    set_errno(saved_errno);
    hidden
}

fn hidden_fd(fd: c_int) -> bool {
    if !filesystem_enabled() {
        return false;
    }
    let saved_errno = get_errno();
    let mut path = [0u8; MAX_RESOLVED_PATH];
    let hidden = resolve_fd_path(fd, &mut path)
        .and_then(|len| normalize_absolute(&path[..len]))
        .is_some_and(|path| classify_path(path.as_bytes()) == PathKind::HiddenInterface);
    set_errno(saved_errno);
    hidden
}

fn listing_fd(fd: c_int) -> bool {
    if !filesystem_enabled() {
        return false;
    }
    let saved_errno = get_errno();
    let mut path = [0u8; MAX_RESOLVED_PATH];
    let listing = resolve_fd_path(fd, &mut path)
        .and_then(|len| normalize_absolute(&path[..len]))
        .is_some_and(|path| classify_path(path.as_bytes()) == PathKind::InterfaceListing);
    set_errno(saved_errno);
    listing
}

macro_rules! saved_original {
    ($ty:ident, $slot:ident, $getter:ident, $setter:ident, $sig:ty) => {
        type $ty = $sig;
        static $slot: AtomicPtr<c_void> = AtomicPtr::new(ptr::null_mut());

        fn $getter() -> Option<$ty> {
            let raw = $slot.load(Ordering::Relaxed);
            (!raw.is_null()).then(|| unsafe { mem::transmute::<*mut c_void, $ty>(raw) })
        }

        pub(crate) fn $setter(pointer: *const ()) {
            $slot.store(pointer as *mut c_void, Ordering::Relaxed);
        }
    };
}

saved_original!(
    OpenFn,
    REAL_OPEN,
    real_open,
    set_real_open_ptr,
    unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
);
saved_original!(
    Open64Fn,
    REAL_OPEN64,
    real_open64,
    set_real_open64_ptr,
    unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
);
saved_original!(
    Openat64Fn,
    REAL_OPENAT64,
    real_openat64,
    set_real_openat64_ptr,
    unsafe extern "C" fn(c_int, *const c_char, c_int, libc::mode_t) -> c_int
);
saved_original!(
    Open2Fn,
    REAL_OPEN_2,
    real_open_2,
    set_real_open_2_ptr,
    unsafe extern "C" fn(*const c_char, c_int) -> c_int
);
saved_original!(
    Openat2Fn,
    REAL_OPENAT_2,
    real_openat_2,
    set_real_openat_2_ptr,
    unsafe extern "C" fn(c_int, *const c_char, c_int) -> c_int
);
saved_original!(
    AccessFn,
    REAL_ACCESS,
    real_access,
    set_real_access_ptr,
    unsafe extern "C" fn(*const c_char, c_int) -> c_int
);
saved_original!(
    FaccessatFn,
    REAL_FACCESSAT,
    real_faccessat,
    set_real_faccessat_ptr,
    unsafe extern "C" fn(c_int, *const c_char, c_int, c_int) -> c_int
);
saved_original!(
    StatFn,
    REAL_STAT,
    real_stat,
    set_real_stat_ptr,
    unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int
);
saved_original!(
    Stat64Fn,
    REAL_STAT64,
    real_stat64,
    set_real_stat64_ptr,
    unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int
);
saved_original!(
    LstatFn,
    REAL_LSTAT,
    real_lstat,
    set_real_lstat_ptr,
    unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int
);
saved_original!(
    Lstat64Fn,
    REAL_LSTAT64,
    real_lstat64,
    set_real_lstat64_ptr,
    unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int
);
saved_original!(
    FstatFn,
    REAL_FSTAT,
    real_fstat,
    set_real_fstat_ptr,
    unsafe extern "C" fn(c_int, *mut libc::stat) -> c_int
);
saved_original!(
    Fstat64Fn,
    REAL_FSTAT64,
    real_fstat64,
    set_real_fstat64_ptr,
    unsafe extern "C" fn(c_int, *mut libc::stat) -> c_int
);
saved_original!(
    FstatatFn,
    REAL_FSTATAT,
    real_fstatat,
    set_real_fstatat_ptr,
    unsafe extern "C" fn(c_int, *const c_char, *mut libc::stat, c_int) -> c_int
);
saved_original!(
    Fstatat64Fn,
    REAL_FSTATAT64,
    real_fstatat64,
    set_real_fstatat64_ptr,
    unsafe extern "C" fn(c_int, *const c_char, *mut libc::stat, c_int) -> c_int
);
saved_original!(
    ReadlinkFn,
    REAL_READLINK,
    real_readlink,
    set_real_readlink_ptr,
    unsafe extern "C" fn(*const c_char, *mut c_char, usize) -> libc::ssize_t
);
saved_original!(
    ReadlinkatFn,
    REAL_READLINKAT,
    real_readlinkat,
    set_real_readlinkat_ptr,
    unsafe extern "C" fn(c_int, *const c_char, *mut c_char, usize) -> libc::ssize_t
);
saved_original!(
    ReadlinkChkFn,
    REAL_READLINK_CHK,
    real_readlink_chk,
    set_real_readlink_chk_ptr,
    unsafe extern "C" fn(*const c_char, *mut c_char, usize, usize) -> libc::ssize_t
);
saved_original!(
    ReadlinkatChkFn,
    REAL_READLINKAT_CHK,
    real_readlinkat_chk,
    set_real_readlinkat_chk_ptr,
    unsafe extern "C" fn(c_int, *const c_char, *mut c_char, usize, usize) -> libc::ssize_t
);
saved_original!(
    ReaddirFn,
    REAL_READDIR,
    real_readdir,
    set_real_readdir_ptr,
    unsafe extern "C" fn(*mut libc::DIR) -> *mut libc::dirent
);
saved_original!(
    Readdir64Fn,
    REAL_READDIR64,
    real_readdir64,
    set_real_readdir64_ptr,
    unsafe extern "C" fn(*mut libc::DIR) -> *mut libc::dirent64
);

fn denied() -> c_int {
    set_errno(libc::ENOENT);
    -1
}

macro_rules! path_wrapper {
    ($name:ident, $real:ident, ($($arg:ident : $ty:ty),*), $dirfd:expr, $path:expr, $allow_empty:expr, $call:expr) => {
        pub(crate) unsafe extern "C" fn $name($($arg: $ty),*) -> c_int {
            let Some(real) = $real() else {
                set_errno(libc::EFAULT);
                return -1;
            };
            if hidden_path($dirfd, $path, $allow_empty) {
                return denied();
            }
            unsafe { $call(real) }
        }
    };
}

path_wrapper!(hooked_open, real_open, (path: *const c_char, flags: c_int, mode: libc::mode_t), libc::AT_FDCWD, path, false, |real: OpenFn| real(path, flags, mode));
path_wrapper!(hooked_open64, real_open64, (path: *const c_char, flags: c_int, mode: libc::mode_t), libc::AT_FDCWD, path, false, |real: Open64Fn| real(path, flags, mode));
path_wrapper!(hooked_openat64, real_openat64, (dirfd: c_int, path: *const c_char, flags: c_int, mode: libc::mode_t), dirfd, path, false, |real: Openat64Fn| real(dirfd, path, flags, mode));
path_wrapper!(hooked_open_2, real_open_2, (path: *const c_char, flags: c_int), libc::AT_FDCWD, path, false, |real: Open2Fn| real(path, flags));
path_wrapper!(hooked_openat_2, real_openat_2, (dirfd: c_int, path: *const c_char, flags: c_int), dirfd, path, false, |real: Openat2Fn| real(dirfd, path, flags));
path_wrapper!(hooked_access, real_access, (path: *const c_char, mode: c_int), libc::AT_FDCWD, path, false, |real: AccessFn| real(path, mode));
path_wrapper!(hooked_faccessat, real_faccessat, (dirfd: c_int, path: *const c_char, mode: c_int, flags: c_int), dirfd, path, flags & libc::AT_EMPTY_PATH != 0, |real: FaccessatFn| real(dirfd, path, mode, flags));
path_wrapper!(hooked_stat, real_stat, (path: *const c_char, out: *mut libc::stat), libc::AT_FDCWD, path, false, |real: StatFn| real(path, out));
path_wrapper!(hooked_stat64, real_stat64, (path: *const c_char, out: *mut libc::stat), libc::AT_FDCWD, path, false, |real: Stat64Fn| real(path, out));
path_wrapper!(hooked_lstat, real_lstat, (path: *const c_char, out: *mut libc::stat), libc::AT_FDCWD, path, false, |real: LstatFn| real(path, out));
path_wrapper!(hooked_lstat64, real_lstat64, (path: *const c_char, out: *mut libc::stat), libc::AT_FDCWD, path, false, |real: Lstat64Fn| real(path, out));
pub(crate) unsafe extern "C" fn hooked_readlink(
    path: *const c_char,
    output: *mut c_char,
    size: usize,
) -> libc::ssize_t {
    let Some(real) = real_readlink() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_path(libc::AT_FDCWD, path, false) {
        set_errno(libc::ENOENT);
        return -1;
    }
    unsafe { real(path, output, size) }
}

pub(crate) unsafe extern "C" fn hooked_fstat(fd: c_int, out: *mut libc::stat) -> c_int {
    let Some(real) = real_fstat() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_fd(fd) {
        return denied();
    }
    unsafe { real(fd, out) }
}

pub(crate) unsafe extern "C" fn hooked_fstat64(fd: c_int, out: *mut libc::stat) -> c_int {
    let Some(real) = real_fstat64() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_fd(fd) {
        return denied();
    }
    unsafe { real(fd, out) }
}

pub(crate) unsafe extern "C" fn hooked_fstatat(
    dirfd: c_int,
    path: *const c_char,
    out: *mut libc::stat,
    flags: c_int,
) -> c_int {
    let Some(real) = real_fstatat() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_path(dirfd, path, flags & libc::AT_EMPTY_PATH != 0) {
        return denied();
    }
    unsafe { real(dirfd, path, out, flags) }
}

pub(crate) unsafe extern "C" fn hooked_fstatat64(
    dirfd: c_int,
    path: *const c_char,
    out: *mut libc::stat,
    flags: c_int,
) -> c_int {
    let Some(real) = real_fstatat64() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_path(dirfd, path, flags & libc::AT_EMPTY_PATH != 0) {
        return denied();
    }
    unsafe { real(dirfd, path, out, flags) }
}

pub(crate) unsafe extern "C" fn hooked_readlinkat(
    dirfd: c_int,
    path: *const c_char,
    output: *mut c_char,
    size: usize,
) -> libc::ssize_t {
    let Some(real) = real_readlinkat() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_path(dirfd, path, false) {
        set_errno(libc::ENOENT);
        return -1;
    }
    unsafe { real(dirfd, path, output, size) }
}

pub(crate) unsafe extern "C" fn hooked_readlink_chk(
    path: *const c_char,
    output: *mut c_char,
    size: usize,
    output_size: usize,
) -> libc::ssize_t {
    let Some(real) = real_readlink_chk() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_path(libc::AT_FDCWD, path, false) {
        set_errno(libc::ENOENT);
        return -1;
    }
    unsafe { real(path, output, size, output_size) }
}

pub(crate) unsafe extern "C" fn hooked_readlinkat_chk(
    dirfd: c_int,
    path: *const c_char,
    output: *mut c_char,
    size: usize,
    output_size: usize,
) -> libc::ssize_t {
    let Some(real) = real_readlinkat_chk() else {
        set_errno(libc::EFAULT);
        return -1;
    };
    if hidden_path(dirfd, path, false) {
        set_errno(libc::ENOENT);
        return -1;
    }
    unsafe { real(dirfd, path, output, size, output_size) }
}

unsafe fn dirent_is_vpn(entry: *const libc::dirent) -> bool {
    let name = unsafe { slice::from_raw_parts((*entry).d_name.as_ptr().cast::<u8>(), 256) };
    let len = name
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(name.len());
    is_vpn_iface_bytes(&name[..len])
}

unsafe fn dirent64_is_vpn(entry: *const libc::dirent64) -> bool {
    let name = unsafe { slice::from_raw_parts((*entry).d_name.as_ptr().cast::<u8>(), 256) };
    let len = name
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(name.len());
    is_vpn_iface_bytes(&name[..len])
}

pub(crate) unsafe extern "C" fn hooked_readdir(dir: *mut libc::DIR) -> *mut libc::dirent {
    let Some(real) = real_readdir() else {
        set_errno(libc::EFAULT);
        return ptr::null_mut();
    };
    let filter = !dir.is_null() && listing_fd(unsafe { libc::dirfd(dir) });
    loop {
        let entry = unsafe { real(dir) };
        if entry.is_null() || !filter || !unsafe { dirent_is_vpn(entry) } {
            return entry;
        }
    }
}

pub(crate) unsafe extern "C" fn hooked_readdir64(dir: *mut libc::DIR) -> *mut libc::dirent64 {
    let Some(real) = real_readdir64() else {
        set_errno(libc::EFAULT);
        return ptr::null_mut();
    };
    let filter = !dir.is_null() && listing_fd(unsafe { libc::dirfd(dir) });
    loop {
        let entry = unsafe { real(dir) };
        if entry.is_null() || !filter || !unsafe { dirent64_is_vpn(entry) } {
            return entry;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classifies_sysfs_interface_paths_and_listings() {
        assert_eq!(classify_path(b"/sys/class/net"), PathKind::InterfaceListing);
        assert_eq!(
            classify_path(b"/sys/class/net/tun0/statistics/rx_bytes"),
            PathKind::HiddenInterface,
        );
        assert_eq!(
            classify_path(b"/sys/devices/virtual/net/wg0"),
            PathKind::HiddenInterface,
        );
        assert_eq!(classify_path(b"/sys/class/net/wlan0"), PathKind::Other);
        assert_eq!(classify_path(b"/data/net/tun0"), PathKind::Other);
    }

    #[test]
    fn classifies_proc_sys_interface_paths_and_listings() {
        assert_eq!(
            classify_path(b"/proc/sys/net/ipv4/conf"),
            PathKind::InterfaceListing,
        );
        assert_eq!(
            classify_path(b"/proc/sys/net/ipv6/neigh/tailscale0/gc_stale_time"),
            PathKind::HiddenInterface,
        );
        assert_eq!(
            classify_path(b"/proc/sys/net/ipv4/conf/default"),
            PathKind::Other,
        );
        assert_eq!(classify_path(b"/proc/net/tun0"), PathKind::Other);
    }

    #[test]
    fn normalizes_dot_segments_without_escaping_root() {
        let path = normalize_absolute(b"/sys/class/./net/../net//tun0/ifindex").unwrap();
        assert_eq!(path.as_bytes(), b"/sys/class/net/tun0/ifindex");
        assert_eq!(classify_path(path.as_bytes()), PathKind::HiddenInterface);

        let path = normalize_absolute(b"/../../sys/class/net/wlan0").unwrap();
        assert_eq!(path.as_bytes(), b"/sys/class/net/wlan0");
    }
}

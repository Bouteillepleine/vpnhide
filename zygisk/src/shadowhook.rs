//! Minimal FFI bindings for ByteDance's android-inline-hook (shadowhook).
//!
//! Upstream: <https://github.com/bytedance/android-inline-hook>
//!
//! We only bind the three entry points we actually need. shadowhook itself
//! is linked as a static archive (`libshadowhook.a`) built for
//! `aarch64-linux-android`; see `build.rs`.

use core::ffi::{c_char, c_int, c_void};
use std::sync::OnceLock;

// `SHADOWHOOK_MODE_UNIQUE` from shadowhook.h. We never use shared mode, so a
// constant represents the one FFI value we pass without carrying a dead enum
// variant solely to mirror the C declaration.
const SHADOWHOOK_MODE_UNIQUE: c_int = 1;

unsafe extern "C" {
    /// Initialize shadowhook. Safe to call more than once; subsequent calls
    /// after the first are no-ops and return 0.
    fn shadowhook_init(mode: c_int, debuggable: bool) -> c_int;

    /// Install an inline hook on `sym_name` as exported by `lib_name`
    /// (e.g. `"libc.so"`). On success returns an opaque non-null stub and
    /// writes the trampoline-to-original into `*orig_addr`. Returns null on
    /// failure; call `shadowhook_get_errno()` for details (not bound here).
    fn shadowhook_hook_sym_name(
        lib_name: *const c_char,
        sym_name: *const c_char,
        new_addr: *mut c_void,
        orig_addr: *mut *mut c_void,
    ) -> *mut c_void;

    fn shadowhook_unhook(stub: *mut c_void) -> c_int;
}

static INIT_RC: OnceLock<c_int> = OnceLock::new();

/// Initialize shadowhook exactly once per process. Returns Ok on success
/// or if already initialized; Err with the raw return code otherwise.
pub fn init_once() -> Result<(), c_int> {
    let rc = *INIT_RC.get_or_init(|| {
        // SAFETY: FFI call with no arguments that reference Rust memory.
        unsafe { shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) }
    });
    if rc == 0 { Ok(()) } else { Err(rc) }
}

/// Install an inline hook on `lib!sym`. `new_fn` is the replacement
/// function; on success the original-trampoline pointer is written to
/// `*out_orig`. Returns the shadowhook stub (opaque) or null on failure.
///
/// # Safety
///
/// `new_fn` must be a valid function pointer with a signature ABI-compatible
/// with the real target symbol. `out_orig` must be a valid writable pointer.
pub unsafe fn hook_sym(
    lib: &core::ffi::CStr,
    sym: &core::ffi::CStr,
    new_fn: *mut c_void,
    out_orig: *mut *mut c_void,
) -> *mut c_void {
    unsafe { shadowhook_hook_sym_name(lib.as_ptr(), sym.as_ptr(), new_fn, out_orig) }
}

/// Remove a hook previously installed by `hook_sym`. `stub` is the
/// non-null pointer that `hook_sym` returned. Returns 0 on success,
/// non-zero on failure (best-effort — there's nothing useful to do
/// with a failure here, since we only call this during partial-install
/// rollback).
///
/// # Safety
///
/// `stub` must be a non-null pointer previously returned by
/// `hook_sym`, and not already unhooked.
pub unsafe fn unhook(stub: *mut c_void) -> c_int {
    unsafe { shadowhook_unhook(stub) }
}

use std::process;

use vpnhide_activator::{activate_kmod, activate_kmod_boot, boot_wait_requested_from_env};

fn main() {
    let boot_wait = match boot_wait_requested_from_env() {
        Ok(value) => value,
        Err(e) => {
            eprintln!("vpnhide kmod activator failed: {e}");
            process::exit(2);
        }
    };
    let result = if boot_wait {
        activate_kmod_boot()
    } else {
        activate_kmod()
    };
    if let Err(e) = result {
        eprintln!("vpnhide kmod activator failed: {e}");
        process::exit(1);
    }
}

use std::process;

use vpnhide_activator::{activate_zygisk, activate_zygisk_boot, boot_wait_requested_from_env};

fn main() {
    let boot_wait = match boot_wait_requested_from_env() {
        Ok(value) => value,
        Err(e) => {
            eprintln!("vpnhide zygisk activator failed: {e}");
            process::exit(2);
        }
    };
    let result = if boot_wait {
        activate_zygisk_boot()
    } else {
        activate_zygisk()
    };
    if let Err(e) = result {
        eprintln!("vpnhide zygisk activator failed: {e}");
        process::exit(1);
    }
}

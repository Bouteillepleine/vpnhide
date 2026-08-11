use std::process;

use vpnhide_activator::{activate_ports_recorded, boot_wait_requested_from_env};

fn main() {
    let boot_wait = match boot_wait_requested_from_env() {
        Ok(value) => value,
        Err(e) => {
            eprintln!("vpnhide ports activator failed: {e}");
            process::exit(2);
        }
    };
    if let Err(e) = activate_ports_recorded(boot_wait) {
        eprintln!("vpnhide ports activator failed: {e}");
        process::exit(1);
    }
}

use std::{env, process};

use vpnhide_activator::{Result, activate_kmod, boot_load_kmod, boot_service_kmod, uninstall_kmod};

fn main() {
    if let Err(e) = run() {
        eprintln!("vpnhide kmod activator failed: {e}");
        process::exit(1);
    }
}

fn run() -> Result<()> {
    match env::args().skip(1).collect::<Vec<_>>().as_slice() {
        [] => activate_kmod(),
        [command] if command == "boot-load" => boot_load_kmod(),
        [command] if command == "boot-service" => boot_service_kmod(),
        [command] if command == "uninstall" => uninstall_kmod(),
        _ => Err("usage: activator [boot-load|boot-service|uninstall]".into()),
    }
}

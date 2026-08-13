use std::process;

use vpnhide_activator::{Result, activate_zygisk, boot_service_zygisk, uninstall_zygisk};

fn main() {
    if let Err(e) = run() {
        eprintln!("vpnhide zygisk activator failed: {e}");
        process::exit(1);
    }
}

fn run() -> Result<()> {
    match std::env::args().skip(1).collect::<Vec<_>>().as_slice() {
        [] => activate_zygisk(),
        [command] if command == "boot-service" => boot_service_zygisk(),
        [command] if command == "uninstall" => {
            uninstall_zygisk();
            Ok(())
        }
        _ => Err("usage: activator [boot-service|uninstall]".into()),
    }
}

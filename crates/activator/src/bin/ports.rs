use std::process;

use vpnhide_activator::{Result, activate_ports_recorded, boot_service_ports, uninstall_ports};

fn main() {
    if let Err(e) = run() {
        eprintln!("vpnhide ports activator failed: {e}");
        process::exit(1);
    }
}

fn run() -> Result<()> {
    match std::env::args().skip(1).collect::<Vec<_>>().as_slice() {
        [] => activate_ports_recorded(false),
        [command] if command == "boot-service" => boot_service_ports(),
        [command] if command == "uninstall" => uninstall_ports(),
        _ => Err("usage: activator [boot-service|uninstall]".into()),
    }
}

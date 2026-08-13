use std::{env, process};

use vpnhide_activator::{
    Result, activate_kpm, boot_load_kpm, boot_service_kpm, read_kpm_state, read_kpm_stats,
    read_kpm_status, uninstall_kpm,
};

fn main() {
    match run() {
        Ok(code) => process::exit(code),
        Err(e) => {
            eprintln!("vpnhide kpm activator failed: {e}");
            process::exit(1);
        }
    }
}

fn run() -> Result<i32> {
    let args = env::args().skip(1).collect::<Vec<_>>();
    match args.as_slice() {
        [] => activate_kpm().map(|()| 0),
        [arg] if arg == "boot-load" => boot_load_kpm().map(|()| 0),
        [arg] if arg == "boot-service" => boot_service_kpm().map(|()| 0),
        [arg] if arg == "uninstall" => uninstall_kpm().map(|()| 0),
        [arg] if arg == "status" => {
            print!("{}", read_kpm_status()?);
            Ok(0)
        }
        [arg] if arg == "stats" => {
            print!("{}", read_kpm_stats()?);
            Ok(0)
        }
        [arg] if arg == "state" => {
            print!("{}", read_kpm_state()?);
            Ok(0)
        }
        _ => Err("usage: activator [boot-load|boot-service|uninstall|status|stats|state]".into()),
    }
}

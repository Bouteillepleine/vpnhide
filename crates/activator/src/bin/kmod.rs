use std::{env, process};

use vpnhide_activator::{activate_kmod, activate_kmod_boot, filesystem_hiding_enabled};

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.as_slice() == ["filesystem-hiding-enabled"] {
        match filesystem_hiding_enabled() {
            Ok(true) => return,
            Ok(false) => process::exit(1),
            Err(e) => {
                eprintln!("vpnhide kmod activator failed: {e}");
                process::exit(2);
            }
        }
    }

    let boot_wait = match args.as_slice() {
        [] => false,
        [arg] if arg == "--boot-wait" => true,
        _ => {
            eprintln!("usage: activator [--boot-wait|filesystem-hiding-enabled]");
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

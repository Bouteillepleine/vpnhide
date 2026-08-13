use std::{env, process};

use vpnhide_activator::{activate_kmod, activate_kmod_boot, kernel_boot_feature_enabled};

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if let [command, feature] = args.as_slice()
        && command == "boot-feature-enabled"
    {
        match kernel_boot_feature_enabled(feature) {
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
            eprintln!("usage: activator [--boot-wait|boot-feature-enabled <name>]");
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

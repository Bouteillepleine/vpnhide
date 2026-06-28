fn main() {
    let boot_wait = match vpnhide_activator::boot_wait_requested_from_env() {
        Ok(value) => value,
        Err(e) => {
            eprintln!("vpnhide ports activator failed: {e}");
            std::process::exit(2);
        }
    };
    let result = if boot_wait {
        vpnhide_activator::activate_ports_boot()
    } else {
        vpnhide_activator::activate_ports()
    };
    if let Err(e) = result {
        eprintln!("vpnhide ports activator failed: {e}");
        std::process::exit(1);
    }
}

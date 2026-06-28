fn main() {
    if let Err(e) = vpnhide_activator::activate_kpm() {
        eprintln!("vpnhide kpm activator failed: {e}");
        std::process::exit(1);
    }
}

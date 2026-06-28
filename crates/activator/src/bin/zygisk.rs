fn main() {
    if let Err(e) = vpnhide_activator::activate_zygisk() {
        eprintln!("vpnhide zygisk activator failed: {e}");
        std::process::exit(1);
    }
}

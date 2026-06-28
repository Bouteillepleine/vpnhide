fn main() {
    if let Err(e) = vpnhide_activator::activate_kmod() {
        eprintln!("vpnhide kmod activator failed: {e}");
        std::process::exit(1);
    }
}

fn main() {
    if let Err(e) = run() {
        eprintln!("vpnhide kpm activator failed: {e}");
        std::process::exit(1);
    }
}

fn run() -> vpnhide_activator::Result<()> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    match args.as_slice() {
        [] => vpnhide_activator::activate_kpm(),
        [arg] if arg == "--boot-wait" => vpnhide_activator::activate_kpm_boot(),
        [arg] if arg == "status" => {
            print!("{}", vpnhide_activator::read_kpm_status()?);
            Ok(())
        }
        [arg] if arg == "stats" => {
            print!("{}", vpnhide_activator::read_kpm_stats()?);
            Ok(())
        }
        [arg] if arg == "state" => {
            print!("{}", vpnhide_activator::read_kpm_state()?);
            Ok(())
        }
        _ => Err("usage: activator [--boot-wait|status|stats|state]".into()),
    }
}

use vpnhide_activator::KpmBootOutcome;

/// Exit code returned from `--boot-wait` when the KPM stood down because the
/// .ko backend is present (protocol §1.5). Distinct from 0 (configured) and 1
/// (error) so service.sh can record a truthful `conflict` load_status instead
/// of falsely reporting the KPM as configured.
const EXIT_DEFERRED_CONFLICT: i32 = 3;

fn main() {
    match run() {
        Ok(code) => std::process::exit(code),
        Err(e) => {
            eprintln!("vpnhide kpm activator failed: {e}");
            std::process::exit(1);
        }
    }
}

fn run() -> vpnhide_activator::Result<i32> {
    let args = std::env::args().skip(1).collect::<Vec<_>>();
    match args.as_slice() {
        [] => vpnhide_activator::activate_kpm().map(|_| 0),
        [arg] if arg == "--boot-wait" => match vpnhide_activator::activate_kpm_boot()? {
            KpmBootOutcome::Configured => Ok(0),
            KpmBootOutcome::DeferredConflict => Ok(EXIT_DEFERRED_CONFLICT),
        },
        [arg] if arg == "status" => {
            print!("{}", vpnhide_activator::read_kpm_status()?);
            Ok(0)
        }
        [arg] if arg == "stats" => {
            print!("{}", vpnhide_activator::read_kpm_stats()?);
            Ok(0)
        }
        [arg] if arg == "state" => {
            print!("{}", vpnhide_activator::read_kpm_state()?);
            Ok(0)
        }
        _ => Err("usage: activator [--boot-wait|status|stats|state]".into()),
    }
}

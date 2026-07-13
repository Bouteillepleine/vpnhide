use vpnhide_activator::KpmBootOutcome;

/// Exit code returned from `--boot-wait` when the KPM stood down because the
/// .ko backend is present (protocol §1.5). Distinct from 0 (configured) and 1
/// (error) so service.sh can record a truthful `conflict` load_status instead
/// of falsely reporting the KPM as configured.
const EXIT_DEFERRED_CONFLICT: i32 = 3;
/// APatch is present but authentication is not available yet. This is a
/// deferred boot state, not a generic activator failure.
const EXIT_AWAITING_AUTHENTICATION: i32 = 4;

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
        [arg] if arg == "--boot-wait" => {
            Ok(boot_exit_code(vpnhide_activator::activate_kpm_boot()?))
        }
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

fn boot_exit_code(outcome: KpmBootOutcome) -> i32 {
    match outcome {
        KpmBootOutcome::Configured => 0,
        KpmBootOutcome::DeferredConflict => EXIT_DEFERRED_CONFLICT,
        KpmBootOutcome::AwaitingAuthentication => EXIT_AWAITING_AUTHENTICATION,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn boot_outcomes_have_stable_machine_readable_exit_codes() {
        assert_eq!(boot_exit_code(KpmBootOutcome::Configured), 0);
        assert_eq!(boot_exit_code(KpmBootOutcome::DeferredConflict), 3);
        assert_eq!(boot_exit_code(KpmBootOutcome::AwaitingAuthentication), 4);
    }
}

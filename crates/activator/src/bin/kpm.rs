use std::{env, process};

use vpnhide_activator::{
    KpmBootOutcome, Result, activate_kpm, activate_kpm_boot, kernel_boot_feature_enabled,
    load_kpm_boot, read_kpm_state, read_kpm_stats, read_kpm_status,
};

/// Exit code returned from `--boot-wait` when the KPM stood down because the
/// .ko backend is present (docs/storage.md §4.3). Distinct from 0 (configured)
/// and 1
/// (error) so service.sh can record a truthful `conflict` load_status instead
/// of falsely reporting the KPM as configured.
const EXIT_DEFERRED_CONFLICT: i32 = 3;
/// APatch is present but authentication is not available yet. This is a
/// deferred boot state, not a generic activator failure.
const EXIT_AWAITING_AUTHENTICATION: i32 = 4;
/// The running kernel has no validated KPM offset table.
const EXIT_UNSUPPORTED_KERNEL: i32 = 5;

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
        [arg] if arg == "--boot-wait" => Ok(boot_exit_code(activate_kpm_boot()?)),
        [arg] if arg == "--load-only" => Ok(boot_exit_code(load_kpm_boot()?)),
        [command, feature] if command == "boot-feature-enabled" => {
            Ok(if kernel_boot_feature_enabled(feature)? { 0 } else { 1 })
        }
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
        _ => Err(
            "usage: activator [--boot-wait|--load-only|boot-feature-enabled <name>|status|stats|state]"
                .into(),
        ),
    }
}

fn boot_exit_code(outcome: KpmBootOutcome) -> i32 {
    match outcome {
        KpmBootOutcome::Configured => 0,
        KpmBootOutcome::DeferredConflict => EXIT_DEFERRED_CONFLICT,
        KpmBootOutcome::AwaitingAuthentication => EXIT_AWAITING_AUTHENTICATION,
        KpmBootOutcome::UnsupportedKernel => EXIT_UNSUPPORTED_KERNEL,
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
        assert_eq!(boot_exit_code(KpmBootOutcome::UnsupportedKernel), 5);
    }
}

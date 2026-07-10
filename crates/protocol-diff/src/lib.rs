//! Test-only host bridge for differential C ↔ Rust protocol checks.
//!
//! Keeping this in its own workspace crate prevents the C oracle from being
//! linked into any Android runtime artifact.

#[cfg(test)]
mod tests {
    use proptest::prelude::*;
    use vpnhide_protocol::{Config, Target, parse_config};

    const C_TARGET_CAPACITY: usize = 128;

    #[derive(Clone, Copy, Default)]
    #[repr(C)]
    struct CTarget {
        uid: u32,
        hookmask: u32,
    }

    unsafe extern "C" {
        fn vpnhide_diff_parse_config(
            input: *const u8,
            len: usize,
            targets: *mut CTarget,
            capacity: i32,
            debug: *mut i32,
        ) -> i32;
    }

    fn parse_with_c(input: &[u8]) -> Option<Config> {
        let mut targets = [CTarget::default(); C_TARGET_CAPACITY];
        let mut debug = -1;
        let count = unsafe {
            vpnhide_diff_parse_config(
                input.as_ptr(),
                input.len(),
                targets.as_mut_ptr(),
                C_TARGET_CAPACITY as i32,
                &mut debug,
            )
        };
        if count < 0 {
            return None;
        }
        Some(Config {
            debug: match debug {
                0 => Some(false),
                1 => Some(true),
                _ => None,
            },
            targets: targets[..count as usize]
                .iter()
                .map(|target| Target {
                    uid: target.uid,
                    hookmask: target.hookmask,
                })
                .collect(),
        })
    }

    proptest! {
        #![proptest_config(ProptestConfig::with_cases(2_048))]

        #[test]
        fn c_and_rust_config_parsers_agree(input in prop::collection::vec(any::<u8>(), 0..1024)) {
            prop_assert_eq!(parse_with_c(&input), parse_config(&input));
        }
    }
}

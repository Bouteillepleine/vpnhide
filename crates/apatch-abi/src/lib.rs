//! KernelPatch/APatch supercall command compatibility shared by the KPM
//! activator and the APK's read-only runtime probe.

pub const APATCH_SUPERCALL_NR: i64 = 45;
pub const APATCH_SUPERCALL_DEFAULT_VERSION_CODE: i64 = 0x000d00;
pub const APATCH_SUPERCALL_MAGIC: i64 = 0x1158;
pub const APATCH_SUPERCALL_VERSION_FALLBACKS: &[i64] = &[
    0x000d02, 0x000d01, 0x000c02, 0x000c01, 0x000c00, 0x000b01, 0x000b00, 0x000a05,
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CommandStyle {
    Versioned(i64),
    Raw,
}

pub fn command_candidates(version_hint: Option<i64>) -> Vec<CommandStyle> {
    let mut styles = Vec::new();
    for style in version_hint
        .map(CommandStyle::Versioned)
        .into_iter()
        .chain(std::iter::once(CommandStyle::Versioned(
            APATCH_SUPERCALL_DEFAULT_VERSION_CODE,
        )))
        .chain(
            APATCH_SUPERCALL_VERSION_FALLBACKS
                .iter()
                .copied()
                .map(CommandStyle::Versioned),
        )
        .chain(std::iter::once(CommandStyle::Raw))
    {
        if !styles.contains(&style) {
            styles.push(style);
        }
    }
    styles
}

pub fn encode_command(style: CommandStyle, command: i64) -> i64 {
    match style {
        CommandStyle::Versioned(version) => {
            (version << 32) | (APATCH_SUPERCALL_MAGIC << 16) | (command & 0xffff)
        }
        CommandStyle::Raw => command,
    }
}

pub fn parse_kernel_version_hint(log: &str) -> Option<i64> {
    const MARKER: &str = "KP KernelPatch Version:";
    log.lines().rev().find_map(|line| {
        let (_, tail) = line.split_once(MARKER)?;
        let value = tail
            .trim_start()
            .strip_prefix("0x")
            .unwrap_or_else(|| tail.trim_start())
            .split(|character: char| !character.is_ascii_hexdigit())
            .next()?;
        if value.is_empty() {
            return None;
        }
        i64::from_str_radix(value, 16).ok()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidates_cover_hint_fallbacks_and_raw_abi_once() {
        let candidates = command_candidates(Some(0x000d01));
        assert_eq!(candidates.first(), Some(&CommandStyle::Versioned(0x000d01)));
        assert_eq!(candidates.last(), Some(&CommandStyle::Raw));
        assert_eq!(
            candidates
                .iter()
                .filter(|style| **style == CommandStyle::Versioned(0x000d01))
                .count(),
            1
        );
    }

    #[test]
    fn encoding_keeps_magic_and_low_command_bits() {
        assert_eq!(
            encode_command(CommandStyle::Versioned(0x000d03), 0x1031),
            (0x000d03_i64 << 32) | (0x1158_i64 << 16) | 0x1031,
        );
        assert_eq!(encode_command(CommandStyle::Raw, 0x1031), 0x1031);
    }

    #[test]
    fn parses_latest_version_hint_from_dmesg() {
        let log =
            "old\nKP KernelPatch Version: 000d02\nnoise\nKP KernelPatch Version: 000d03-extra\n";
        assert_eq!(parse_kernel_version_hint(log), Some(0x000d03));
    }
}

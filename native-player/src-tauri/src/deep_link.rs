//! Strict parser for `aocplayer://` links (ADR-025 §2).
//!
//! A link carries a one-time code and nothing else: no host, tenant, user,
//! video or token. Anyone can start the player with a hand-made link on the
//! command line (the Tauri docs warn about this), so anything that is not
//! exactly `aocplayer://pair?code=<43>` or `aocplayer://play?code=<43>` is
//! rejected before any network call.

/// A code is 256 bits, base64url without padding.
const CODE_LENGTH: usize = 43;

/// Longest valid link: `aocplayer://pair/?code=` + 43 characters.
const MAX_LINK_LENGTH: usize = 80;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeepLink {
    /// `aocplayer://pair?code=…`: pair this installation with the student's device.
    Pair(String),
    /// `aocplayer://play?code=…`: claim one pending playback session.
    Play(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("not a valid AOC Player link")]
pub struct InvalidLink;

pub fn parse(raw: &str) -> Result<DeepLink, InvalidLink> {
    if raw.len() > MAX_LINK_LENGTH || !raw.is_ascii() {
        return Err(InvalidLink);
    }
    let rest = raw.strip_prefix("aocplayer://").ok_or(InvalidLink)?;
    let (target, query) = rest.split_once('?').ok_or(InvalidLink)?;
    let action = match target {
        "pair" | "pair/" => DeepLink::Pair as fn(String) -> DeepLink,
        "play" | "play/" => DeepLink::Play,
        _ => return Err(InvalidLink),
    };
    let code = query.strip_prefix("code=").ok_or(InvalidLink)?;
    if !is_code(code) {
        return Err(InvalidLink);
    }
    Ok(action(code.to_owned()))
}

fn is_code(code: &str) -> bool {
    code.len() == CODE_LENGTH
        && code
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;

    const CODE: &str = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";

    #[test]
    fn accepts_pair_and_play_links() {
        assert_eq!(CODE.len(), 43);
        assert_eq!(
            parse(&format!("aocplayer://pair?code={CODE}")),
            Ok(DeepLink::Pair(CODE.into()))
        );
        assert_eq!(
            parse(&format!("aocplayer://play?code={CODE}")),
            Ok(DeepLink::Play(CODE.into()))
        );
        // Windows may hand the link over with a slash before the query.
        assert_eq!(
            parse(&format!("aocplayer://play/?code={CODE}")),
            Ok(DeepLink::Play(CODE.into()))
        );
    }

    #[test]
    fn rejects_other_hosts_paths_and_schemes() {
        for link in [
            format!("aocplayer://login?code={CODE}"),
            format!("aocplayer://Play?code={CODE}"),
            format!("aocplayer://play/x?code={CODE}"),
            format!("aocplayer://evil.example.com/play?code={CODE}"),
            format!("aocplayer://user@play?code={CODE}"),
            format!("aocplayer://play:443?code={CODE}"),
            format!("aocplayerx://play?code={CODE}"),
            format!("AOCPLAYER://play?code={CODE}"),
            format!("https://play?code={CODE}"),
            format!(" aocplayer://play?code={CODE}"),
        ] {
            assert_eq!(parse(&link), Err(InvalidLink), "{link}");
        }
    }

    #[test]
    fn rejects_extra_or_missing_parameters() {
        for link in [
            "aocplayer://play".to_owned(),
            "aocplayer://play?".to_owned(),
            "aocplayer://play?code=".to_owned(),
            format!("aocplayer://play?code={CODE}&api=https://evil.example.com"),
            format!("aocplayer://play?api=x&code={CODE}"),
            format!("aocplayer://play?code={CODE}#x"),
            format!("aocplayer://play?code={CODE}&code={CODE}"),
            format!("aocplayer://play?Code={CODE}"),
        ] {
            assert_eq!(parse(&link), Err(InvalidLink), "{link}");
        }
    }

    #[test]
    fn rejects_malformed_codes() {
        let short = &CODE[..42];
        let long = format!("{CODE}A");
        let padded = format!("{}=", &CODE[..42]);
        let encoded = format!("{}%41", &CODE[..40]);
        let unicode = format!("{}é", &CODE[..41]);
        for code in [
            short,
            &long,
            &padded,
            &encoded,
            &unicode,
            &"+".repeat(43),
            &"/".repeat(43),
        ] {
            let link = format!("aocplayer://play?code={code}");
            assert_eq!(parse(&link), Err(InvalidLink), "{link}");
        }
        assert_eq!(parse(&"a".repeat(10_000)), Err(InvalidLink));
    }
}

//! Build-time configuration. The API origin is compiled into the binary
//! (ADR-025 §1): the player never takes a host from a deep link, a file or
//! the user.

use url::Url;

pub const PLAYER_VERSION: &str = env!("CARGO_PKG_VERSION");

/// The only platform the backend accepts today (ADR-025, Q-015).
pub const PLATFORM: &str = "WINDOWS";

/// Debug builds talk to the local backend unless `AOC_API_ORIGIN` says otherwise.
const DEBUG_DEFAULT_ORIGIN: &str = "http://localhost:8080";

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ConfigError {
    #[error("no API origin was compiled into this build")]
    Missing,
    #[error("the compiled API origin is not a valid https origin")]
    Invalid,
}

/// The pinned API origin for this build.
pub fn api_origin() -> Result<Url, ConfigError> {
    let raw = option_env!("AOC_API_ORIGIN").or(if cfg!(debug_assertions) {
        Some(DEBUG_DEFAULT_ORIGIN)
    } else {
        None
    });
    parse_origin(raw.ok_or(ConfigError::Missing)?, cfg!(debug_assertions))
}

/// Accepts a bare origin: `https://host[:port]`, or `http://` to a loopback
/// host when `allow_loopback_http` (debug builds only).
pub fn parse_origin(raw: &str, allow_loopback_http: bool) -> Result<Url, ConfigError> {
    let url = Url::parse(raw.trim()).map_err(|_| ConfigError::Invalid)?;
    let bare = url.path() == "/"
        && url.query().is_none()
        && url.fragment().is_none()
        && url.username().is_empty()
        && url.password().is_none();
    let host = url.host_str().ok_or(ConfigError::Invalid)?;
    let scheme_ok = match url.scheme() {
        "https" => true,
        "http" => allow_loopback_http && matches!(host, "localhost" | "127.0.0.1" | "[::1]"),
        _ => false,
    };
    if bare && scheme_ok {
        Ok(url)
    } else {
        Err(ConfigError::Invalid)
    }
}

/// Debug builds accept files from the local MinIO unless `AOC_MEDIA_ORIGINS` says otherwise.
const DEBUG_DEFAULT_MEDIA_ORIGINS: &str = "http://localhost:9000,http://127.0.0.1:9000";

/// Where platform-hosted (INTERNAL) video files may come from (Phase 10):
/// the object-storage / CDN origins compiled into this build, comma-separated
/// in `AOC_MEDIA_ORIGINS`. A signed URL on any other host is never played.
pub fn media_origins() -> Result<Vec<Url>, ConfigError> {
    let raw = option_env!("AOC_MEDIA_ORIGINS").or(if cfg!(debug_assertions) {
        Some(DEBUG_DEFAULT_MEDIA_ORIGINS)
    } else {
        None
    });
    parse_origins(raw.ok_or(ConfigError::Missing)?, cfg!(debug_assertions))
}

pub fn parse_origins(raw: &str, allow_loopback_http: bool) -> Result<Vec<Url>, ConfigError> {
    raw.split(',')
        .filter(|part| !part.trim().is_empty())
        .map(|part| parse_origin(part, allow_loopback_http))
        .collect()
}

/// Debug builds frame the dev bridge unless `AOC_EMBED_BRIDGE_URL` says otherwise.
const DEBUG_DEFAULT_EMBED_BRIDGE_URL: &str = "http://embed.lms.test:3000/embed/dyntube-bridge.html";

/// The DynTube bridge page (VIDEO Phase 13c, ADR-026 Amendment 1), compiled
/// in from `AOC_EMBED_BRIDGE_URL`. The player frames only this page; the
/// server's `bridgeUrl` is never used. A release build without one can't play
/// embedded video.
pub fn embed_bridge_url() -> Result<Url, ConfigError> {
    let raw = option_env!("AOC_EMBED_BRIDGE_URL").or(if cfg!(debug_assertions) {
        Some(DEBUG_DEFAULT_EMBED_BRIDGE_URL)
    } else {
        None
    });
    parse_bridge_url(raw.ok_or(ConfigError::Missing)?, cfg!(debug_assertions))
}

/// An `.html` page over https (or http in debug builds), with no credentials,
/// query or fragment.
pub fn parse_bridge_url(raw: &str, allow_http: bool) -> Result<Url, ConfigError> {
    let url = Url::parse(raw.trim()).map_err(|_| ConfigError::Invalid)?;
    let scheme_ok = url.scheme() == "https" || (allow_http && url.scheme() == "http");
    let clean = url.username().is_empty()
        && url.password().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.host_str().is_some()
        && url.path().ends_with(".html");
    if scheme_ok && clean {
        Ok(url)
    } else {
        Err(ConfigError::Invalid)
    }
}

/// Whether plain HTTP may be used at all (debug builds against loopback).
pub fn allows_http(origin: &Url) -> bool {
    origin.scheme() == "http"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_https_origins() {
        let url = parse_origin("https://lms.example.com", false).unwrap();
        assert_eq!(url.as_str(), "https://lms.example.com/");
        assert!(parse_origin("https://lms.example.com:8443/", false).is_ok());
    }

    #[test]
    fn http_only_for_loopback_in_debug() {
        assert!(parse_origin("http://localhost:8080", true).is_ok());
        assert!(parse_origin("http://127.0.0.1:8080", true).is_ok());
        assert_eq!(
            parse_origin("http://localhost:8080", false),
            Err(ConfigError::Invalid)
        );
        assert_eq!(
            parse_origin("http://lms.example.com", true),
            Err(ConfigError::Invalid)
        );
    }

    #[test]
    fn rejects_anything_but_a_bare_origin() {
        for raw in [
            "https://lms.example.com/api",
            "https://lms.example.com/?x=1",
            "https://lms.example.com/#x",
            "https://user:pw@lms.example.com",
            "ftp://lms.example.com",
            "aocplayer://play",
            "not a url",
        ] {
            assert_eq!(parse_origin(raw, true), Err(ConfigError::Invalid), "{raw}");
        }
    }
}

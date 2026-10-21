//! Which playback engine the UI runs, decided and validated here in Rust
//! (Phase 10). The UI never sees a media reference this module did not
//! accept: a YouTube id must look like one, a file URL must be on one of
//! the media origins compiled into the build, and (VIDEO Phase 13, ADR-026)
//! an HLS playlist must be a DynTube tokenized playlist on a compiled-in host.
//! VIDEO Phase 13c (ADR-026 Amendment 1): a DynTube video plays in DynTube's
//! own player inside the compiled-in bridge page; only its embed key comes
//! from the server.

use serde::Serialize;
use url::Url;

use crate::api::Descriptor;
use crate::config;

/// What the UI should play, tagged by engine.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(
    tag = "engine",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum Media {
    /// The YouTube embed with its own controls hidden, driven by our control bar
    /// (ADR-025 Amendment 3); seeking follows the institute's rule like files.
    Youtube {
        video_id: String,
        allow_seeking: bool,
    },
    /// A platform-hosted file behind a short-lived signed URL.
    File { url: String, allow_seeking: bool },
    /// A managed-streaming (DynTube) HLS playlist behind a short-lived token.
    Hls { url: String, allow_seeking: bool },
    /// DynTube's own player inside the compiled-in bridge page (Phase 13c).
    Embed {
        key: String,
        bridge_url: String,
        allow_seeking: bool,
    },
    /// Anything this player cannot play; the UI shows a refusal.
    Unsupported,
}

impl Media {
    pub fn from_descriptor(descriptor: &Descriptor, media_origins: &[Url]) -> Self {
        Self::from_descriptor_with_bridge(
            descriptor,
            media_origins,
            config::embed_bridge_url().ok().as_ref(),
        )
    }

    /// `bridge` is the compiled-in bridge page; `None` means embedded video
    /// can't play in this build.
    pub fn from_descriptor_with_bridge(
        descriptor: &Descriptor,
        media_origins: &[Url],
        bridge: Option<&Url>,
    ) -> Self {
        match descriptor.provider.as_str() {
            "YOUTUBE" => descriptor
                .provider_video_id
                .as_deref()
                .filter(|id| is_youtube_id(id))
                .map_or(Media::Unsupported, |id| Media::Youtube {
                    video_id: id.to_owned(),
                    allow_seeking: descriptor.allow_seeking,
                }),
            "INTERNAL" => descriptor
                .signed_url
                .as_deref()
                .and_then(|url| checked_file_url(url, media_origins))
                .map_or(Media::Unsupported, |url| Media::File {
                    url,
                    allow_seeking: descriptor.allow_seeking,
                }),
            "DYNTUBE" if descriptor.media_format.as_deref() == Some("EMBED") => {
                match (descriptor.embed.as_ref(), bridge) {
                    (Some(embed), Some(bridge))
                        if embed.provider == "DYNTUBE" && is_embed_key(&embed.key) =>
                    {
                        Media::Embed {
                            key: embed.key.clone(),
                            bridge_url: bridge.to_string(),
                            allow_seeking: descriptor.allow_seeking,
                        }
                    }
                    _ => Media::Unsupported,
                }
            }
            // A player that cannot tell the format never guesses: no `HLS`, no playback.
            "DYNTUBE" if descriptor.media_format.as_deref() == Some("HLS") => descriptor
                .signed_url
                .as_deref()
                .and_then(checked_hls_url)
                .map_or(Media::Unsupported, |url| Media::Hls {
                    url,
                    allow_seeking: descriptor.allow_seeking,
                }),
            _ => Media::Unsupported,
        }
    }

    /// The same media with a fresh signed file URL from a heartbeat; `None`
    /// when this is not a file or the new URL is not acceptable.
    pub fn with_fresh_url(&self, raw: &str, media_origins: &[Url]) -> Option<Media> {
        match self {
            Media::File { allow_seeking, .. } => {
                checked_file_url(raw, media_origins).map(|url| Media::File {
                    url,
                    allow_seeking: *allow_seeking,
                })
            }
            Media::Hls { allow_seeking, .. } => checked_hls_url(raw).map(|url| Media::Hls {
                url,
                allow_seeking: *allow_seeking,
            }),
            _ => None,
        }
    }
}

/// DynTube embed keys (`channelKey`): 6-64 URL-safe characters.
pub fn is_embed_key(key: &str) -> bool {
    (6..=64).contains(&key.len())
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// YouTube video ids are 11 URL-safe characters.
pub fn is_youtube_id(id: &str) -> bool {
    id.len() == 11
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// A signed file URL is accepted only on an allowed origin (same scheme, host
/// and port) and without credentials in it.
pub fn checked_file_url(raw: &str, media_origins: &[Url]) -> Option<String> {
    if raw.len() > 4096 {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    if !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
        return None;
    }
    let origin = url.origin();
    media_origins
        .iter()
        .any(|allowed| allowed.origin() == origin)
        .then(|| url.to_string())
}

/// Hosts that serve managed-streaming master playlists (ADR-026, verified live
/// 2026-10-10). Variant playlists and segments come from `*.dyntube.net`; the
/// WebView's CSP allows only these hosts for media requests.
pub const HLS_PLAYLIST_HOSTS: &[&str] = &["api.dyntube.com"];

/// A DynTube tokenized master playlist: HTTPS on an allowed host, default
/// port, `/v1/live/videos/tokens/<key>.m3u8`, no credentials or fragment.
pub fn checked_hls_url(raw: &str) -> Option<String> {
    if raw.len() > 4096 {
        return None;
    }
    let url = Url::parse(raw).ok()?;
    let host_ok = url
        .host_str()
        .is_some_and(|host| HLS_PLAYLIST_HOSTS.contains(&host));
    let key = url
        .path()
        .strip_prefix("/v1/live/videos/tokens/")
        .and_then(|rest| rest.strip_suffix(".m3u8"));
    let key_ok = key.is_some_and(|key| {
        (6..=64).contains(&key.len())
            && key
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    });
    (url.scheme() == "https"
        && host_ok
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
        && key_ok)
        .then(|| url.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::parse_origins;

    fn origins() -> Vec<Url> {
        parse_origins(
            "https://media.example.com, https://cdn.example.com:8443",
            false,
        )
        .unwrap()
    }

    fn descriptor(provider: &str, video_id: Option<&str>, signed_url: Option<&str>) -> Descriptor {
        serde_json::from_value(serde_json::json!({
            "provider": provider,
            "providerVideoId": video_id,
            "signedUrl": signed_url,
            "allowSeeking": false
        }))
        .unwrap()
    }

    fn hls_descriptor(signed_url: &str, media_format: Option<&str>) -> Descriptor {
        serde_json::from_value(serde_json::json!({
            "provider": "DYNTUBE",
            "signedUrl": signed_url,
            "mediaFormat": media_format,
            "allowSeeking": true
        }))
        .unwrap()
    }

    const PLAYLIST: &str = "https://api.dyntube.com/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8?token=abc_-&expires=1791574980321";

    #[test]
    fn dyntube_plays_only_tokenized_playlists_on_the_allowed_host() {
        assert_eq!(
            Media::from_descriptor(&hls_descriptor(PLAYLIST, Some("HLS")), &origins()),
            Media::Hls {
                url: PLAYLIST.into(),
                allow_seeking: true
            }
        );
        for bad in [
            "http://api.dyntube.com/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8?token=a",
            "https://api.dyntube.com:8443/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8",
            "https://api.dyntube.com.evil.test/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8",
            "https://evil.test/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8",
            "https://user:pw@api.dyntube.com/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8",
            "https://api.dyntube.com/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8#x",
            "https://api.dyntube.com/v1/live/videos/zRKooyBhYkuKtrUrFhkVDw.m3u8",
            "https://api.dyntube.com/v1/live/videos/tokens/../../projects.m3u8",
            "https://api.dyntube.com/v1/live/videos/tokens/abc.m3u8",
            "https://api.dyntube.com/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.mp4",
            "https://media.example.com/v.m3u8",
            "javascript:alert(1)",
        ] {
            assert_eq!(checked_hls_url(bad), None, "{bad}");
        }
    }

    #[test]
    fn dyntube_without_the_hls_format_is_refused() {
        for format in [None, Some("FILE"), Some("hls"), Some("DASH")] {
            assert_eq!(
                Media::from_descriptor(&hls_descriptor(PLAYLIST, format), &origins()),
                Media::Unsupported,
                "{format:?}"
            );
        }
        // A DynTube playlist is never accepted as an INTERNAL file either.
        assert_eq!(
            Media::from_descriptor(&descriptor("INTERNAL", None, Some(PLAYLIST)), &origins()),
            Media::Unsupported
        );
    }

    #[test]
    fn a_fresh_playlist_keeps_the_seeking_rule_and_the_host_check() {
        let media = Media::Hls {
            url: PLAYLIST.into(),
            allow_seeking: false,
        };
        let fresh = "https://api.dyntube.com/v1/live/videos/tokens/zRKooyBhYkuKtrUrFhkVDw.m3u8?token=new&expires=2";
        assert_eq!(
            media.with_fresh_url(fresh, &origins()),
            Some(Media::Hls {
                url: fresh.into(),
                allow_seeking: false
            })
        );
        assert_eq!(
            media.with_fresh_url("https://media.example.com/bucket/v.mp4", &origins()),
            None
        );
    }

    #[test]
    fn youtube_needs_a_real_looking_id() {
        let media = Media::from_descriptor(
            &descriptor("YOUTUBE", Some("cqjzumb0x84"), None),
            &origins(),
        );
        assert_eq!(
            media,
            Media::Youtube {
                video_id: "cqjzumb0x84".into(),
                allow_seeking: false
            }
        );
        for bad in [
            "",
            "short",
            "cqjzumb0x84x",
            "cqjzumb0x8/",
            "cqjzumb0x8\"",
            "../../aaaaa",
        ] {
            let media = Media::from_descriptor(&descriptor("YOUTUBE", Some(bad), None), &origins());
            assert_eq!(media, Media::Unsupported, "{bad}");
        }
        assert_eq!(
            Media::from_descriptor(&descriptor("YOUTUBE", None, None), &origins()),
            Media::Unsupported
        );
    }

    #[test]
    fn files_only_from_the_compiled_media_origins() {
        let ok = "https://media.example.com/bucket/v.mp4?X-Amz-Signature=abc";
        assert_eq!(
            Media::from_descriptor(&descriptor("INTERNAL", None, Some(ok)), &origins()),
            Media::File {
                url: ok.into(),
                allow_seeking: false
            }
        );
        assert!(checked_file_url("https://cdn.example.com:8443/v.mp4", &origins()).is_some());
        for bad in [
            "http://media.example.com/v.mp4",
            "https://media.example.com:444/v.mp4",
            "https://cdn.example.com/v.mp4",
            "https://media.example.com.evil.test/v.mp4",
            "https://user:pw@media.example.com/v.mp4",
            "https://media.example.com/v.mp4#x",
            "javascript:alert(1)",
            "file:///C:/Windows/win.ini",
            "not a url",
        ] {
            assert_eq!(checked_file_url(bad, &origins()), None, "{bad}");
        }
        assert_eq!(
            Media::from_descriptor(&descriptor("INTERNAL", None, None), &origins()),
            Media::Unsupported
        );
    }

    #[test]
    fn unknown_providers_are_refused() {
        for provider in ["YOUTUBE_CUSTOM", "BUNNY", ""] {
            let media = Media::from_descriptor(
                &descriptor(provider, Some("cqjzumb0x84"), None),
                &origins(),
            );
            assert_eq!(media, Media::Unsupported, "{provider}");
        }
    }

    #[test]
    fn a_fresh_url_keeps_the_seeking_rule() {
        let media = Media::File {
            url: "https://media.example.com/a".into(),
            allow_seeking: true,
        };
        assert_eq!(
            media.with_fresh_url("https://media.example.com/b", &origins()),
            Some(Media::File {
                url: "https://media.example.com/b".into(),
                allow_seeking: true
            })
        );
        assert_eq!(
            media.with_fresh_url("https://evil.test/b", &origins()),
            None
        );
        let youtube = Media::Youtube {
            video_id: "cqjzumb0x84".into(),
            allow_seeking: true,
        };
        assert_eq!(
            youtube.with_fresh_url("https://media.example.com/b", &origins()),
            None
        );
    }

    #[test]
    fn media_serializes_for_the_ui() {
        assert_eq!(
            serde_json::to_value(Media::File {
                url: "u".into(),
                allow_seeking: true
            })
            .unwrap(),
            serde_json::json!({ "engine": "file", "url": "u", "allowSeeking": true })
        );
        assert_eq!(
            serde_json::to_value(Media::Youtube {
                video_id: "cqjzumb0x84".into(),
                allow_seeking: false
            })
            .unwrap(),
            serde_json::json!({ "engine": "youtube", "videoId": "cqjzumb0x84", "allowSeeking": false })
        );
        assert_eq!(
            serde_json::to_value(Media::Unsupported).unwrap(),
            serde_json::json!({ "engine": "unsupported" })
        );
        assert_eq!(
            serde_json::to_value(Media::Hls {
                url: "u".into(),
                allow_seeking: false
            })
            .unwrap(),
            serde_json::json!({ "engine": "hls", "url": "u", "allowSeeking": false })
        );
    }

    #[test]
    fn origin_lists_parse_strictly() {
        assert_eq!(
            parse_origins("https://a.example.com,https://b.example.com", false)
                .unwrap()
                .len(),
            2
        );
        assert!(parse_origins("https://a.example.com,http://b.example.com", false).is_err());
        assert!(parse_origins("http://localhost:9000", true).is_ok());
        assert!(parse_origins("http://localhost:9000", false).is_err());
    }

    fn embed_descriptor(embed: serde_json::Value, media_format: &str) -> Descriptor {
        serde_json::from_value(serde_json::json!({
            "provider": "DYNTUBE",
            "signedUrl": null,
            "mediaFormat": media_format,
            "allowSeeking": false,
            "embed": embed
        }))
        .unwrap()
    }

    const BRIDGE: &str = "https://embed.example.com/embed/dyntube-bridge.html";

    #[test]
    fn dyntube_embed_frames_only_the_compiled_in_bridge_with_a_valid_key() {
        let bridge = Url::parse(BRIDGE).unwrap();
        let descriptor = embed_descriptor(
            serde_json::json!({ "provider": "DYNTUBE", "key": "ch3x_Kx-91aZ", "bridgeUrl": "https://evil.test/x.html" }),
            "EMBED",
        );
        assert_eq!(
            Media::from_descriptor_with_bridge(&descriptor, &origins(), Some(&bridge)),
            Media::Embed {
                key: "ch3x_Kx-91aZ".into(),
                bridge_url: BRIDGE.into(),
                allow_seeking: false
            }
        );
        // No compiled-in bridge: nothing plays.
        assert_eq!(
            Media::from_descriptor_with_bridge(&descriptor, &origins(), None),
            Media::Unsupported
        );
    }

    #[test]
    fn dyntube_embed_refuses_bad_keys_other_providers_and_a_missing_format() {
        let bridge = Url::parse(BRIDGE).unwrap();
        for key in ["short", "has space here", "../../etc", "a\"b<script>", &"x".repeat(65)] {
            let descriptor =
                embed_descriptor(serde_json::json!({ "provider": "DYNTUBE", "key": key }), "EMBED");
            assert_eq!(
                Media::from_descriptor_with_bridge(&descriptor, &origins(), Some(&bridge)),
                Media::Unsupported,
                "{key}"
            );
        }
        let other = embed_descriptor(serde_json::json!({ "provider": "BUNNY", "key": "ch3x_Kx-91aZ" }), "EMBED");
        assert_eq!(
            Media::from_descriptor_with_bridge(&other, &origins(), Some(&bridge)),
            Media::Unsupported
        );
        let unformatted =
            embed_descriptor(serde_json::json!({ "provider": "DYNTUBE", "key": "ch3x_Kx-91aZ" }), "FILE");
        assert_eq!(
            Media::from_descriptor_with_bridge(&unformatted, &origins(), Some(&bridge)),
            Media::Unsupported
        );
        let missing = embed_descriptor(serde_json::Value::Null, "EMBED");
        assert_eq!(
            Media::from_descriptor_with_bridge(&missing, &origins(), Some(&bridge)),
            Media::Unsupported
        );
    }

    #[test]
    fn the_bridge_url_must_be_a_clean_html_page() {
        assert!(config::parse_bridge_url(BRIDGE, false).is_ok());
        assert!(config::parse_bridge_url("http://embed.lms.test:3000/embed/dyntube-bridge.html", true).is_ok());
        for bad in [
            "http://embed.lms.test:3000/embed/dyntube-bridge.html",
            "https://embed.example.com/embed/dyntube-bridge.html?x=1",
            "https://embed.example.com/embed/dyntube-bridge.html#x",
            "https://u:p@embed.example.com/embed/dyntube-bridge.html",
            "https://embed.example.com/embed/",
            "javascript:alert(1)",
        ] {
            assert!(config::parse_bridge_url(bad, false).is_err(), "{bad}");
        }
    }
}

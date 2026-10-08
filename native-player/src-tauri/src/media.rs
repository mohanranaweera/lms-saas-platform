//! Which playback engine the UI runs, decided and validated here in Rust
//! (Phase 10). The UI never sees a media reference this module did not
//! accept: a YouTube id must look like one, and a file URL must be on one of
//! the media origins compiled into the build.

use serde::Serialize;
use url::Url;

use crate::api::Descriptor;

/// What the UI should play, tagged by engine.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(
    tag = "engine",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum Media {
    /// The official YouTube embed (documented parameters only, nothing drawn over it).
    Youtube { video_id: String },
    /// A platform-hosted file behind a short-lived signed URL.
    File { url: String, allow_seeking: bool },
    /// Anything this player cannot play; the UI shows a refusal.
    Unsupported,
}

impl Media {
    pub fn from_descriptor(descriptor: &Descriptor, media_origins: &[Url]) -> Self {
        match descriptor.provider.as_str() {
            "YOUTUBE" => descriptor
                .provider_video_id
                .as_deref()
                .filter(|id| is_youtube_id(id))
                .map_or(Media::Unsupported, |id| Media::Youtube {
                    video_id: id.to_owned(),
                }),
            "INTERNAL" => descriptor
                .signed_url
                .as_deref()
                .and_then(|url| checked_file_url(url, media_origins))
                .map_or(Media::Unsupported, |url| Media::File {
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
            _ => None,
        }
    }
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

/// Links from the YouTube embed ("Watch on YouTube", the channel name) may be
/// opened in the system browser; nothing else may open from the player.
pub fn is_youtube_page(url: &Url) -> bool {
    url.scheme() == "https"
        && matches!(
            url.host_str(),
            Some("www.youtube.com" | "youtube.com" | "m.youtube.com" | "youtu.be")
        )
        && url.username().is_empty()
        && url.port().is_none()
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

    #[test]
    fn youtube_needs_a_real_looking_id() {
        let media = Media::from_descriptor(
            &descriptor("YOUTUBE", Some("cqjzumb0x84"), None),
            &origins(),
        );
        assert_eq!(
            media,
            Media::Youtube {
                video_id: "cqjzumb0x84".into()
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
        };
        assert_eq!(
            youtube.with_fresh_url("https://media.example.com/b", &origins()),
            None
        );
    }

    #[test]
    fn only_youtube_pages_open_outside() {
        let allowed = |raw: &str| is_youtube_page(&Url::parse(raw).unwrap());
        assert!(allowed("https://www.youtube.com/watch?v=cqjzumb0x84"));
        assert!(allowed("https://youtu.be/cqjzumb0x84"));
        assert!(!allowed("http://www.youtube.com/watch?v=x"));
        assert!(!allowed("https://www.youtube.com.evil.test/"));
        assert!(!allowed("https://evil.test/?u=https://www.youtube.com"));
        assert!(!allowed("https://www.youtube.com:8443/"));
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
                video_id: "cqjzumb0x84".into()
            })
            .unwrap(),
            serde_json::json!({ "engine": "youtube", "videoId": "cqjzumb0x84" })
        );
        assert_eq!(
            serde_json::to_value(Media::Unsupported).unwrap(),
            serde_json::json!({ "engine": "unsupported" })
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
}

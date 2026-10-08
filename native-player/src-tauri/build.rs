fn main() {
    // The API origin is compiled into the binary (ADR-025 §1): a release build
    // without one, or with a non-HTTPS one, must not exist.
    // Likewise the storage/CDN origins platform-hosted videos may come from.
    println!("cargo:rerun-if-env-changed=AOC_API_ORIGIN");
    println!("cargo:rerun-if-env-changed=AOC_MEDIA_ORIGINS");
    if std::env::var("PROFILE").as_deref() == Ok("release") {
        let origin = std::env::var("AOC_API_ORIGIN").unwrap_or_default();
        if !origin.trim().starts_with("https://") {
            panic!("AOC_API_ORIGIN must be set to an https:// origin for release builds");
        }
        let media = std::env::var("AOC_MEDIA_ORIGINS").unwrap_or_default();
        let origins: Vec<&str> = media
            .split(',')
            .map(str::trim)
            .filter(|o| !o.is_empty())
            .collect();
        if origins.is_empty() || origins.iter().any(|o| !o.starts_with("https://")) {
            panic!(
                "AOC_MEDIA_ORIGINS must list https:// origins (comma-separated) for release builds"
            );
        }
    }

    // Only these app commands exist for the UI, and capabilities/default.json
    // must grant each one explicitly.
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .app_manifest(tauri_build::AppManifest::new().commands(&["get_state"])),
    )
    .expect("failed to run tauri-build");
}

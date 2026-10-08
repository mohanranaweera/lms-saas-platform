fn main() {
    // The API origin is compiled into the binary (ADR-025 §1): a release build
    // without one, or with a non-HTTPS one, must not exist.
    println!("cargo:rerun-if-env-changed=AOC_API_ORIGIN");
    if std::env::var("PROFILE").as_deref() == Ok("release") {
        let origin = std::env::var("AOC_API_ORIGIN").unwrap_or_default();
        if !origin.trim().starts_with("https://") {
            panic!("AOC_API_ORIGIN must be set to an https:// origin for release builds");
        }
    }

    // Only these app commands exist for the UI, and capabilities/default.json
    // must grant each one explicitly.
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .app_manifest(tauri_build::AppManifest::new().commands(&["get_state", "end_session"])),
    )
    .expect("failed to run tauri-build");
}

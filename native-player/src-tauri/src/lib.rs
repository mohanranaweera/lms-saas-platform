//! AOC Secure Player (ADR-025): Tauri shell around the player core.

pub mod api;
pub mod config;
pub mod deep_link;
pub mod installation;
pub mod keystore;
pub mod player;
pub mod signing;

use std::sync::Arc;

use tauri::{
    AppHandle, Emitter, Manager, RunEvent, State, WebviewUrl, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_deep_link::DeepLinkExt;

use crate::api::ApiClient;
use crate::installation::InstallationStore;
use crate::player::{Player, View, ViewSink};

const MAIN_WINDOW: &str = "main";
const VIEW_EVENT: &str = "player://view";

/// Pushes every view change to the UI.
struct WindowSink(AppHandle);

impl ViewSink for WindowSink {
    fn show(&self, view: &View) {
        let _ = self.0.emit_to(MAIN_WINDOW, VIEW_EVENT, view);
    }
}

#[tauri::command]
fn get_state(player: State<'_, Arc<Player>>) -> View {
    player.view()
}

#[tauri::command]
async fn end_session(player: State<'_, Arc<Player>>) -> Result<(), ()> {
    player.end_by_student().await;
    Ok(())
}

pub fn run() {
    let mut builder = tauri::Builder::default();
    // Must be the first plugin: a second launch (e.g. a new aocplayer:// link)
    // hands its link to this instance and exits.
    #[cfg(desktop)]
    {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window(MAIN_WINDOW) {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }));
    }
    builder = builder.plugin(tauri_plugin_deep_link::init());
    #[cfg(feature = "release")]
    {
        builder = builder.plugin(tauri_plugin_updater::Builder::new().build());
    }

    let app = builder
        .invoke_handler(tauri::generate_handler![get_state, end_session])
        .setup(|app| {
            let origin = config::api_origin()?;
            let api = ApiClient::new(origin)?;
            let store = InstallationStore::new(app.path().app_local_data_dir()?);
            let player = Player::new(
                api,
                keystore::platform_store(),
                store,
                Arc::new(WindowSink(app.handle().clone())),
            );
            app.manage(Arc::clone(&player));

            WebviewWindowBuilder::new(app, MAIN_WINDOW, WebviewUrl::App("index.html".into()))
                .title("AOC Player")
                .inner_size(960.0, 600.0)
                .min_inner_size(640.0, 420.0)
                .on_navigation(is_bundled_ui)
                .build()?;

            // Installed builds get the scheme from the installer; a dev build
            // registers it for the current user so links work during development.
            #[cfg(debug_assertions)]
            app.deep_link().register_all()?;

            let on_link = Arc::clone(&player);
            app.deep_link().on_open_url(move |event| {
                for url in event.urls() {
                    handle(&on_link, url.as_str());
                }
            });
            // Started by a link (first instance).
            if let Some(urls) = app.deep_link().get_current()? {
                for url in urls {
                    handle(&player, url.as_str());
                }
            }

            #[cfg(feature = "release")]
            tauri::async_runtime::spawn(update_when_idle(
                app.handle().clone(),
                Arc::clone(&player),
            ));
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to start the AOC Player");

    app.run(|app, event| {
        // Closing the window ends the current session first (bounded wait).
        if let RunEvent::WindowEvent {
            label,
            event: WindowEvent::CloseRequested { api, .. },
            ..
        } = &event
        {
            let player = app.state::<Arc<Player>>().inner().clone();
            if label == MAIN_WINDOW && player.has_session() {
                api.prevent_close();
                let app = app.clone();
                tauri::async_runtime::spawn(async move {
                    player.shutdown().await;
                    app.exit(0);
                });
            }
        }
    });
}

fn handle(player: &Arc<Player>, link: &str) {
    let player = Arc::clone(player);
    let link = link.to_owned();
    tauri::async_runtime::spawn(async move { player.handle_link(&link).await });
}

/// The window may only show the bundled UI (ADR-025 §1 navigation allow-list).
fn is_bundled_ui(url: &url::Url) -> bool {
    let bundled = match url.scheme() {
        "tauri" => url.host_str() == Some("localhost"),
        "http" | "https" => url.host_str() == Some("tauri.localhost"),
        _ => false,
    };
    let dev_server = cfg!(debug_assertions)
        && url.scheme() == "http"
        && url.host_str() == Some("127.0.0.1")
        && url.port() == Some(1430);
    bundled || dev_server
}

/// Installs a signed update when one exists and nothing is playing. The
/// updater refuses unsigned or older releases (ADR-025 §7).
#[cfg(feature = "release")]
async fn update_when_idle(app: AppHandle, player: Arc<Player>) {
    use tauri_plugin_updater::UpdaterExt;
    let Ok(updater) = app.updater() else { return };
    let Ok(Some(update)) = updater.check().await else {
        return;
    };
    if player.has_session() {
        return;
    }
    if update.download_and_install(|_, _| {}, || {}).await.is_ok() {
        app.restart();
    }
}

#[cfg(test)]
mod tests {
    use super::is_bundled_ui;

    #[test]
    fn navigation_is_limited_to_the_bundled_ui() {
        let allowed = |raw: &str| is_bundled_ui(&url::Url::parse(raw).unwrap());
        assert!(allowed("http://tauri.localhost/index.html"));
        assert!(allowed("tauri://localhost/index.html"));
        assert!(!allowed("https://evil.example.com/"));
        assert!(!allowed("https://tauri.localhost.evil.example.com/"));
        assert!(!allowed("http://localhost:8080/"));
        assert!(!allowed("file:///C:/Windows/"));
        assert!(!allowed("aocplayer://play?code=x"));
    }
}

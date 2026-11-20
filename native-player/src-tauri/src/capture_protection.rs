//! Windows capture-affinity deterrence for the native player.
//!
//! This is deliberately a small, Rust-owned capability. The WebView cannot
//! request arbitrary window handles or change the setting. Windows 10 version
//! 2004 and later supports `WDA_EXCLUDEFROMCAPTURE`; other platforms report
//! `Unsupported` so required protection fails closed.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CaptureStatus {
    #[default]
    NotRequested,
    Applied,
    Unsupported,
    Failed,
}

impl CaptureStatus {
    pub fn is_applied(self) -> bool {
        self == Self::Applied
    }
}

#[cfg(windows)]
pub fn apply_to_window<R: tauri::Runtime>(
    window: &tauri::WebviewWindow<R>,
    enabled: bool,
) -> CaptureStatus {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{
        SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE,
    };

    if !enabled {
        return CaptureStatus::NotRequested;
    }
    let Ok(handle) = window.window_handle() else {
        return CaptureStatus::Failed;
    };
    let RawWindowHandle::Win32(raw) = handle.as_raw() else {
        return CaptureStatus::Unsupported;
    };
    let hwnd = HWND(raw.hwnd.get() as *mut core::ffi::c_void);
    // The API returns an error on unsupported Windows versions or when the
    // handle is no longer a top-level window; neither case may fail open.
    if unsafe { SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE) }.is_ok() {
        CaptureStatus::Applied
    } else {
        CaptureStatus::Failed
    }
}

#[cfg(not(windows))]
pub fn apply_to_window<R: tauri::Runtime>(
    _window: &tauri::WebviewWindow<R>,
    enabled: bool,
) -> CaptureStatus {
    if enabled {
        CaptureStatus::Unsupported
    } else {
        CaptureStatus::NotRequested
    }
}

#[cfg(windows)]
pub fn clear_from_window<R: tauri::Runtime>(window: &tauri::WebviewWindow<R>) -> CaptureStatus {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::UI::WindowsAndMessaging::{SetWindowDisplayAffinity, WDA_NONE};

    let Ok(handle) = window.window_handle() else {
        return CaptureStatus::Failed;
    };
    let RawWindowHandle::Win32(raw) = handle.as_raw() else {
        return CaptureStatus::Unsupported;
    };
    let hwnd = HWND(raw.hwnd.get() as *mut core::ffi::c_void);
    if unsafe { SetWindowDisplayAffinity(hwnd, WDA_NONE) }.is_ok() {
        CaptureStatus::NotRequested
    } else {
        CaptureStatus::Failed
    }
}

#[cfg(not(windows))]
pub fn clear_from_window<R: tauri::Runtime>(_window: &tauri::WebviewWindow<R>) -> CaptureStatus {
    CaptureStatus::NotRequested
}

pub trait Controller: Send + Sync + 'static {
    fn apply(&self, enabled: bool) -> CaptureStatus;
    fn clear(&self);
}

pub struct WindowController<R: tauri::Runtime = tauri::Wry> {
    window: tauri::WebviewWindow<R>,
}

impl<R: tauri::Runtime> WindowController<R> {
    pub fn new(window: tauri::WebviewWindow<R>) -> Self {
        Self { window }
    }
}

impl<R: tauri::Runtime> Controller for WindowController<R> {
    fn apply(&self, enabled: bool) -> CaptureStatus {
        apply_to_window(&self.window, enabled)
    }

    fn clear(&self) {
        let _ = clear_from_window(&self.window);
    }
}

#[derive(Default)]
pub struct UnsupportedController;

impl Controller for UnsupportedController {
    fn apply(&self, enabled: bool) -> CaptureStatus {
        if enabled {
            CaptureStatus::Unsupported
        } else {
            CaptureStatus::NotRequested
        }
    }

    fn clear(&self) {}
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_applied_counts_as_protected() {
        assert!(CaptureStatus::Applied.is_applied());
        assert!(!CaptureStatus::Unsupported.is_applied());
        assert!(!CaptureStatus::Failed.is_applied());
        assert!(!CaptureStatus::NotRequested.is_applied());
    }
}

/**
 * Light/dark mode (LOGIN-01 Phase A). The mode is a per-browser preference:
 * `localStorage["lms-theme"]` = "light" | "dark", or absent = follow the OS.
 * It is applied as the `dark` class on `<html>` (Tailwind's `dark` variant and
 * the `.dark` tokens in `globals.css`) by {@link THEME_INIT_SCRIPT} before
 * first paint, so there is no flash of the wrong theme. Nothing here is sent
 * to the backend.
 */

export type ThemeMode = "light" | "dark";

export const THEME_STORAGE_KEY = "lms-theme";

const THEME_CHANGE_EVENT = "lms-theme-change";

/**
 * Inline `<head>` script (static string, no user data): applies the stored
 * mode, else the OS preference. Wrapped in try/catch because storage can be
 * unavailable (private mode, blocked site data).
 */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem("${THEME_STORAGE_KEY}");var d=t==="dark"||(t!=="light"&&window.matchMedia("(prefers-color-scheme: dark)").matches);var e=document.documentElement;e.classList.toggle("dark",d);e.style.colorScheme=d?"dark":"light"}catch(_){}})()`;

function applyMode(mode: ThemeMode): void {
  const root = document.documentElement;
  root.classList.toggle("dark", mode === "dark");
  root.style.colorScheme = mode;
}

/** The mode currently applied to the document (client only). */
export function currentThemeMode(): ThemeMode {
  return document.documentElement.classList.contains("dark") ? "dark" : "light";
}

/** Applies and remembers an explicit choice; notifies every subscribed toggle. */
export function setThemeMode(mode: ThemeMode): void {
  applyMode(mode);
  try {
    localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    // Storage unavailable: the choice still applies for this page view.
  }
  window.dispatchEvent(new Event(THEME_CHANGE_EVENT));
}

/**
 * `useSyncExternalStore` subscription: an explicit toggle, another tab
 * changing the stored mode, or the OS preference changing while the user has
 * made no explicit choice.
 */
export function subscribeThemeMode(onChange: () => void): () => void {
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const onStorage = (event: StorageEvent) => {
    if (event.key !== THEME_STORAGE_KEY) return;
    applyMode(event.newValue === "dark" ? "dark" : event.newValue === "light" ? "light" : media.matches ? "dark" : "light");
    onChange();
  };
  const onSystem = () => {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(THEME_STORAGE_KEY);
    } catch {
      stored = null;
    }
    if (stored !== "light" && stored !== "dark") {
      applyMode(media.matches ? "dark" : "light");
      onChange();
    }
  };
  window.addEventListener(THEME_CHANGE_EVENT, onChange);
  window.addEventListener("storage", onStorage);
  media.addEventListener("change", onSystem);
  return () => {
    window.removeEventListener(THEME_CHANGE_EVENT, onChange);
    window.removeEventListener("storage", onStorage);
    media.removeEventListener("change", onSystem);
  };
}

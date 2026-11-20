/**
 * Anonymous language preference for this tenant: the host-scoped `lms_locale`
 * cookie (§2 step 3). Written only by an explicit switcher action, never from
 * `?lang=` or background requests. Not HttpOnly on purpose: it holds a public
 * UI preference only, nothing security-relevant.
 */
export const LOCALE_COOKIE = "lms_locale";
const ONE_YEAR_SECONDS = 365 * 24 * 60 * 60;

export function readLocaleCookie(): string | null {
  if (typeof document === "undefined") return null;
  for (const part of document.cookie.split(";")) {
    const [name, ...rest] = part.trim().split("=");
    if (name === LOCALE_COOKIE) {
      try {
        return decodeURIComponent(rest.join("="));
      } catch {
        return null;
      }
    }
  }
  return null;
}

export function writeLocaleCookie(locale: string): void {
  if (typeof document === "undefined") return;
  const secure = window.location.protocol === "https:" ? "; Secure" : "";
  document.cookie = `${LOCALE_COOKIE}=${encodeURIComponent(locale)}; Path=/; Max-Age=${ONE_YEAR_SECONDS}; SameSite=Lax${secure}`;
}

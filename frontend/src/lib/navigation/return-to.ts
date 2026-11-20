/**
 * STORE-01 / ADR-037 decision 7: a `returnTo` value is honored only when it is
 * a same-host relative path. Anything else (absolute URL, scheme, protocol-
 * relative `//host`, `/\host`, control characters) is ignored and the caller
 * falls back to its normal destination. UX only - it never grants access; the
 * destination page's backend calls are authorized independently.
 */
export const RETURN_TO_PARAM = "returnTo";

const MAX_RETURN_TO_LENGTH = 2048;
/** Auth pages never make sense as a post-sign-in destination (avoids loops). */
const AUTH_PATHS = ["/login", "/register"];

/** Returns the path when it is a safe same-host relative path, otherwise `null`. */
export function safeReturnTo(value: string | null | undefined): string | null {
  if (typeof value !== "string") return null;
  if (value.length === 0 || value.length > MAX_RETURN_TO_LENGTH) return null;
  // Exactly one leading slash: rejects "//host" and "/\host" (browsers treat "\" as "/").
  if (value[0] !== "/" || value[1] === "/" || value[1] === "\\") return null;
  // No backslashes, whitespace or control characters anywhere: the URL parser
  // strips tabs/newlines, so "/\t/evil.test" would otherwise become "//evil.test".
  if (/[\\\s\u0000-\u001f\u007f]/.test(value)) return null;
  // Final check with the platform URL parser: the path must stay on our origin.
  const base = "http://return-to.invalid";
  let parsed: URL;
  try {
    parsed = new URL(value, base);
  } catch {
    return null;
  }
  if (parsed.origin !== base) return null;
  if (AUTH_PATHS.some((path) => parsed.pathname === path || parsed.pathname.startsWith(`${path}/`))) return null;
  return value;
}

/** `/login` (or `/register`) carrying a validated `returnTo`; the plain path when there is none. */
export function withReturnTo(path: string, returnTo: string | null | undefined): string {
  const safe = safeReturnTo(returnTo);
  return safe ? `${path}?${RETURN_TO_PARAM}=${encodeURIComponent(safe)}` : path;
}

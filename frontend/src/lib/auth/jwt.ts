/**
 * Decodes a JWT payload for DISPLAY/ROUTING CONVENIENCE ONLY.
 *
 * This performs no signature verification and must never be treated as proof of
 * anything — the backend (`JwtAuthenticationFilter`) is the sole authority on
 * whether a token is valid, unexpired, or belongs to a live, non-revoked session.
 * The only reason the frontend reads this at all is to pick which dashboard route
 * to redirect to right after a successful login response, using the `role` claim
 * as a UX shortcut. No permission/authorization decision may ever be based on
 * this decoded value.
 */
export interface DecodedAccessToken {
  /** Subject — the authenticated user id. */
  sub?: string;
  tenant_id?: string;
  role?: string;
  session_id?: string;
  exp?: number;
  iat?: number;
}

export function decodeAccessTokenPayload(token: string): DecodedAccessToken | null {
  try {
    const segments = token.split(".");
    if (segments.length !== 3) {
      return null;
    }
    const payloadSegment = segments[1];
    const base64 = payloadSegment.replace(/-/g, "+").replace(/_/g, "/");
    const padding = (4 - (base64.length % 4)) % 4;
    const padded = base64 + "=".repeat(padding);
    const json = atob(padded);
    return JSON.parse(json) as DecodedAccessToken;
  } catch {
    return null;
  }
}

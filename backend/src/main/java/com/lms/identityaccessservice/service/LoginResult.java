package com.lms.identityaccessservice.service;

import java.time.Duration;
import java.util.UUID;

/**
 * Service-layer result of a successful login - carries the raw refresh token
 * only so the controller can set it as an {@code HttpOnly} cookie; it is
 * never put in a JSON response body. {@code expiresIn} is the access
 * token's lifetime (used for the JSON body's {@code expiresInSeconds});
 * {@code refreshCookieMaxAge} is the refresh token's own remaining lifetime
 * (the session's absolute expiry, not the access token's) and must be used
 * for the cookie's {@code Max-Age} instead - the two are deliberately kept
 * distinct so a caller cannot accidentally expire the refresh cookie after
 * only 15 minutes.
 */
public record LoginResult(String accessToken, Duration expiresIn, UUID sessionId, String rawRefreshToken,
		Duration refreshCookieMaxAge, boolean mustChangePassword) {

}

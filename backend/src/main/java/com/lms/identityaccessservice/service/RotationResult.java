package com.lms.identityaccessservice.service;

import java.time.Duration;

/**
 * Result of a successful refresh-token rotation - same raw-token-never-in
 * -JSON rule and same {@code expiresIn} vs {@code refreshCookieMaxAge}
 * distinction as {@link LoginResult}.
 */
public record RotationResult(String accessToken, Duration expiresIn, String rawRefreshToken,
		Duration refreshCookieMaxAge) {

}

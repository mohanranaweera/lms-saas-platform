package com.lms.identityaccessservice.web.dto;

import java.util.UUID;

/**
 * The refresh token is never included here - it is delivered only via an
 * {@code HttpOnly}/{@code Secure}/{@code SameSite=Strict} cookie (plan
 * §10/§21). {@code mustChangePassword} is surfaced per the plan §4
 * recommendation to ship the column in the response now even though the
 * forced-change redirect UX itself is deferred to a follow-up story.
 */
public record LoginResponse(String accessToken, long expiresInSeconds, UUID sessionId, boolean mustChangePassword) {

}

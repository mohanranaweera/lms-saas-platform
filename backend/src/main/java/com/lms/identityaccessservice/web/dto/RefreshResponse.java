package com.lms.identityaccessservice.web.dto;

/** Same no-refresh-token-in-body rule as {@link LoginResponse}. */
public record RefreshResponse(String accessToken, long expiresInSeconds) {

}

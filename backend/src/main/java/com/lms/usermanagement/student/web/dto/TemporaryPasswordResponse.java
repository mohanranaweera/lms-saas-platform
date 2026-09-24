package com.lms.usermanagement.student.web.dto;

/**
 * Response body for {@code POST /api/v1/students/{id}/reset-password} (Wave
 * 3). Carries the one-time temporary password exactly once - never persisted
 * or logged beyond this single response, per {@code .claude/rules/security.md}.
 */
public record TemporaryPasswordResponse(String temporaryPassword) {

}

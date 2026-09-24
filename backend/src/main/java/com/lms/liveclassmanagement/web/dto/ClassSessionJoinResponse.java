package com.lms.liveclassmanagement.web.dto;

import java.time.Instant;

/**
 * A freshly-minted, short-lived, caller-scoped join URL - never a
 * stable/reusable link (see {@code LiveClassProviderApi#getJoinUrl}'s
 * javadoc). The frontend opens {@code joinUrl} in a new tab; it is never
 * embedded/proxied server-side (out of scope per {@code
 * .claude/rules/architecture.md}'s no-self-hosted-conferencing guidance).
 */
public record ClassSessionJoinResponse(String joinUrl, Instant expiresAt) {

}

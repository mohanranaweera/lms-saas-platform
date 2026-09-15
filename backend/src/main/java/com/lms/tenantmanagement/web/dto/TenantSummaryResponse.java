package com.lms.tenantmanagement.web.dto;

import com.lms.tenantmanagement.api.TenantStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant list-row response DTO (PADASH-1 plan §11's table shape) - never the
 * {@code Tenant} JPA entity itself, per {@code backend/CLAUDE.md}'s "do not
 * expose JPA entities directly" rule.
 */
public record TenantSummaryResponse(UUID id, String name, String subdomain, TenantStatus status,
		String requestedPlan, Instant createdAt) {

}

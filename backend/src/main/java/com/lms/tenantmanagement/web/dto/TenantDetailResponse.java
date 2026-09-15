package com.lms.tenantmanagement.web.dto;

import com.lms.tenantmanagement.api.TenantStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Full tenant profile/contact/plan detail response DTO (PADASH-1 plan §4.1
 * step 3) - never the {@code Tenant} JPA entity itself.
 */
public record TenantDetailResponse(UUID id, String name, String subdomain, TenantStatus status, String requestedPlan,
		String contactName, String contactEmail, String contactPhone, Instant createdAt) {

}

package com.lms.tenantmanagement.web.dto;

/** Response shape for {@code GET /api/v1/tenant-config/domains}. */
public record ConfigDomainSummaryResponse(String domain, boolean hasProperties) {

}

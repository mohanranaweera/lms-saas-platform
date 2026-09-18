package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.ConfigDomain;

/** Service-layer view backing {@code GET /api/v1/tenant-config/domains}. */
public record ConfigDomainSummary(ConfigDomain domain, boolean hasProperties) {

}

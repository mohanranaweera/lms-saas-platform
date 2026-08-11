package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.TenantStatus;
import java.util.UUID;

/** Result of a successful tenant registration. */
public record TenantRegistrationResult(UUID id, String subdomain, TenantStatus status) {

}

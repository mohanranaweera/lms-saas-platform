package com.lms.tenantmanagement.web.dto;

import java.util.UUID;

/** {@code status} is serialized as its lowercase column form (e.g. {@code "pending_approval"}), matching what a frontend status chip expects. */
public record TenantRegistrationResponse(UUID id, String subdomain, String status) {

}

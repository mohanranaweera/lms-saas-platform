package com.lms.tenantmanagement.api;

/**
 * Coarse tenant lifecycle status, mirroring the {@code tenant.status} CHECK
 * constraint in {@code V2__create_tenant.sql}. Lives in {@code api} (not
 * {@code domain}) so other modules (identity-access-service) can reference it
 * via {@link TenantSummary} without depending on the {@code tenant} JPA
 * entity, per this module's package boundary rules.
 */
public enum TenantStatus {

	TRIAL, ACTIVE, SUSPENDED, CANCELLED

}

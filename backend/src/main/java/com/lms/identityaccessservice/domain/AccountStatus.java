package com.lms.identityaccessservice.domain;

/**
 * Shared by {@code tenant_user.status} (V3) and {@code
 * platform_admin_user.status} (V4) - both use the identical
 * {@code CHECK (status IN ('active','suspended'))} vocabulary.
 */
public enum AccountStatus {

	ACTIVE, SUSPENDED

}

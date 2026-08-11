package com.lms.tenantmanagement.api;

/**
 * The lifecycle status of a {@code tenant} row. Lives in {@code api} (rather
 * than {@code domain}) because other domains - most notably the future
 * {@code identity-access-service} - need to reason about a tenant's status
 * (e.g. "is this tenant login-eligible?") without depending on
 * {@code tenant-management}'s JPA entity.
 *
 * <p>Mirrors the CHECK-constrained column values in
 * {@code V2__create_tenant_table.sql} exactly; see {@link #toColumnValue()}
 * and {@link #fromColumnValue(String)} for the mapping, applied at the
 * persistence boundary by {@code com.lms.tenantmanagement.domain.TenantStatusConverter}.
 */
public enum TenantStatus {

	PENDING_APPROVAL("pending_approval"),
	TRIAL("trial"),
	ACTIVE("active"),
	SUSPENDED("suspended"),
	CANCELLED("cancelled"),
	REJECTED("rejected");

	private final String columnValue;

	TenantStatus(String columnValue) {
		this.columnValue = columnValue;
	}

	public String toColumnValue() {
		return columnValue;
	}

	public static TenantStatus fromColumnValue(String columnValue) {
		for (TenantStatus status : values()) {
			if (status.columnValue.equals(columnValue)) {
				return status;
			}
		}
		throw new IllegalArgumentException("Unknown tenant status column value: " + columnValue);
	}

}

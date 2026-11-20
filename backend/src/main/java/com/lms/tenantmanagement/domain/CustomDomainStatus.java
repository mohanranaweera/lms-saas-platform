package com.lms.tenantmanagement.domain;

/**
 * TEN-DOM-01 / ADR-021: lifecycle of a tenant custom domain. Only
 * {@link #ACTIVE} resolves to the tenant.
 *
 * <ul>
 * <li>{@link #PENDING_VERIFICATION} - added, DNS ownership not yet proven.</li>
 * <li>{@link #ACTIVE} - CNAME and TXT records seen; the domain serves the tenant.</li>
 * <li>{@link #DNS_BROKEN} - was ACTIVE, the records are gone; restored when they return.</li>
 * <li>{@link #SUSPENDED} - stopped by a Platform Admin; the tenant cannot re-verify or remove it.</li>
 * <li>{@link #REMOVED} - removed by the tenant; kept as history, the hostname is free again.</li>
 * </ul>
 */
public enum CustomDomainStatus {

	PENDING_VERIFICATION, ACTIVE, DNS_BROKEN, SUSPENDED, REMOVED

}

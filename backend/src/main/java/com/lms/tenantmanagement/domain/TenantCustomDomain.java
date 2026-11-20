package com.lms.tenantmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * TEN-DOM-01 / ADR-021: a hostname a tenant has claimed. Resolves to the
 * tenant only while {@link CustomDomainStatus#ACTIVE}. Never deleted - removal
 * is {@link CustomDomainStatus#REMOVED}. State changes go through the methods
 * below, which mirror V63's CHECK constraints.
 */
@Entity
@Table(name = "tenant_custom_domain")
public class TenantCustomDomain extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "hostname", nullable = false, updatable = false, length = 253)
	private String hostname;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 25)
	private CustomDomainStatus status;

	@Column(name = "verification_code", nullable = false, updatable = false, length = 64)
	private String verificationCode;

	@Column(name = "is_primary", nullable = false)
	private boolean primary;

	@Column(name = "verified_at")
	private Instant verifiedAt;

	@Column(name = "last_checked_at")
	private Instant lastCheckedAt;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "suspended_reason", length = 500)
	private String suspendedReason;

	/** For Hibernate only. */
	protected TenantCustomDomain() {
	}

	private TenantCustomDomain(UUID tenantId, String hostname, String verificationCode) {
		this.tenantId = tenantId;
		this.hostname = hostname;
		this.verificationCode = verificationCode;
		this.status = CustomDomainStatus.PENDING_VERIFICATION;
	}

	public static TenantCustomDomain claim(UUID tenantId, String hostname, String verificationCode) {
		return new TenantCustomDomain(tenantId, hostname, verificationCode);
	}

	/** Records proved: ACTIVE. The first verification time is kept. */
	public void markVerified(Instant now) {
		this.status = CustomDomainStatus.ACTIVE;
		if (this.verifiedAt == null) {
			this.verifiedAt = now;
		}
		this.lastCheckedAt = now;
		this.lastError = null;
	}

	/** A check that did not prove the records. An ACTIVE domain becomes DNS_BROKEN; others keep their status. */
	public void markCheckFailed(String error, Instant now) {
		if (this.status == CustomDomainStatus.ACTIVE) {
			this.status = CustomDomainStatus.DNS_BROKEN;
		}
		this.lastCheckedAt = now;
		this.lastError = truncate(error);
	}

	/** A lookup that could not run (timeout, resolver error): says nothing about the records, so no status change. */
	public void markCheckInconclusive(String error, Instant now) {
		this.lastCheckedAt = now;
		this.lastError = truncate(error);
	}

	public void makePrimary() {
		this.primary = true;
	}

	public void clearPrimary() {
		this.primary = false;
	}

	public void remove() {
		this.status = CustomDomainStatus.REMOVED;
		this.primary = false;
	}

	public void suspend(String reason) {
		this.status = CustomDomainStatus.SUSPENDED;
		this.primary = false;
		this.suspendedReason = truncate(reason);
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getHostname() {
		return hostname;
	}

	public CustomDomainStatus getStatus() {
		return status;
	}

	public String getVerificationCode() {
		return verificationCode;
	}

	public boolean isPrimary() {
		return primary;
	}

	public Instant getVerifiedAt() {
		return verifiedAt;
	}

	public Instant getLastCheckedAt() {
		return lastCheckedAt;
	}

	public String getLastError() {
		return lastError;
	}

	public String getSuspendedReason() {
		return suspendedReason;
	}

	private static String truncate(String value) {
		return (value == null || value.length() <= 500) ? value : value.substring(0, 500);
	}

}

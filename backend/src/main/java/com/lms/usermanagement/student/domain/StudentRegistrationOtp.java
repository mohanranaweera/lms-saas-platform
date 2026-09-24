package com.lms.usermanagement.student.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code student_registration_otp} (V46, Wave 3 - Student
 * registration expansion). Deliberately NOT linked to {@code
 * student_profile}/{@code tenant_user} - no account exists yet at
 * OTP-send time. See V46's header comment for the full rationale (hash-only
 * storage, short TTL, attempt-count rate limiting).
 */
@Entity
@Table(name = "student_registration_otp")
public class StudentRegistrationOtp extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "email", nullable = false, updatable = false)
	private String email;

	@Column(name = "otp_hash", nullable = false, updatable = false)
	private String otpHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected StudentRegistrationOtp() {
	}

	public StudentRegistrationOtp(UUID tenantId, String email, String otpHash, Instant expiresAt) {
		this.tenantId = tenantId;
		this.email = email;
		this.otpHash = otpHash;
		this.expiresAt = expiresAt;
		this.attemptCount = 0;
		this.createdAt = Instant.now();
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getEmail() {
		return email;
	}

	public String getOtpHash() {
		return otpHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getConsumedAt() {
		return consumedAt;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isExpired(Instant now) {
		return now.isAfter(expiresAt);
	}

	public boolean isConsumed() {
		return consumedAt != null;
	}

	/** Increments this row's verify-attempt counter - a wrong-code guess, whether or not it also then expires/exhausts the row. */
	public void recordFailedAttempt() {
		this.attemptCount++;
	}

	/** Marks this row consumed - an already-consumed OTP must never verify a second time. */
	public void consume() {
		this.consumedAt = Instant.now();
	}

}

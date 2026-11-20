package com.lms.paymentmanagement.slip.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * PAY-HUB-01: an internal staff note on a payment slip (Klass "Add Note").
 * Append-only - every column is {@code updatable = false} and V106's trigger
 * rejects UPDATE/DELETE. A note never changes the slip's review state and is
 * never shown to students.
 */
@Entity
@Table(name = "payment_slip_note")
public class PaymentSlipNote extends BaseEntity implements TenantOwned {

	public static final int MAX_BODY_LENGTH = 1000;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "slip_id", nullable = false, updatable = false)
	private UUID slipId;

	@Column(name = "author_user_id", nullable = false, updatable = false)
	private UUID authorUserId;

	@Column(name = "body", nullable = false, updatable = false, length = MAX_BODY_LENGTH)
	private String body;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PaymentSlipNote() {
	}

	public PaymentSlipNote(UUID tenantId, UUID slipId, UUID authorUserId, String body) {
		this.tenantId = tenantId;
		this.slipId = slipId;
		this.authorUserId = authorUserId;
		this.body = body;
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

	public UUID getSlipId() {
		return slipId;
	}

	public UUID getAuthorUserId() {
		return authorUserId;
	}

	public String getBody() {
		return body;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}

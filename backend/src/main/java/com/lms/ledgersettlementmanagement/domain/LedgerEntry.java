package com.lms.ledgersettlementmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mapped 1:1 onto {@code ledger_entry} (V19) - PAY-3. Fully append-only,
 * mirroring {@code CoursePriceHistory}'s exact shape: extends {@link
 * BaseEntity} directly, only {@code created_at} is auto-managed.
 *
 * <p>{@code orderId}/{@code paymentId} are opaque cross-domain ids into
 * {@code payment-management} (composite FK-enforced at the DB level, V19) -
 * never a JPA association across the module boundary. {@code
 * PAYMENT_CONFIRMED} entries carry a positive {@code amount}; {@code REFUND}
 * entries carry a negative {@code amount} (this module's own sign
 * convention - the DB only requires {@code amount <> 0}) and always set
 * {@code reversesEntryId} to the {@code PAYMENT_CONFIRMED} entry they
 * reverse.
 *
 * <p>Written EXCLUSIVELY by {@code LedgerEntryService} - no repository
 * method exposes update/delete (see {@code LedgerEntryRepository}), per
 * {@code .claude/rules/backend.md}'s append-only enforcement guidance.
 */
@Entity
@Table(name = "ledger_entry")
@EntityListeners(AuditingEntityListener.class)
public class LedgerEntry extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "order_id", nullable = false, updatable = false)
	private UUID orderId;

	@Column(name = "payment_id", updatable = false)
	private UUID paymentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false, updatable = false, length = 30)
	private LedgerEntryType entryType;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "reverses_entry_id", updatable = false)
	private UUID reversesEntryId;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected LedgerEntry() {
	}

	public LedgerEntry(UUID tenantId, UUID orderId, UUID paymentId, LedgerEntryType entryType, BigDecimal amount,
			UUID reversesEntryId) {
		this.tenantId = tenantId;
		this.orderId = orderId;
		this.paymentId = paymentId;
		this.entryType = entryType;
		this.amount = amount;
		this.reversesEntryId = reversesEntryId;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public UUID getPaymentId() {
		return paymentId;
	}

	public LedgerEntryType getEntryType() {
		return entryType;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public UUID getReversesEntryId() {
		return reversesEntryId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}

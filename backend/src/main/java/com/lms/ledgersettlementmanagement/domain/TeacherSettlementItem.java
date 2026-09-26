package com.lms.ledgersettlementmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mapped onto {@code teacher_settlement_item} (V55) - one source {@code
 * ledger_entry} included in a REGULAR settlement. Fully immutable. The
 * {@code (tenant_id, ledger_entry_id)} unique constraint guarantees a ledger
 * entry is settled at most once across every period and every run, and
 * gives each settlement a traceable link back to the authoritative ledger.
 */
@Entity
@Table(name = "teacher_settlement_item")
public class TeacherSettlementItem extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "settlement_id", nullable = false, updatable = false)
	private UUID settlementId;

	@Column(name = "ledger_entry_id", nullable = false, updatable = false)
	private UUID ledgerEntryId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	protected TeacherSettlementItem() {
	}

	public TeacherSettlementItem(UUID tenantId, UUID settlementId, UUID ledgerEntryId, UUID courseId,
			BigDecimal amount) {
		this.tenantId = tenantId;
		this.settlementId = settlementId;
		this.ledgerEntryId = ledgerEntryId;
		this.courseId = courseId;
		this.amount = amount;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getSettlementId() {
		return settlementId;
	}

	public UUID getLedgerEntryId() {
		return ledgerEntryId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

}

package com.lms.financeexpensemanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import com.lms.financeexpensemanagement.domain.FinanceEnums.SourceType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * FIN-01: one account-book posting. Monetary columns ({@code type}, {@code
 * amount}, {@code currency}, {@code transactionDate}, source link) are
 * immutable. The only state changes are {@code UNPOSTED -> POSTED} (an account
 * is assigned to a student-payment posting that had no routing) and
 * {@code POSTED -> REVERSED} (a REVERSAL row was appended). Note and category
 * may be edited; nothing is ever deleted.
 */
@Entity
@Table(name = "finance_transaction")
public class FinanceTransaction extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false, length = 12)
	private TransactionType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private TransactionStatus status;

	@Column(name = "amount", nullable = false, updatable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "from_account_id")
	private UUID fromAccountId;

	@Column(name = "to_account_id")
	private UUID toAccountId;

	@Column(name = "category_id")
	private UUID categoryId;

	@Column(name = "transaction_date", nullable = false, updatable = false)
	private LocalDate transactionDate;

	@Column(name = "posted_at")
	private Instant postedAt;

	@Column(name = "note", length = 500)
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, updatable = false, length = 20)
	private SourceType sourceType;

	@Column(name = "source_id", updatable = false)
	private UUID sourceId;

	@Column(name = "reverses_transaction_id", updatable = false)
	private UUID reversesTransactionId;

	@Column(name = "idempotency_key", updatable = false, length = 100)
	private String idempotencyKey;

	@Column(name = "balance_before", updatable = false, precision = 14, scale = 2)
	private BigDecimal balanceBefore;

	@Column(name = "balance_after", updatable = false, precision = 14, scale = 2)
	private BigDecimal balanceAfter;

	@Column(name = "reason", updatable = false, length = 500)
	private String reason;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "reversed_at")
	private Instant reversedAt;

	@Column(name = "reversed_by")
	private UUID reversedBy;

	protected FinanceTransaction() {
	}

	private FinanceTransaction(UUID tenantId, TransactionType type, TransactionStatus status, BigDecimal amount,
			String currency, UUID fromAccountId, UUID toAccountId, UUID categoryId, LocalDate transactionDate,
			Instant postedAt, String note, SourceType sourceType, UUID sourceId, UUID reversesTransactionId,
			String idempotencyKey, String reason, UUID createdBy) {
		this.tenantId = tenantId;
		this.type = type;
		this.status = status;
		this.amount = amount;
		this.currency = currency;
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.categoryId = categoryId;
		this.transactionDate = transactionDate;
		this.postedAt = postedAt;
		this.note = note;
		this.sourceType = sourceType;
		this.sourceId = sourceId;
		this.reversesTransactionId = reversesTransactionId;
		this.idempotencyKey = idempotencyKey;
		this.reason = reason;
		this.createdBy = createdBy;
	}

	/** A posted row of any type except ADJUSTMENT (use {@link #adjustment}). */
	public static FinanceTransaction posted(UUID tenantId, TransactionType type, BigDecimal amount, String currency,
			UUID fromAccountId, UUID toAccountId, UUID categoryId, LocalDate transactionDate, Instant postedAt,
			String note, SourceType sourceType, UUID sourceId, UUID reversesTransactionId, String idempotencyKey,
			String reason, UUID createdBy) {
		return new FinanceTransaction(tenantId, type, TransactionStatus.POSTED, amount, currency, fromAccountId,
				toAccountId, categoryId, transactionDate, postedAt, note, sourceType, sourceId, reversesTransactionId,
				idempotencyKey, reason, createdBy);
	}

	/** A student-payment (or refund) posting that is waiting for routing. */
	public static FinanceTransaction unposted(UUID tenantId, TransactionType type, BigDecimal amount,
			String currency, LocalDate transactionDate, String note, UUID ledgerEntryId, UUID reversesTransactionId,
			UUID createdBy) {
		return new FinanceTransaction(tenantId, type, TransactionStatus.UNPOSTED, amount, currency, null, null, null,
				transactionDate, null, note, SourceType.LEDGER_ENTRY, ledgerEntryId, reversesTransactionId, null,
				null, createdBy);
	}

	public static FinanceTransaction adjustment(UUID tenantId, BigDecimal difference, String currency, UUID accountId,
			LocalDate transactionDate, Instant postedAt, BigDecimal balanceBefore, String reason,
			String idempotencyKey, UUID createdBy) {
		boolean credit = difference.signum() > 0;
		FinanceTransaction row = new FinanceTransaction(tenantId, TransactionType.ADJUSTMENT,
				TransactionStatus.POSTED, difference.abs(), currency, credit ? null : accountId,
				credit ? accountId : null, null, transactionDate, postedAt, null, SourceType.MANUAL, null, null,
				idempotencyKey, reason, createdBy);
		row.balanceBefore = balanceBefore;
		row.balanceAfter = balanceBefore.add(difference);
		return row;
	}

	public void post(UUID fromAccountId, UUID toAccountId, Instant at) {
		if (status != TransactionStatus.UNPOSTED) {
			throw new IllegalStateException("Only an UNPOSTED row can be posted");
		}
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.postedAt = at;
		this.status = TransactionStatus.POSTED;
	}

	public void markReversed(UUID actorId, Instant at) {
		if (status != TransactionStatus.POSTED) {
			throw new IllegalStateException("Only a POSTED row can be reversed");
		}
		this.status = TransactionStatus.REVERSED;
		this.reversedAt = at;
		this.reversedBy = actorId;
	}

	public void editMetadata(String note, UUID categoryId) {
		this.note = note;
		this.categoryId = categoryId;
	}

	/** Signed effect on {@code accountId}: + credit, - debit, 0 when untouched or UNPOSTED. */
	public BigDecimal effectOn(UUID accountId) {
		if (status == TransactionStatus.UNPOSTED) {
			return BigDecimal.ZERO;
		}
		BigDecimal effect = BigDecimal.ZERO;
		if (accountId.equals(toAccountId)) {
			effect = effect.add(amount);
		}
		if (accountId.equals(fromAccountId)) {
			effect = effect.subtract(amount);
		}
		return effect;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public TransactionType getType() {
		return type;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public UUID getFromAccountId() {
		return fromAccountId;
	}

	public UUID getToAccountId() {
		return toAccountId;
	}

	public UUID getCategoryId() {
		return categoryId;
	}

	public LocalDate getTransactionDate() {
		return transactionDate;
	}

	public Instant getPostedAt() {
		return postedAt;
	}

	public String getNote() {
		return note;
	}

	public SourceType getSourceType() {
		return sourceType;
	}

	public UUID getSourceId() {
		return sourceId;
	}

	public UUID getReversesTransactionId() {
		return reversesTransactionId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public BigDecimal getBalanceBefore() {
		return balanceBefore;
	}

	public BigDecimal getBalanceAfter() {
		return balanceAfter;
	}

	public String getReason() {
		return reason;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getReversedAt() {
		return reversedAt;
	}

	public UUID getReversedBy() {
		return reversedBy;
	}

}

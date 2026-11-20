package com.lms.financeexpensemanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * FIN-01: a scheduled posting definition. Editing or cancelling it never
 * touches already POSTED occurrences ({@link FinanceScheduledOccurrence}).
 */
@Entity
@Table(name = "finance_scheduled_transaction")
public class FinanceScheduledTransaction extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false, length = 10)
	private TransactionType type;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "from_account_id")
	private UUID fromAccountId;

	@Column(name = "to_account_id")
	private UUID toAccountId;

	@Column(name = "category_id")
	private UUID categoryId;

	@Column(name = "note", length = 500)
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(name = "schedule_type", nullable = false, length = 10)
	private ScheduleType scheduleType;

	@Column(name = "scheduled_date")
	private LocalDate scheduledDate;

	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.SMALLINT)
	@Column(name = "day_of_month")
	private Integer dayOfMonth;

	@Column(name = "starts_on", nullable = false, updatable = false)
	private LocalDate startsOn;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private ScheduleStatus status;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancelled_by")
	private UUID cancelledBy;

	protected FinanceScheduledTransaction() {
	}

	public FinanceScheduledTransaction(UUID tenantId, String name, TransactionType type, BigDecimal amount,
			String currency, UUID fromAccountId, UUID toAccountId, UUID categoryId, String note,
			ScheduleType scheduleType, LocalDate scheduledDate, Integer dayOfMonth, LocalDate startsOn,
			UUID createdBy) {
		this.tenantId = tenantId;
		this.name = name;
		this.type = type;
		this.amount = amount;
		this.currency = currency;
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.categoryId = categoryId;
		this.note = note;
		this.scheduleType = scheduleType;
		this.scheduledDate = scheduledDate;
		this.dayOfMonth = dayOfMonth;
		this.startsOn = startsOn;
		this.status = ScheduleStatus.ACTIVE;
		this.createdBy = createdBy;
	}

	public void update(String name, BigDecimal amount, UUID fromAccountId, UUID toAccountId, UUID categoryId,
			String note, ScheduleType scheduleType, LocalDate scheduledDate, Integer dayOfMonth, UUID actorId) {
		this.name = name;
		this.amount = amount;
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.categoryId = categoryId;
		this.note = note;
		this.scheduleType = scheduleType;
		this.scheduledDate = scheduledDate;
		this.dayOfMonth = dayOfMonth;
		this.updatedBy = actorId;
	}

	public void cancel(UUID actorId, Instant at) {
		this.status = ScheduleStatus.CANCELLED;
		this.cancelledAt = at;
		this.cancelledBy = actorId;
		this.updatedBy = actorId;
	}

	public void complete() {
		this.status = ScheduleStatus.COMPLETED;
	}

	/** The due date in {@code month}, or {@code null} when the month has no such day (skipped). */
	public LocalDate dueDateIn(YearMonth month) {
		if (scheduleType != ScheduleType.MONTHLY) {
			return null;
		}
		return dayOfMonth <= month.lengthOfMonth() ? month.atDay(dayOfMonth) : null;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public TransactionType getType() {
		return type;
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

	public String getNote() {
		return note;
	}

	public ScheduleType getScheduleType() {
		return scheduleType;
	}

	public LocalDate getScheduledDate() {
		return scheduledDate;
	}

	public Integer getDayOfMonth() {
		return dayOfMonth;
	}

	public LocalDate getStartsOn() {
		return startsOn;
	}

	public ScheduleStatus getStatus() {
		return status;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

}

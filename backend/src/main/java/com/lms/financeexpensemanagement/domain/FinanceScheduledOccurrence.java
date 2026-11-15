package com.lms.financeexpensemanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import com.lms.financeexpensemanagement.domain.FinanceEnums.OccurrenceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * FIN-01: one due date of a schedule. Unique per (tenant, schedule, due date),
 * so a duplicate job run or a double click can never post twice.
 */
@Entity
@Table(name = "finance_scheduled_occurrence")
public class FinanceScheduledOccurrence extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "schedule_id", nullable = false, updatable = false)
	private UUID scheduleId;

	@Column(name = "due_date", nullable = false, updatable = false)
	private LocalDate dueDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private OccurrenceStatus status;

	@Column(name = "transaction_id")
	private UUID transactionId;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "last_error", length = 500)
	private String lastError;

	@Column(name = "executed_at")
	private Instant executedAt;

	@Column(name = "executed_by")
	private UUID executedBy;

	protected FinanceScheduledOccurrence() {
	}

	public FinanceScheduledOccurrence(UUID tenantId, UUID scheduleId, LocalDate dueDate) {
		this.tenantId = tenantId;
		this.scheduleId = scheduleId;
		this.dueDate = dueDate;
		this.status = OccurrenceStatus.DUE;
	}

	public void markPosted(UUID transactionId, UUID actorId, Instant at) {
		this.status = OccurrenceStatus.POSTED;
		this.transactionId = transactionId;
		this.attempts++;
		this.lastError = null;
		this.executedAt = at;
		this.executedBy = actorId;
	}

	public void markFailed(String error, UUID actorId, Instant at) {
		this.status = OccurrenceStatus.FAILED;
		this.attempts++;
		this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
		this.executedAt = at;
		this.executedBy = actorId;
	}

	public void markSkipped(UUID actorId, Instant at) {
		this.status = OccurrenceStatus.SKIPPED;
		this.executedAt = at;
		this.executedBy = actorId;
	}

	public boolean isOpen() {
		return status == OccurrenceStatus.DUE || status == OccurrenceStatus.FAILED;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getScheduleId() {
		return scheduleId;
	}

	public LocalDate getDueDate() {
		return dueDate;
	}

	public OccurrenceStatus getStatus() {
		return status;
	}

	public UUID getTransactionId() {
		return transactionId;
	}

	public int getAttempts() {
		return attempts;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getExecutedAt() {
		return executedAt;
	}

	public UUID getExecutedBy() {
		return executedBy;
	}

}

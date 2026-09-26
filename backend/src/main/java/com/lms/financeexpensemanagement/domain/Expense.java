package com.lms.financeexpensemanagement.domain;

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
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mapped onto {@code expense} (V54) - Wave 7, PAR-23-01/05. Append-only
 * financial history (wave-07-plan.md §10 judgment call 1): every business
 * column is {@code updatable = false}; the ONLY permitted mutation is the
 * one-way {@link #voidExpense} transition, which sets the three void columns
 * exactly once. There is no edit and no delete - a correction is a void plus
 * a new expense.
 */
@Entity
@Table(name = "expense")
@EntityListeners(AuditingEntityListener.class)
public class Expense extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "category_id", nullable = false, updatable = false)
	private UUID categoryId;

	@Column(name = "expense_date", nullable = false, updatable = false)
	private LocalDate expenseDate;

	@Column(name = "description", nullable = false, updatable = false, length = 500)
	private String description;

	@Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false, updatable = false, length = 20)
	private ExpenseMethod method;

	@Column(name = "reference", updatable = false, length = 255)
	private String reference;

	@Column(name = "attachment_object_key", updatable = false, length = 1024)
	private String attachmentObjectKey;

	@Column(name = "attachment_filename", updatable = false, length = 255)
	private String attachmentFilename;

	@Column(name = "attachment_mime_type", updatable = false, length = 255)
	private String attachmentMimeType;

	@Column(name = "attachment_size_bytes", updatable = false)
	private Long attachmentSizeBytes;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "voided_at")
	private Instant voidedAt;

	@Column(name = "voided_by")
	private UUID voidedBy;

	@Column(name = "void_reason", length = 500)
	private String voidReason;

	protected Expense() {
	}

	public Expense(UUID tenantId, UUID categoryId, LocalDate expenseDate, String description, BigDecimal amount,
			String currency, ExpenseMethod method, String reference, UUID createdBy) {
		this.tenantId = tenantId;
		this.categoryId = categoryId;
		this.expenseDate = expenseDate;
		this.description = description;
		this.amount = amount;
		this.currency = currency;
		this.method = method;
		this.reference = reference;
		this.createdBy = createdBy;
	}

	/** Set once, before the first save only - the columns are not updatable afterwards. */
	public void attachReceipt(String objectKey, String filename, String mimeType, long sizeBytes) {
		this.attachmentObjectKey = objectKey;
		this.attachmentFilename = filename;
		this.attachmentMimeType = mimeType;
		this.attachmentSizeBytes = sizeBytes;
	}

	/**
	 * One-way VOID transition. Callers must check {@link #isVoided()} first
	 * and surface a 409; this guard is the last line of defense.
	 */
	public void voidExpense(UUID actorId, String reason, Instant at) {
		if (isVoided()) {
			throw new IllegalStateException("Expense is already voided");
		}
		this.voidedBy = actorId;
		this.voidReason = reason;
		this.voidedAt = at;
	}

	public boolean isVoided() {
		return voidedAt != null;
	}

	public boolean hasAttachment() {
		return attachmentObjectKey != null;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCategoryId() {
		return categoryId;
	}

	public LocalDate getExpenseDate() {
		return expenseDate;
	}

	public String getDescription() {
		return description;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public ExpenseMethod getMethod() {
		return method;
	}

	public String getReference() {
		return reference;
	}

	public String getAttachmentObjectKey() {
		return attachmentObjectKey;
	}

	public String getAttachmentFilename() {
		return attachmentFilename;
	}

	public String getAttachmentMimeType() {
		return attachmentMimeType;
	}

	public Long getAttachmentSizeBytes() {
		return attachmentSizeBytes;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getVoidedAt() {
		return voidedAt;
	}

	public UUID getVoidedBy() {
		return voidedBy;
	}

	public String getVoidReason() {
		return voidReason;
	}

}

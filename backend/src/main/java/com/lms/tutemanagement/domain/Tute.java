package com.lms.tutemanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code tute} (V121, TUTE-01): one printed handout of a course. {@code quantity} is the
 * lifetime stock; availability is derived from the non-cancelled fulfillments under the row
 * lock. Never hard-deleted once any fulfillment exists (archived instead).
 */
@Entity
@Table(name = "tute")
public class Tute extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "description", length = 2000)
	private String description;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_type", nullable = false, length = 30)
	private TutePaymentType paymentType;

	@Column(name = "price", precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "currency", nullable = false, length = 3)
	private String currency;

	@Column(name = "paid_lesson_id")
	private UUID paidLessonId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private TuteStatus status = TuteStatus.ACTIVE;

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "archived_by")
	private UUID archivedBy;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Tute() {
	}

	public Tute(UUID tenantId, UUID courseId, String name, String description, int quantity,
			TutePaymentType paymentType, BigDecimal price, String currency, UUID paidLessonId) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.currency = currency;
		update(name, description, quantity, paymentType, price, paidLessonId);
	}

	public final void update(String name, String description, int quantity, TutePaymentType paymentType,
			BigDecimal price, UUID paidLessonId) {
		this.name = name;
		this.description = description;
		this.quantity = quantity;
		this.paymentType = paymentType;
		this.price = price;
		this.paidLessonId = paidLessonId;
	}

	public void archive(UUID actorId, Instant now) {
		this.status = TuteStatus.ARCHIVED;
		this.archivedAt = now;
		this.archivedBy = actorId;
	}

	public boolean isArchived() {
		return status == TuteStatus.ARCHIVED;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public int getQuantity() {
		return quantity;
	}

	public TutePaymentType getPaymentType() {
		return paymentType;
	}

	public BigDecimal getPrice() {
		return price;
	}

	public String getCurrency() {
		return currency;
	}

	public UUID getPaidLessonId() {
		return paidLessonId;
	}

	public TuteStatus getStatus() {
		return status;
	}

	public Instant getArchivedAt() {
		return archivedAt;
	}

	public long getVersion() {
		return version;
	}

}

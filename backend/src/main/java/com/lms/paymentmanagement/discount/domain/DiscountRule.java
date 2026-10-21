package com.lms.paymentmanagement.discount.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.TimestampedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * V92 {@code discount_rule}: the header of a tenant discount rule. Holds only
 * mutable state - {@code active}, {@code archived_at}, the pointer to the
 * current immutable {@link DiscountRuleVersion} and the normalized code used by
 * the per-tenant live-code unique index (D7). Pricing terms live on the
 * versions (D13). Never deleted - "delete" is {@link #archive(Instant)} (D11).
 */
@Entity
@Table(name = "discount_rule")
public class DiscountRule extends TimestampedEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "discount_type", nullable = false, updatable = false, length = 30)
	private DiscountType discountType;

	@Column(name = "code_normalized", length = 40)
	private String codeNormalized;

	@Column(name = "current_version_id")
	private UUID currentVersionId;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	protected DiscountRule() {
	}

	public DiscountRule(UUID tenantId, DiscountType discountType, String codeNormalized, boolean active,
			UUID createdBy) {
		this.tenantId = tenantId;
		this.discountType = discountType;
		this.codeNormalized = codeNormalized;
		this.active = active;
		this.createdBy = createdBy;
	}

	/** Points the header at a newly appended version (and its normalized code). */
	public void moveToVersion(UUID versionId, String codeNormalized) {
		this.currentVersionId = versionId;
		this.codeNormalized = codeNormalized;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public void archive(Instant at) {
		if (archivedAt == null) {
			archivedAt = at;
		}
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public DiscountType getDiscountType() {
		return discountType;
	}

	public String getCodeNormalized() {
		return codeNormalized;
	}

	public UUID getCurrentVersionId() {
		return currentVersionId;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getArchivedAt() {
		return archivedAt;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

}

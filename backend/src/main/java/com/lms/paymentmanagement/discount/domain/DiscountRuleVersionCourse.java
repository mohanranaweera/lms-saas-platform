package com.lms.paymentmanagement.discount.domain;

import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * V92 {@code discount_rule_version_course}: one required / reward course of a
 * rule version - immutable with its version. The PK is the natural key
 * {@code (tenant_id, version_id, course_id, role)}; {@link Persistable} makes
 * {@code save} a plain insert (no merge-select).
 */
@Entity
@Immutable
@IdClass(DiscountRuleVersionCourse.Key.class)
@Table(name = "discount_rule_version_course")
public class DiscountRuleVersionCourse implements TenantOwned, Persistable<DiscountRuleVersionCourse.Key> {

	@Id
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Id
	@Column(name = "version_id", nullable = false, updatable = false)
	private UUID versionId;

	@Id
	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Id
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, updatable = false, length = 20)
	private DiscountCourseRole role;

	@Transient
	private boolean isNew = true;

	protected DiscountRuleVersionCourse() {
	}

	public DiscountRuleVersionCourse(UUID tenantId, UUID versionId, UUID courseId, DiscountCourseRole role) {
		this.tenantId = tenantId;
		this.versionId = versionId;
		this.courseId = courseId;
		this.role = role;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	@Override
	public Key getId() {
		return new Key(tenantId, versionId, courseId, role);
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getVersionId() {
		return versionId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public DiscountCourseRole getRole() {
		return role;
	}

	/** Composite id. */
	public static class Key implements Serializable {

		private UUID tenantId;

		private UUID versionId;

		private UUID courseId;

		private DiscountCourseRole role;

		public Key() {
		}

		public Key(UUID tenantId, UUID versionId, UUID courseId, DiscountCourseRole role) {
			this.tenantId = tenantId;
			this.versionId = versionId;
			this.courseId = courseId;
			this.role = role;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Key other)) {
				return false;
			}
			return Objects.equals(tenantId, other.tenantId) && Objects.equals(versionId, other.versionId)
					&& Objects.equals(courseId, other.courseId) && role == other.role;
		}

		@Override
		public int hashCode() {
			return Objects.hash(tenantId, versionId, courseId, role);
		}

	}

}

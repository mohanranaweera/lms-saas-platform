package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code course_participation} (V86, D01, ADR-028): free course enrollment -
 * one row per tenant + student + course. Grants navigation of safe course
 * metadata only; protected leaf resources additionally need paid coverage
 * (legacy {@code enrollment} access or a {@code content_entitlement}), decided
 * by {@code LessonAccessApi}.
 *
 * <p>Rows are written ONLY by the race-safe native {@code INSERT ... ON
 * CONFLICT DO NOTHING} in {@code CourseParticipationRepository} and are never
 * updated or deleted (payment events never revoke participation, D08), hence
 * {@link Immutable}.
 */
@Entity
@Immutable
@Table(name = "course_participation")
public class CourseParticipation implements TenantOwned {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "enrolled_at", nullable = false, updatable = false)
	private Instant enrolledAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, updatable = false, length = 30)
	private CourseParticipationSource source;

	protected CourseParticipation() {
	}

	public UUID getId() {
		return id;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public Instant getEnrolledAt() {
		return enrolledAt;
	}

	public CourseParticipationSource getSource() {
		return source;
	}

}

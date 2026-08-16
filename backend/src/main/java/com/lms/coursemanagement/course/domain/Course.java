package com.lms.coursemanagement.course.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code course-management}'s aggregate root (MVP-008), mapped 1:1 onto
 * {@code course} (V11). {@code teacherId} is an OPAQUE {@code tenant_user}
 * id only - never a JPA {@code @ManyToOne} across the module boundary (per
 * {@code .claude/rules/architecture.md}), though it is still schema-enforced
 * via V11's composite {@code fk_course_teacher} FK.
 *
 * <p>{@code price} may only ever be written by {@code CourseService
 * #changePrice} - see that method's javadoc for why no other mutator touches
 * this field.
 */
@Entity
@Table(name = "course")
public class Course extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "teacher_id", nullable = false)
	private UUID teacherId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "slug", nullable = false)
	private String slug;

	@Column(name = "category", nullable = false)
	private String category;

	@Column(name = "subject")
	private String subject;

	@Column(name = "stream")
	private String stream;

	@Column(name = "grade")
	private String grade;

	@Column(name = "academic_year")
	private String academicYear;

	@Column(name = "description")
	private String description;

	@Column(name = "price", nullable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "access_duration_days")
	private Integer accessDurationDays;

	@Column(name = "enrollment_rule")
	private String enrollmentRule;

	@Column(name = "status", nullable = false)
	private CourseStatus status;

	protected Course() {
	}

	public Course(UUID tenantId, UUID teacherId, String name, String slug, String category, String subject,
			String stream, String grade, String academicYear, String description, BigDecimal price,
			Integer accessDurationDays, String enrollmentRule, CourseStatus status) {
		this.tenantId = tenantId;
		this.teacherId = teacherId;
		this.name = name;
		this.slug = slug;
		this.category = category;
		this.subject = subject;
		this.stream = stream;
		this.grade = grade;
		this.academicYear = academicYear;
		this.description = description;
		this.price = price;
		this.accessDurationDays = accessDurationDays;
		this.enrollmentRule = enrollmentRule;
		this.status = status == null ? CourseStatus.DRAFT : status;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getTeacherId() {
		return teacherId;
	}

	/**
	 * Callers outside {@code CourseService#reassignTeacher} must not call
	 * this - that method is the sole Tenant-Admin-only path for changing
	 * {@code teacher_id} (plan §10/§15(a); never {@code DomainArea.COURSES}'s
	 * flat matrix, never the Teacher-ownership path). Enforced by convention
	 * and code review only, same caveat as {@link #setPrice}.
	 */
	public void setTeacherId(UUID teacherId) {
		this.teacherId = teacherId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSlug() {
		return slug;
	}

	public void setSlug(String slug) {
		this.slug = slug;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getStream() {
		return stream;
	}

	public void setStream(String stream) {
		this.stream = stream;
	}

	public String getGrade() {
		return grade;
	}

	public void setGrade(String grade) {
		this.grade = grade;
	}

	public String getAcademicYear() {
		return academicYear;
	}

	public void setAcademicYear(String academicYear) {
		this.academicYear = academicYear;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public BigDecimal getPrice() {
		return price;
	}

	/**
	 * Callers outside {@code CourseService#changePrice} must not call this -
	 * that method is the one non-bypassable write path for {@code price}
	 * (plan §15(c): every change must also write a {@code course_price_history}
	 * row and publish {@code CoursePriceChangedEvent} in the same
	 * transaction). This is a domain entity in a different package
	 * ({@code course.domain}) than its sole legitimate caller
	 * ({@code course.service}), so Java's package-private visibility cannot
	 * express this restriction at compile time - enforced by convention and
	 * code review only, the same as every other service-only mutation in
	 * this codebase (e.g. {@code TenantStatusService}'s status transitions).
	 * Do not call this from anywhere other than {@code CourseService
	 * #changePrice} - see that method's javadoc.
	 */
	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public Integer getAccessDurationDays() {
		return accessDurationDays;
	}

	public void setAccessDurationDays(Integer accessDurationDays) {
		this.accessDurationDays = accessDurationDays;
	}

	public String getEnrollmentRule() {
		return enrollmentRule;
	}

	public void setEnrollmentRule(String enrollmentRule) {
		this.enrollmentRule = enrollmentRule;
	}

	public CourseStatus getStatus() {
		return status;
	}

	public void setStatus(CourseStatus status) {
		this.status = status;
	}

}

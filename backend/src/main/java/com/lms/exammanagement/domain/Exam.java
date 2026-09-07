package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code exam} (V26). {@code courseId} is an OPAQUE {@code
 * course-management} id only - never a JPA association across the module
 * boundary, though schema-enforced via V26's composite {@code fk_exam_course}
 * FK.
 *
 * <p><b>Lifecycle.</b> {@code status} only ever advances forward: {@code DRAFT
 * -> SCHEDULED} is the single manually-triggered transition ({@code
 * ExamSchedulingService#scheduleExam}, {@code APPROVE}-gated); {@code
 * SCHEDULED -> PUBLISHED -> CLOSED} are system-computed, lazily advanced by
 * {@link com.lms.exammanagement.service.ExamLifecycleService} - never set
 * directly from a controller. {@code resultsPublishedAt} is a fully separate
 * gate set once, by {@code ResultsPublishingService#publishResults}, only
 * once {@code status == CLOSED} (service-layer precondition, not a DB {@code
 * CHECK} - see V26's own header comment).
 *
 * <p><b>Deviation flagged (see module report).</b> {@code scheduled_start}/
 * {@code scheduled_end}/{@code time_limit_minutes} are {@code NOT NULL} with a
 * {@code CHECK (scheduled_end > scheduled_start)} from the very first {@code
 * DRAFT} row (V26), but the API contract (plan §10) creates a brand new
 * {@code DRAFT} exam from only a {@code title}. {@link
 * com.lms.exammanagement.service.ExamSchedulingService#createDraftExam}
 * therefore seeds a structurally-valid placeholder window (a 1-minute window
 * starting "now", 1-minute time limit) that MUST be overwritten via {@code
 * updateDraftExam} before the exam can ever be scheduled - this placeholder
 * is never shown to a student (only visible to the authoring Teacher/TA/staff
 * while the exam is still {@code DRAFT}).
 */
@Entity
@Table(name = "exam")
public class Exam extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "scheduled_start", nullable = false)
	private Instant scheduledStart;

	@Column(name = "scheduled_end", nullable = false)
	private Instant scheduledEnd;

	@Column(name = "time_limit_minutes", nullable = false)
	private Integer timeLimitMinutes;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private ExamStatus status;

	@Column(name = "results_published_at")
	private Instant resultsPublishedAt;

	protected Exam() {
	}

	public Exam(UUID tenantId, UUID courseId, String title, Instant scheduledStart, Instant scheduledEnd,
			Integer timeLimitMinutes, ExamStatus status) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.title = title;
		this.scheduledStart = scheduledStart;
		this.scheduledEnd = scheduledEnd;
		this.timeLimitMinutes = timeLimitMinutes;
		this.status = status;
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

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public Instant getScheduledStart() {
		return scheduledStart;
	}

	public void setScheduledStart(Instant scheduledStart) {
		this.scheduledStart = scheduledStart;
	}

	public Instant getScheduledEnd() {
		return scheduledEnd;
	}

	public void setScheduledEnd(Instant scheduledEnd) {
		this.scheduledEnd = scheduledEnd;
	}

	public Integer getTimeLimitMinutes() {
		return timeLimitMinutes;
	}

	public void setTimeLimitMinutes(Integer timeLimitMinutes) {
		this.timeLimitMinutes = timeLimitMinutes;
	}

	public ExamStatus getStatus() {
		return status;
	}

	/**
	 * Callers outside {@code ExamSchedulingService#scheduleExam} and {@link
	 * com.lms.exammanagement.service.ExamLifecycleService} must not call this
	 * - status only ever advances forward (never backward), per this class's
	 * own javadoc. Enforced by convention/code review only, the same caveat
	 * as {@code Course#setPrice}.
	 */
	public void setStatus(ExamStatus status) {
		this.status = status;
	}

	public Instant getResultsPublishedAt() {
		return resultsPublishedAt;
	}

	/**
	 * Callers outside {@code ResultsPublishingService#publishResults} must not
	 * call this - see that method's javadoc for the one non-bypassable write
	 * path (results may only be published once {@code status == CLOSED}).
	 */
	public void setResultsPublishedAt(Instant resultsPublishedAt) {
		this.resultsPublishedAt = resultsPublishedAt;
	}

}

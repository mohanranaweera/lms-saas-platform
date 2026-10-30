package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 *
 * <p><b>Klass exams (V111, {@code configVersion = 2}).</b> The same table also holds
 * Klass-model exams ({@link ExamType} MCQ / WRITTEN / PHYSICAL). Their status is derived
 * from {@code scheduledStart}/{@code scheduledEnd} and the injected clock; the stored
 * {@code status} stays {@code DRAFT} (schema-enforced), which keeps every legacy
 * lifecycle / attempt path inert for them. Their configuration is only ever written by
 * {@code com.lms.exammanagement.service.exam.ExamConfigService}.
 */
@Entity
@Table(name = "exam")
public class Exam extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	/** Updatable only for Klass exams before their first attempt (ExamConfigService). */
	@Column(name = "course_id", nullable = false)
	private UUID courseId;

	@Column(name = "title", nullable = false)
	private String title;

	@Column(name = "scheduled_start", nullable = false)
	private Instant scheduledStart;

	@Column(name = "scheduled_end", nullable = false)
	private Instant scheduledEnd;

	/** Never null for legacy exams (ck_exam_legacy_shape); optional for PHYSICAL Klass exams. */
	@Column(name = "time_limit_minutes")
	private Integer timeLimitMinutes;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private ExamStatus status;

	@Column(name = "results_published_at")
	private Instant resultsPublishedAt;

	@Column(name = "config_version", nullable = false, updatable = false)
	private int configVersion = LEGACY_CONFIG_VERSION;

	@Enumerated(EnumType.STRING)
	@Column(name = "exam_type", nullable = false, length = 10)
	private ExamType examType = ExamType.MCQ;

	@Column(name = "password_ciphertext")
	private String passwordCiphertext;

	@Column(name = "question_count")
	private Integer questionCount;

	@Column(name = "cutoff_marks", precision = 10, scale = 2)
	private BigDecimal cutoffMarks;

	@Column(name = "total_marks", precision = 10, scale = 2)
	private BigDecimal totalMarks;

	@Column(name = "shuffle_questions", nullable = false)
	private boolean shuffleQuestions;

	@Column(name = "shuffle_answers", nullable = false)
	private boolean shuffleAnswers;

	@Column(name = "enroll_all_students", nullable = false)
	private boolean enrollAllStudents;

	@Column(name = "auto_enroll_on_payment", nullable = false)
	private boolean autoEnrollOnPayment;

	/** JSON object, present iff {@code autoEnrollOnPayment} (ck_exam_payment_criteria_iff_auto_enroll). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payment_criteria")
	private String paymentCriteria;

	@Column(name = "show_answers_with_results", nullable = false)
	private boolean showAnswersWithResults;

	@Column(name = "question_paper_media_id")
	private UUID questionPaperMediaId;

	@Column(name = "marking_scheme_media_id")
	private UUID markingSchemeMediaId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	/** {@code exam.config_version} of a legacy MVP-017 exam. */
	public static final int LEGACY_CONFIG_VERSION = 1;

	/** {@code exam.config_version} of a Klass-model exam (Slice B+). */
	public static final int KLASS_CONFIG_VERSION = 2;

	protected Exam() {
	}

	/**
	 * A new Klass-model exam (V111). Stored status is fixed at {@code DRAFT}; every
	 * configuration field is applied by the caller before the first flush.
	 */
	public static Exam klassExam(UUID tenantId, UUID courseId, ExamType type, String name, Instant startAt,
			Instant endAt, Integer durationMinutes) {
		Exam exam = new Exam(tenantId, courseId, name, startAt, endAt, durationMinutes, ExamStatus.DRAFT);
		exam.configVersion = KLASS_CONFIG_VERSION;
		exam.examType = type;
		return exam;
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

	public boolean isKlassExam() {
		return configVersion == KLASS_CONFIG_VERSION;
	}

	public int getConfigVersion() {
		return configVersion;
	}

	public void setCourseId(UUID courseId) {
		this.courseId = courseId;
	}

	public ExamType getExamType() {
		return examType;
	}

	public void setExamType(ExamType examType) {
		this.examType = examType;
	}

	public String getPasswordCiphertext() {
		return passwordCiphertext;
	}

	public void setPasswordCiphertext(String passwordCiphertext) {
		this.passwordCiphertext = passwordCiphertext;
	}

	public Integer getQuestionCount() {
		return questionCount;
	}

	public void setQuestionCount(Integer questionCount) {
		this.questionCount = questionCount;
	}

	public BigDecimal getCutoffMarks() {
		return cutoffMarks;
	}

	public void setCutoffMarks(BigDecimal cutoffMarks) {
		this.cutoffMarks = cutoffMarks;
	}

	public BigDecimal getTotalMarks() {
		return totalMarks;
	}

	public void setTotalMarks(BigDecimal totalMarks) {
		this.totalMarks = totalMarks;
	}

	public boolean isShuffleQuestions() {
		return shuffleQuestions;
	}

	public void setShuffleQuestions(boolean shuffleQuestions) {
		this.shuffleQuestions = shuffleQuestions;
	}

	public boolean isShuffleAnswers() {
		return shuffleAnswers;
	}

	public void setShuffleAnswers(boolean shuffleAnswers) {
		this.shuffleAnswers = shuffleAnswers;
	}

	public boolean isEnrollAllStudents() {
		return enrollAllStudents;
	}

	public void setEnrollAllStudents(boolean enrollAllStudents) {
		this.enrollAllStudents = enrollAllStudents;
	}

	public boolean isAutoEnrollOnPayment() {
		return autoEnrollOnPayment;
	}

	public void setAutoEnrollOnPayment(boolean autoEnrollOnPayment) {
		this.autoEnrollOnPayment = autoEnrollOnPayment;
	}

	public String getPaymentCriteria() {
		return paymentCriteria;
	}

	public void setPaymentCriteria(String paymentCriteria) {
		this.paymentCriteria = paymentCriteria;
	}

	public boolean isShowAnswersWithResults() {
		return showAnswersWithResults;
	}

	public void setShowAnswersWithResults(boolean showAnswersWithResults) {
		this.showAnswersWithResults = showAnswersWithResults;
	}

	public UUID getQuestionPaperMediaId() {
		return questionPaperMediaId;
	}

	public void setQuestionPaperMediaId(UUID questionPaperMediaId) {
		this.questionPaperMediaId = questionPaperMediaId;
	}

	public UUID getMarkingSchemeMediaId() {
		return markingSchemeMediaId;
	}

	public void setMarkingSchemeMediaId(UUID markingSchemeMediaId) {
		this.markingSchemeMediaId = markingSchemeMediaId;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public UUID getDeletedBy() {
		return deletedBy;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public void markDeleted(UUID actorId, Instant at) {
		this.deletedAt = at;
		this.deletedBy = actorId;
	}

}

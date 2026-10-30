package com.lms.exammanagement.service.exam;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lms.exammanagement.domain.ExamAttemptStatus;
import com.lms.exammanagement.domain.ExamAttendance;
import com.lms.exammanagement.domain.ExamType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of Klass exam enrollment and student participation (contract {@code
 * docs/api/exam-management-v2.md} "Slice C"). Plain DTO records - never JPA entities. Before
 * release no student shape carries a correct flag, an explanation or the exam password: the
 * answer key appears only in {@link ResultItemView}, built only once answers are released.
 */
public final class StudentExamViews {

	private StudentExamViews() {
	}

	// ------------------------------------------------------------------
	// Staff enrollment.
	// ------------------------------------------------------------------

	public record EnrollResultView(int added, int alreadyEnrolled) {
	}

	public record SyncResultView(int added) {
	}

	/** First/last name are a whitespace split of the stored full name (students have one name field). */
	public record EnrollmentCandidateView(UUID studentId, String indexNumber, String firstName, String lastName) {
	}

	// ------------------------------------------------------------------
	// Student.
	// ------------------------------------------------------------------

	public record AttemptSummaryView(UUID id, ExamAttemptStatus status, Instant deadlineAt) {
	}

	public record StudentExamView(UUID id, ExamType type, String name, UUID courseId, String courseName,
			Instant startAt,
			Instant endAt, Integer durationMinutes, Integer questionCount, BigDecimal totalMarks,
			ExamWindowStatus status, boolean requiresPassword, AttemptSummaryView attempt, boolean resultAvailable) {
	}

	public record StudentExamListView(Instant serverNow, List<StudentExamView> upcoming,
			List<StudentExamView> inProgress, List<StudentExamView> previous) {
	}

	public record OptionView(UUID id, String html) {
	}

	public record AttemptItemView(UUID id, int sequence, String bodyHtml, BigDecimal marks, List<OptionView> options,
			UUID selectedOptionId, long revision) {
	}

	public record AttemptView(UUID id, UUID examId, ExamAttemptStatus status, Instant startedAt, Instant deadlineAt,
			Instant serverNow, List<AttemptItemView> items) {
	}

	public record SaveAnswerView(boolean saved, boolean stale, long revision, Instant serverNow) {
	}

	public record ResultItemView(int sequence, String bodyHtml, BigDecimal marks, BigDecimal awardedMarks,
			UUID selectedOptionId, UUID correctOptionId, String explanationHtml, List<OptionView> options) {
	}

	/**
	 * {@code items} is omitted unless answers are released (showAnswersWithResults and after endAt).
	 * WRITTEN / PHYSICAL (Slice D, released once results are published): {@code score} = the
	 * staff-entered marks (null if none), {@code attemptId} null for PHYSICAL, {@code markedPaper}
	 * a 10-minute signed link when a marked paper exists, {@code attendance} for PHYSICAL; both
	 * omitted when absent.
	 */
	public record ResultView(UUID examId, UUID attemptId, BigDecimal score, BigDecimal totalMarks,
			BigDecimal cutoffMarks, Boolean passed, Integer answeredCount,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<ResultItemView> items,
			@JsonInclude(JsonInclude.Include.NON_NULL) ExamViews.ExamDocumentLinkView markedPaper,
			@JsonInclude(JsonInclude.Include.NON_NULL) ExamAttendance attendance) {
	}

}

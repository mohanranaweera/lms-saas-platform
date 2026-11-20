package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.ExamType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of Klass exam configuration (contract {@code
 * docs/api/exam-management-v2.md} "Slice B"). Plain DTO records - never JPA entities. None
 * of them ever carries the exam password except {@link RevealedPasswordView}, and none
 * carries paper content or correct answers.
 */
public final class ExamViews {

	private ExamViews() {
	}

	public record PaymentCriteriaView(String kind, List<String> months, List<UUID> moduleIds, List<String> dates,
			BigDecimal minimumAmount) {
	}

	public record ModuleQuotaView(UUID moduleId, String moduleName, int questionCount) {
	}

	public record DocumentRefView(String fileName) {
	}

	public record ExamDetailView(UUID id, ExamType type, String name, UUID courseId, String courseName,
			Integer durationMinutes, Instant startAt, Instant endAt, Integer questionCount, BigDecimal cutoffMarks,
			List<ModuleQuotaView> moduleQuotas, boolean shuffleQuestions, boolean shuffleAnswers,
			boolean enrollAllStudents, boolean autoEnrollOnPayment, PaymentCriteriaView paymentCriteria,
			boolean showAnswersWithResults, BigDecimal totalMarks, UUID questionPaperMediaId,
			UUID markingSchemeMediaId, ExamWindowStatus status, boolean hasPassword, int paperQuestionCount,
			DocumentRefView questionPaper, DocumentRefView markingScheme, boolean locked, Instant createdAt,
			Instant updatedAt) {
	}

	public record ExamCardView(UUID id, ExamType type, ExamWindowStatus status, String name, UUID courseId,
			String courseName, Instant startAt, Instant endAt, Integer durationMinutes, Integer questionCount,
			BigDecimal totalMarks, long enrolled, long attended, long completed, long submitted) {
	}

	public record ExamDirectoryView(Instant serverNow, List<ExamCardView> inProgress, List<ExamCardView> upcoming,
			List<ExamCardView> previous) {
	}

	/** {@code paymentCriteriaKind == null}: auto-enroll on payment is unsupported (FREE / CUSTOM). */
	public record CourseExamConfigView(UUID courseId, String pricingModel, String paymentCriteriaKind) {
	}

	public record RevealedPasswordView(String password) {

		@Override
		public String toString() {
			return "RevealedPasswordView[password=***]";
		}

	}

	public record ExamDocumentLinkView(String url, String fileName) {
	}

	public record UploadedExamDocumentView(UUID mediaId, String fileName) {
	}

}

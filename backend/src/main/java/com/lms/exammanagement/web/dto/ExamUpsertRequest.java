package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.service.exam.ExamConfigInput;
import com.lms.exammanagement.service.exam.ExamConfigInput.PaymentCriteriaInput;
import com.lms.exammanagement.service.exam.ExamConfigInput.QuotaInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/exams} and {@code PUT /api/v1/exams/{id}} for Klass exams (contract
 * "Slice B" {@code ExamUpsertRequest}). Every field rule is per exam type, so validation
 * happens in {@code ExamConfigService} (400 with field errors), not with bean-validation
 * annotations here. {@code toString} never prints the password.
 */
public record ExamUpsertRequest(String type, String name, UUID courseId, String password, Integer durationMinutes,
		Instant startAt, Instant endAt, Integer questionCount, BigDecimal cutoffMarks,
		List<ModuleQuotaRequest> moduleQuotas, Boolean shuffleQuestions, Boolean shuffleAnswers,
		Boolean enrollAllStudents, Boolean autoEnrollOnPayment, PaymentCriteriaRequest paymentCriteria,
		Boolean showAnswersWithResults, BigDecimal totalMarks, UUID questionPaperMediaId,
		UUID markingSchemeMediaId) {

	public record ModuleQuotaRequest(UUID moduleId, Integer questionCount) {
	}

	public record PaymentCriteriaRequest(String kind, List<String> months, List<UUID> moduleIds, List<String> dates,
			BigDecimal minimumAmount) {
	}

	public ExamConfigInput toInput() {
		List<QuotaInput> quotas = moduleQuotas == null ? null
				: moduleQuotas.stream()
					.map(quota -> quota == null ? null : new QuotaInput(quota.moduleId(), quota.questionCount()))
					.toList();
		PaymentCriteriaInput criteria = paymentCriteria == null ? null
				: new PaymentCriteriaInput(paymentCriteria.kind(), paymentCriteria.months(), paymentCriteria.moduleIds(),
						paymentCriteria.dates(), paymentCriteria.minimumAmount());
		return new ExamConfigInput(type, name, courseId, password, durationMinutes, startAt, endAt, questionCount,
				cutoffMarks, quotas, shuffleQuestions, shuffleAnswers, enrollAllStudents, autoEnrollOnPayment, criteria,
				showAnswersWithResults, totalMarks, questionPaperMediaId, markingSchemeMediaId);
	}

	@Override
	public String toString() {
		return "ExamUpsertRequest[type=" + type + ", name=" + name + ", courseId=" + courseId + ", password="
				+ (password == null ? "null" : "***") + "]";
	}

}

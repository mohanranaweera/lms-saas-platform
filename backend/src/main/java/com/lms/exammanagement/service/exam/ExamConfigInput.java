package com.lms.exammanagement.service.exam;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The create/update request of a Klass exam as received (contract {@code ExamUpsertRequest}),
 * before any validation - every field may be null. {@code toString} never prints the
 * password.
 */
public record ExamConfigInput(String type, String name, UUID courseId, String password, Integer durationMinutes,
		Instant startAt, Instant endAt, Integer questionCount, BigDecimal cutoffMarks, List<QuotaInput> moduleQuotas,
		Boolean shuffleQuestions, Boolean shuffleAnswers, Boolean enrollAllStudents, Boolean autoEnrollOnPayment,
		PaymentCriteriaInput paymentCriteria, Boolean showAnswersWithResults, BigDecimal totalMarks,
		UUID questionPaperMediaId, UUID markingSchemeMediaId) {

	public record QuotaInput(UUID moduleId, Integer questionCount) {
	}

	public record PaymentCriteriaInput(String kind, List<String> months, List<UUID> moduleIds, List<String> dates,
			BigDecimal minimumAmount) {
	}

	@Override
	public String toString() {
		return "ExamConfigInput[type=" + type + ", name=" + name + ", courseId=" + courseId + ", password="
				+ (password == null ? "null" : "***") + "]";
	}

}

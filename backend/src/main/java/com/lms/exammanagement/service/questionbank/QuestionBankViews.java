package com.lms.exammanagement.service.questionbank;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of the module question bank (contract {@code docs/api/exam-management-v2.md}
 * "Slice A"). Plain DTO records - never JPA entities. Staff-only: {@code correct} and the
 * explanation are part of the authoring view, never of any student payload.
 */
public final class QuestionBankViews {

	private QuestionBankViews() {
	}

	public record QuestionOptionView(UUID id, int sequence, String html, boolean correct) {
	}

	public record QuestionView(UUID id, UUID courseId, UUID moduleId, int sequence, String bodyHtml,
			BigDecimal marks, String explanationHtml, List<QuestionOptionView> options, UUID importId,
			Instant createdAt, Instant updatedAt) {
	}

	/** {@code minMarks}/{@code maxMarks}: lowest/highest live question marks (Slice B); null for an empty module. */
	public record ModuleQuestionCountView(UUID moduleId, String moduleName, long questionCount,
			BigDecimal totalMarks, BigDecimal minMarks, BigDecimal maxMarks) {
	}

	public record CopyResultView(int copied) {
	}

	public record ExamMediaView(UUID mediaId, String url) {
	}

	public record QuestionImportView(UUID id, String fileName, Instant importedAt, int questionCount,
			String importedByName) {
	}

	/** {@code replayed} = an earlier import with the same Idempotency-Key was returned (200, not 201). */
	public record ImportOutcome(QuestionImportView importView, boolean replayed) {
	}

	/** One CSV problem; {@code row} is the 1-based CSV record number (header = 1), null for file-level problems. */
	public record ImportError(Integer row, String column, String message) {
	}

}

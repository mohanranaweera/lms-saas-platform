package com.lms.exammanagement.service.exam;

import com.lms.common.api.PageResponse;
import com.lms.exammanagement.domain.ExamAttemptStatus;
import com.lms.exammanagement.domain.ExamAttendance;
import com.lms.exammanagement.domain.ExamEnrollmentSource;
import com.lms.exammanagement.domain.ExamResultLabel;
import com.lms.exammanagement.domain.ExamSubmissionUploaderRole;
import com.lms.exammanagement.service.exam.ExamViews.DocumentRefView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDetailView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes of the Klass exam management page, marking and written documents (contract
 * {@code docs/api/exam-management-v2.md} "Slice D"). Plain DTO records - never JPA entities,
 * never the exam password, the marking scheme or a storage key. Index numbers are strings
 * (leading zeros preserved).
 */
public final class ExamManagementViews {

	private ExamManagementViews() {
	}

	/** Percentages are of {@code enrolled}, one decimal, 0 when nobody is enrolled. */
	public record ManagementSummaryView(long enrolled, long attended, BigDecimal attendedPercent, long completed,
			BigDecimal completedPercent, long submitted, BigDecimal submittedPercent) {
	}

	public record DocumentItemView(UUID id, String fileName) {
	}

	public record ManagedStudentRowView(UUID studentId, String indexNumber, String firstName, String lastName,
			ExamResultLabel result, BigDecimal marks, ExamAttemptStatus attemptStatus, Integer answeredCount,
			List<DocumentItemView> answerDocuments, DocumentRefView markedPaper, ExamAttendance attendance,
			ExamEnrollmentSource enrollmentSource) {
	}

	public record ExamManagementView(Instant serverNow, Instant resultsPublishedAt, ExamDetailView exam,
			ManagementSummaryView summary, PageResponse<ManagedStudentRowView> students) {
	}

	/** A student's own answer document ({@code GET /student/exams/{id}/my-documents}). */
	public record StudentDocumentView(UUID id, String fileName, Instant uploadedAt,
			ExamSubmissionUploaderRole uploadedByRole) {
	}

	/** CSV export payload. */
	public record CsvExport(String fileName, byte[] content) {
	}

}

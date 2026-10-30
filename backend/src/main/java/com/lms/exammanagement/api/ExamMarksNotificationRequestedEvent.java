package com.lms.exammanagement.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the Klass "Notify Marks" send (contract "Slice E") inside the same transaction
 * that records the {@code exam_notification_dispatch} row. {@code notification-management}
 * consumes it synchronously, in that transaction, writing one {@code notification_outbox} row per
 * recipient - so the dispatch record, the audit entry and the queued notifications commit
 * together or not at all. {@code tenantId} is the resolved tenant of the sending request.
 *
 * <p>Recipient values are display-ready strings; they never contain answer keys, options or
 * explanations.
 */
public record ExamMarksNotificationRequestedEvent(UUID tenantId, UUID examId, UUID dispatchId, Instant requestedAt,
		List<Recipient> recipients) {

	public ExamMarksNotificationRequestedEvent {
		recipients = List.copyOf(recipients);
	}

	/**
	 * @param result {@code "Passed"} / {@code "Not passed"} when the exam has a cutoff, else {@code null}.
	 */
	public record Recipient(UUID studentId, String examName, String courseName, String marks, String totalMarks,
			String result) {
	}

}

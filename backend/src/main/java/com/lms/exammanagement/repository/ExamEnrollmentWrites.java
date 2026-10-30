package com.lms.exammanagement.repository;

import com.lms.exammanagement.domain.ExamEnrollmentSource;
import java.time.Instant;
import java.util.UUID;

/** Race-safe exam enrollment insert (ADR-032: idempotent via the unique (tenant, exam, student) key). */
public interface ExamEnrollmentWrites {

	/**
	 * {@code INSERT ... ON CONFLICT (tenant_id, exam_id, student_id) DO NOTHING} in the resolved
	 * context tenant. {@code paymentId}/{@code freeCardId}: the evidence of a {@code PAYMENT}
	 * enrollment (exactly one), both {@code null} otherwise.
	 * @return {@code 1} if inserted, {@code 0} if the student was already enrolled.
	 */
	int insertIfAbsent(UUID examId, UUID studentId, ExamEnrollmentSource source, UUID paymentId, UUID freeCardId,
			UUID actorId, Instant now);

}

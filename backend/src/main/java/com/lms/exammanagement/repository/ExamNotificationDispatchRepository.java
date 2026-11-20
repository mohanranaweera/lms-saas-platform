package com.lms.exammanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.exammanagement.domain.ExamNotificationDispatch;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Tenant-scoped, append-only "Notify Marks" dispatch log (V115). */
public interface ExamNotificationDispatchRepository
		extends AppendOnlyTenantAwareRepository<ExamNotificationDispatch, UUID> {

	default Optional<ExamNotificationDispatch> findByExamIdAndIdempotencyKey(UUID examId, String idempotencyKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("examId"), examId),
				cb.equal(root.get("idempotencyKey"), idempotencyKey)));
	}

	/** The exam's most recent send, if any. */
	default Optional<ExamNotificationDispatch> findLatestByExamId(UUID examId) {
		return findAll((root, query, cb) -> cb.equal(root.get("examId"), examId),
				PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "sentAt").and(Sort.by(Sort.Direction.DESC, "id"))))
			.stream()
			.findFirst();
	}

}

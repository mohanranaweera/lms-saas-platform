package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal collaborator of {@link ExamLifecycleService} - NOT exposed via
 * this module's {@code api} package, never injected by another domain
 * (package-private, mirroring {@code
 * enrollmentmanagement.service.EnrollmentExpiryEventWriter}'s established
 * convention for a single-purpose collaborator).
 *
 * <h2>Why this method runs in its OWN {@code Propagation.REQUIRES_NEW} transaction</h2>
 * {@link ExamLifecycleService#resolveCurrentStatus} is called mid-request by
 * access-check callers (chief among them {@code
 * ExamAttemptService#requireAttemptableWindow}) that immediately throw a
 * business exception in the SAME transactional method whenever the resolved
 * status means the request should be rejected (exam not yet open / window
 * closed) - precisely the scenario that most needs the advanced status to
 * survive. If the status write happened inline, in the caller's own ambient
 * transaction, Spring's default rollback-on-unchecked-exception behavior
 * would roll it back right along with the exception that was correctly
 * thrown, permanently freezing {@code exam.status} at a stale value even
 * though every access decision computed from it was correct on every single
 * request (the live value is always recomputed from {@code scheduled_start}/
 * {@code scheduled_end} vs. the clock, never trusted from the stale column).
 * {@code Propagation.REQUIRES_NEW} opens a genuinely separate physical
 * transaction (suspending, not sharing, the caller's) that commits on
 * successful return from this method, regardless of what the caller's own
 * suspended-then-resumed transaction goes on to do (including rolling back
 * on a thrown business exception).
 *
 * <p>Unlike {@code EnrollmentExpiryEventWriter} - which documents a real,
 * observed concurrent-race failure mode requiring the caller to catch a
 * translated {@code DataIntegrityViolationException} - this write has no
 * unique-constraint race to guard against (there is no append-only insert
 * here, just a forward-only status update keyed by primary key). The one
 * race worth guarding against is a concurrent request having already
 * advanced (and committed) the row further than what THIS call computed from
 * an earlier read of a possibly-stale in-memory {@code Exam}; {@link
 * #isForwardAdvance} guards against ever regressing an already-more-advanced
 * persisted status.
 */
@Service
class ExamStatusAdvanceWriter {

	private final ExamRepository examRepository;

	ExamStatusAdvanceWriter(ExamRepository examRepository) {
		this.examRepository = examRepository;
	}

	/**
	 * Re-loads {@code exam} fresh, by id (tenant-scoped, via {@link
	 * ExamRepository#findById}), rather than reusing any managed reference
	 * from the caller's outer persistence context - that reference belongs
	 * to a different transaction/persistence context than this method's own
	 * {@code REQUIRES_NEW} one and must not be reused across the boundary.
	 * A missing/cross-tenant {@code examId} is silently a no-op: by the time
	 * this runs the caller has already tenant-scoped-loaded the same row
	 * successfully, so a miss here would only mean a benign concurrent
	 * deletion (exams are never hard-deleted in current scope) - there is no
	 * outstanding business exception to raise from this purely best-effort
	 * write.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void advanceStatus(UUID examId, ExamStatus targetStatus) {
		examRepository.findById(examId).ifPresent(exam -> {
			if (isForwardAdvance(exam.getStatus(), targetStatus)) {
				exam.setStatus(targetStatus);
				examRepository.save(exam);
			}
		});
	}

	/**
	 * @return {@code true} only if {@code targetStatus} is strictly later in
	 * the fixed {@code DRAFT -> SCHEDULED -> PUBLISHED -> CLOSED} progression
	 * (declaration order of {@link ExamStatus}) than {@code currentStatus} -
	 * never {@code true} for an equal or earlier target, so an
	 * already-more-advanced persisted row (e.g. a concurrent request that
	 * already committed {@code CLOSED}) can never be regressed back to an
	 * earlier value (e.g. {@code PUBLISHED}) by a call computed from a
	 * stale read.
	 */
	private static boolean isForwardAdvance(ExamStatus currentStatus, ExamStatus targetStatus) {
		return targetStatus.ordinal() > currentStatus.ordinal();
	}

}

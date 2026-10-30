package com.lms.exammanagement.repository;

import com.lms.exammanagement.domain.ExamAttempt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Klass attempt operations that need explicit SQL: the per-attempt row lock serializing save /
 * submit / sweep finalization, grading against the frozen paper, the revision-guarded answer
 * save, and the cross-tenant deadline sweep lookup. Every method except the {@code
 * *AcrossTenants} ones is bound to the resolved {@code TenantContext}.
 */
public interface ExamAttemptWrites {

	/**
	 * Re-reads {@code attempt} from the database with {@code SELECT ... FOR UPDATE} (refreshing
	 * any stale in-memory state). The attempt must belong to the context tenant.
	 */
	void lockAndRefresh(ExamAttempt attempt);

	/**
	 * Grades every not-yet-graded item of the attempt, by option id against the frozen paper:
	 * the item's marks if the selected option is the paper's correct option, else 0 (unanswered =
	 * 0, no negative or partial marks).
	 * @return the total score and the number of answered items.
	 */
	Grade gradeItems(UUID attemptId, Instant gradedAt);

	/**
	 * Revision-guarded answer save: applied only when {@code revision} is greater than the stored
	 * revision and the item is not graded. The caller holds the attempt's row lock.
	 * @return {@code 1} if applied, {@code 0} if stale.
	 */
	int saveAnswer(UUID attemptId, UUID itemId, UUID optionId, long revision, Instant now);

	/** Stored revision of an item of the attempt. */
	long currentRevision(UUID attemptId, UUID itemId);

	/**
	 * CROSS-TENANT (scheduled sweep only): open Klass attempts whose deadline has passed, oldest
	 * first. Deliberately named so the bypass stays visible (ADR-006).
	 */
	List<OverdueAttempt> findOverdueKlassAttemptsAcrossTenants(Instant now, int limit);

	/** CROSS-TENANT (scheduled sweep only): prunes password failures older than {@code before}. */
	int prunePasswordFailuresAcrossTenants(Instant before);

	record Grade(BigDecimal score, int answeredCount) {
	}

	record OverdueAttempt(UUID tenantId, UUID attemptId) {
	}

}

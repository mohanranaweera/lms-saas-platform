package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamFinalizedReason;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamAttemptWrites.Grade;
import com.lms.exammanagement.repository.ExamRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single Klass attempt finalization path (D2): submit, lazy finalization on any access and
 * the scheduled sweep all end here. Exactly-once: the attempt row is locked ({@code SELECT ...
 * FOR UPDATE}, refreshed) and re-checked {@code IN_PROGRESS} before grading, so a concurrent
 * submit, save and sweep grade it once; the database additionally forbids changing a finalized
 * attempt or a graded item.
 */
@Component
public class ExamAttemptFinalizer {

	private static final Logger log = LoggerFactory.getLogger(ExamAttemptFinalizer.class);

	private final ExamAttemptRepository attemptRepository;

	private final ExamRepository examRepository;

	public ExamAttemptFinalizer(ExamAttemptRepository attemptRepository, ExamRepository examRepository) {
		this.attemptRepository = attemptRepository;
		this.examRepository = examRepository;
	}

	/**
	 * Finalizes {@code attempt} as TIMEOUT when its deadline has passed. Takes the row lock only
	 * when the attempt looks due.
	 * @return {@code true} if this call finalized it.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean finalizeIfDue(ExamAttempt attempt, Instant now) {
		if (!attempt.isKlassAttempt() || attempt.isFinalized() || now.isBefore(attempt.getDeadlineAt())) {
			return false;
		}
		attemptRepository.lockAndRefresh(attempt);
		if (attempt.isFinalized()) {
			return false;
		}
		finalizeLocked(attempt, ExamFinalizedReason.TIMEOUT, now);
		return true;
	}

	/**
	 * Student submit (idempotent): locks, and finalizes as SUBMITTED - or TIMEOUT if the deadline
	 * has already passed. A finalized attempt is returned unchanged.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void submit(ExamAttempt attempt, Instant now) {
		attemptRepository.lockAndRefresh(attempt);
		if (attempt.isFinalized()) {
			return;
		}
		finalizeLocked(attempt,
				now.isBefore(attempt.getDeadlineAt()) ? ExamFinalizedReason.SUBMITTED : ExamFinalizedReason.TIMEOUT, now);
	}

	/**
	 * Scheduled sweep: one overdue attempt in its own transaction (the caller has set the
	 * attempt's tenant on the thread).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean sweepOne(UUID attemptId, Instant now) {
		return attemptRepository.findById(attemptId).map(attempt -> finalizeIfDue(attempt, now)).orElse(false);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int prunePasswordFailures(Instant before) {
		return attemptRepository.prunePasswordFailuresAcrossTenants(before);
	}

	/**
	 * Caller holds the row lock and has seen {@code IN_PROGRESS}. A WRITTEN exam's attempt (paper
	 * opened, Slice D) has no items and is never auto-graded: it is closed with no score - its
	 * marks are entered by staff on {@code exam_result}.
	 */
	private void finalizeLocked(ExamAttempt attempt, ExamFinalizedReason reason, Instant now) {
		Instant finalizedAt = reason == ExamFinalizedReason.TIMEOUT && attempt.getDeadlineAt().isBefore(now)
				? attempt.getDeadlineAt() : now;
		boolean written = examRepository.findById(attempt.getExamId())
			.map(exam -> exam.getExamType() == ExamType.WRITTEN)
			.orElse(false);
		if (written) {
			attempt.finalizeUngraded(reason, finalizedAt);
		}
		else {
			Grade grade = attemptRepository.gradeItems(attempt.getId(), now);
			attempt.finalizeKlass(reason, finalizedAt, grade.score(), grade.answeredCount(), now);
		}
		attemptRepository.saveAndFlush(attempt);
		log.atInfo()
			.setMessage("exam.attempt.finalized")
			.addKeyValue("examTenantId", attempt.getTenantId())
			.addKeyValue("examId", attempt.getExamId())
			.addKeyValue("attemptId", attempt.getId())
			.addKeyValue("reason", reason)
			.log();
	}

}

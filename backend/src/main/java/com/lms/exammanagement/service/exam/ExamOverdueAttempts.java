package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamStatsQueries;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finalizes (D2, through the single {@link ExamAttemptFinalizer} path) the overdue open attempts
 * of one exam before Slice E reads scores, so stats, Notify Marks and leaderboards never miss an
 * attempt the sweep has not reached yet. Loads only the overdue attempts.
 */
@Component
public class ExamOverdueAttempts {

	private final ExamStatsQueries statsQueries;

	private final ExamAttemptRepository attemptRepository;

	private final ExamAttemptFinalizer finalizer;

	public ExamOverdueAttempts(ExamStatsQueries statsQueries, ExamAttemptRepository attemptRepository,
			ExamAttemptFinalizer finalizer) {
		this.statsQueries = statsQueries;
		this.attemptRepository = attemptRepository;
		this.finalizer = finalizer;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void finalizeOverdue(Exam exam, Instant now) {
		for (UUID attemptId : statsQueries.overdueOpenAttemptIds(exam.getId(), now)) {
			attemptRepository.findById(attemptId).ifPresent(attempt -> finalizer.finalizeIfDue(attempt, now));
		}
	}

}

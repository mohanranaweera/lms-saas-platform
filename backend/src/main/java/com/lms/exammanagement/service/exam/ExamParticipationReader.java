package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single seam through which Klass exam configuration reads participation facts.
 *
 * <p>{@link #hasParticipation} = any {@code exam_attempt} row exists (Slice D adds written
 * submissions and physical results here); it locks the paper and structural fields and
 * blocks deleting the exam.
 *
 * <p>{@link #countsFor} backs the directory cards, batched by exam id and tenant-scoped:
 * {@code enrolled} = {@code exam_enrollment} rows (Slice C), {@code attended} = Klass attempts,
 * {@code completed} = finalized Klass attempts with at least one answer. {@code submitted}
 * (WRITTEN answer sheets) stays 0 until Slice D.
 */
@Component
public class ExamParticipationReader {

	private final ExamAttemptRepository attemptRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	public ExamParticipationReader(ExamAttemptRepository attemptRepository,
			ExamEnrollmentRepository enrollmentRepository) {
		this.attemptRepository = attemptRepository;
		this.enrollmentRepository = enrollmentRepository;
	}

	public boolean hasParticipation(UUID examId) {
		return attemptRepository.existsByExamId(examId);
	}

	public Map<UUID, Counts> countsFor(Collection<UUID> examIds) {
		Map<UUID, long[]> raw = new HashMap<>();
		for (UUID examId : examIds) {
			raw.put(examId, new long[4]);
		}
		if (!examIds.isEmpty()) {
			List<UUID> ids = List.copyOf(examIds);
			for (UUID examId : enrollmentRepository.findExamIdsIn(ids)) {
				raw.get(examId)[0]++;
			}
			for (ExamAttempt attempt : attemptRepository.findKlassAttemptsOfExams(ids)) {
				long[] counts = raw.get(attempt.getExamId());
				counts[1]++;
				if (attempt.isFinalized() && attempt.getAnsweredCount() != null && attempt.getAnsweredCount() > 0) {
					counts[2]++;
				}
			}
		}
		Map<UUID, Counts> result = new HashMap<>();
		raw.forEach((examId, c) -> result.put(examId, new Counts(c[0], c[1], c[2], c[3])));
		return result;
	}

	/** {@code completed}: MCQ finalized with at least one answer; {@code submitted}: WRITTEN answer sheet uploaded. */
	public record Counts(long enrolled, long attended, long completed, long submitted) {

		public static final Counts ZERO = new Counts(0, 0, 0, 0);

	}

}

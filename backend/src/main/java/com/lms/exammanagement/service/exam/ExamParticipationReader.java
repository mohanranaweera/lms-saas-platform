package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.repository.ExamAttemptRepository;
import java.util.Collection;
import java.util.HashMap;
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
 * <p>{@link #countsFor} backs the directory cards. Enrollment (Slice C {@code
 * exam_enrollment}) and submissions/results (Slice D) do not exist yet, so every count is 0
 * until those slices fill this method with one grouped, tenant-scoped query per source
 * table keyed by exam id - callers already batch by exam id.
 */
@Component
public class ExamParticipationReader {

	private final ExamAttemptRepository attemptRepository;

	public ExamParticipationReader(ExamAttemptRepository attemptRepository) {
		this.attemptRepository = attemptRepository;
	}

	public boolean hasParticipation(UUID examId) {
		return attemptRepository.existsByExamId(examId);
	}

	public Map<UUID, Counts> countsFor(Collection<UUID> examIds) {
		Map<UUID, Counts> counts = new HashMap<>();
		for (UUID examId : examIds) {
			counts.put(examId, Counts.ZERO);
		}
		return counts;
	}

	/** {@code completed}: MCQ finalized with at least one answer; {@code submitted}: WRITTEN answer sheet uploaded. */
	public record Counts(long enrolled, long attended, long completed, long submitted) {

		public static final Counts ZERO = new Counts(0, 0, 0, 0);

	}

}

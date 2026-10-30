package com.lms.exammanagement.service.exam;

import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttendance;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamResult;
import com.lms.exammanagement.domain.ExamSubmissionDocument;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamResultRepository;
import com.lms.exammanagement.repository.ExamSubmissionDocumentRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single seam through which Klass exam configuration, the directory and the management page
 * read participation facts (contract "Slice D": attended / completed / submitted are never
 * collapsed).
 *
 * <p>{@link #hasParticipation} = any {@code exam_attempt}, {@code exam_result} or {@code
 * exam_submission_document} row exists; it locks the paper and structural fields and blocks
 * deleting the exam. {@link #hasStudentParticipation} is the same per student and blocks removing
 * that student's enrollment.
 *
 * <p>{@link #countsFor} backs the directory cards and the management summary with the exact
 * per-student rules of {@link #attended}, {@link #completed} and {@link #submitted}, so card,
 * summary and table rows always reconcile:
 * <ul>
 * <li>{@code enrolled} = {@code exam_enrollment} rows;</li>
 * <li>{@code attended}: MCQ - an attempt exists; WRITTEN - the paper was opened (attempt) or an
 * answer sheet exists; PHYSICAL - attendance PRESENT;</li>
 * <li>{@code completed}: MCQ - finalized with at least one answer (0 for other types);</li>
 * <li>{@code submitted}: WRITTEN - at least one live answer document (0 for other types).</li>
 * </ul>
 */
@Component
public class ExamParticipationReader {

	private final ExamAttemptRepository attemptRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamResultRepository resultRepository;

	private final ExamSubmissionDocumentRepository documentRepository;

	public ExamParticipationReader(ExamAttemptRepository attemptRepository,
			ExamEnrollmentRepository enrollmentRepository, ExamResultRepository resultRepository,
			ExamSubmissionDocumentRepository documentRepository) {
		this.attemptRepository = attemptRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.resultRepository = resultRepository;
		this.documentRepository = documentRepository;
	}

	public boolean hasParticipation(UUID examId) {
		return attemptRepository.existsByExamId(examId) || resultRepository.existsByExamId(examId)
				|| documentRepository.existsByExamId(examId);
	}

	public boolean hasStudentParticipation(UUID examId, UUID studentId) {
		return attemptRepository.existsByExamIdAndStudentId(examId, studentId)
				|| resultRepository.existsByExamIdAndStudentId(examId, studentId)
				|| documentRepository.existsByExamIdAndStudentId(examId, studentId);
	}

	public Map<UUID, Counts> countsFor(Collection<Exam> exams) {
		Map<UUID, Counts> result = new HashMap<>();
		if (exams.isEmpty()) {
			return result;
		}
		List<UUID> ids = exams.stream().map(Exam::getId).toList();
		Map<UUID, Long> enrolled = new HashMap<>();
		for (UUID examId : enrollmentRepository.findExamIdsIn(ids)) {
			enrolled.merge(examId, 1L, Long::sum);
		}
		Map<UUID, Map<UUID, ExamAttempt>> attempts = new HashMap<>();
		for (ExamAttempt attempt : attemptRepository.findKlassAttemptsOfExams(ids)) {
			attempts.computeIfAbsent(attempt.getExamId(), id -> new HashMap<>()).put(attempt.getStudentId(), attempt);
		}
		Map<UUID, Map<UUID, ExamResult>> results = new HashMap<>();
		for (ExamResult row : resultRepository.findAllByExamIdIn(ids)) {
			results.computeIfAbsent(row.getExamId(), id -> new HashMap<>()).put(row.getStudentId(), row);
		}
		Map<UUID, Set<UUID>> documents = new HashMap<>();
		for (ExamSubmissionDocument document : documentRepository.findLiveByExamIdIn(ids)) {
			documents.computeIfAbsent(document.getExamId(), id -> new HashSet<>()).add(document.getStudentId());
		}
		for (Exam exam : exams) {
			Map<UUID, ExamAttempt> examAttempts = attempts.getOrDefault(exam.getId(), Map.of());
			Map<UUID, ExamResult> examResults = results.getOrDefault(exam.getId(), Map.of());
			Set<UUID> withDocuments = documents.getOrDefault(exam.getId(), Set.of());
			Set<UUID> students = new HashSet<>(examAttempts.keySet());
			students.addAll(examResults.keySet());
			students.addAll(withDocuments);
			long attended = 0;
			long completed = 0;
			long submitted = 0;
			for (UUID studentId : students) {
				ExamAttempt attempt = examAttempts.get(studentId);
				ExamResult row = examResults.get(studentId);
				boolean hasDocuments = withDocuments.contains(studentId);
				if (attended(exam.getExamType(), attempt, row, hasDocuments)) {
					attended++;
				}
				if (completed(exam.getExamType(), attempt)) {
					completed++;
				}
				if (submitted(exam.getExamType(), hasDocuments)) {
					submitted++;
				}
			}
			result.put(exam.getId(), new Counts(enrolled.getOrDefault(exam.getId(), 0L), attended, completed, submitted));
		}
		return result;
	}

	/**
	 * Slice E: per enrolled student of each exam - {@code attended} (the same rule as {@link
	 * #countsFor}) and the student's {@code marks} when graded (MCQ: finalized attempt with a
	 * score; WRITTEN / PHYSICAL: marks entered), else {@code null}. Four queries for any number
	 * of exams; the caller finalizes overdue attempts first when it needs current MCQ scores.
	 */
	public Map<UUID, List<StudentFacts>> studentFacts(Collection<Exam> exams) {
		Map<UUID, List<StudentFacts>> result = new HashMap<>();
		if (exams.isEmpty()) {
			return result;
		}
		List<UUID> ids = exams.stream().map(Exam::getId).toList();
		Map<UUID, Map<UUID, ExamAttempt>> attempts = new HashMap<>();
		for (ExamAttempt attempt : attemptRepository.findKlassAttemptsOfExams(ids)) {
			attempts.computeIfAbsent(attempt.getExamId(), id -> new HashMap<>()).put(attempt.getStudentId(), attempt);
		}
		Map<UUID, Map<UUID, ExamResult>> results = new HashMap<>();
		for (ExamResult row : resultRepository.findAllByExamIdIn(ids)) {
			results.computeIfAbsent(row.getExamId(), id -> new HashMap<>()).put(row.getStudentId(), row);
		}
		Map<UUID, Set<UUID>> documents = new HashMap<>();
		for (ExamSubmissionDocument document : documentRepository.findLiveByExamIdIn(ids)) {
			documents.computeIfAbsent(document.getExamId(), id -> new HashSet<>()).add(document.getStudentId());
		}
		Map<UUID, Exam> byId = new HashMap<>();
		exams.forEach(exam -> byId.put(exam.getId(), exam));
		for (ExamEnrollment enrollment : enrollmentRepository.findAllByExamIdIn(ids)) {
			Exam exam = byId.get(enrollment.getExamId());
			UUID studentId = enrollment.getStudentId();
			ExamAttempt attempt = attempts.getOrDefault(exam.getId(), Map.of()).get(studentId);
			ExamResult row = results.getOrDefault(exam.getId(), Map.of()).get(studentId);
			boolean hasDocuments = documents.getOrDefault(exam.getId(), Set.of()).contains(studentId);
			result.computeIfAbsent(exam.getId(), id -> new ArrayList<>())
				.add(new StudentFacts(studentId, attended(exam.getExamType(), attempt, row, hasDocuments),
						marks(exam.getExamType(), attempt, row)));
		}
		return result;
	}

	/** The student's marks when graded, else {@code null} (MCQ: finalized + scored; else entered marks). */
	static BigDecimal marks(ExamType type, ExamAttempt attempt, ExamResult result) {
		if (type == ExamType.MCQ) {
			return attempt != null && attempt.isFinalized() ? attempt.getScore() : null;
		}
		return result == null ? null : result.getMarks();
	}

	// ------------------------------------------------------------------
	// Per-student facts (shared with the management rows).
	// ------------------------------------------------------------------

	static boolean attended(ExamType type, ExamAttempt attempt, ExamResult result, boolean hasDocuments) {
		return switch (type) {
			case MCQ -> attempt != null;
			case WRITTEN -> attempt != null || hasDocuments;
			case PHYSICAL -> result != null && result.getAttendance() == ExamAttendance.PRESENT;
		};
	}

	static boolean completed(ExamType type, ExamAttempt attempt) {
		return type == ExamType.MCQ && attempt != null && attempt.isFinalized() && attempt.getAnsweredCount() != null
				&& attempt.getAnsweredCount() > 0;
	}

	static boolean submitted(ExamType type, boolean hasDocuments) {
		return type == ExamType.WRITTEN && hasDocuments;
	}

	/** One enrolled student's Slice E facts: attended, and marks when graded ({@code null} otherwise). */
	public record StudentFacts(UUID studentId, boolean attended, BigDecimal marks) {

		public boolean graded() {
			return marks != null;
		}

	}

	/** {@code completed}: MCQ finalized with at least one answer; {@code submitted}: WRITTEN answer sheet uploaded. */
	public record Counts(long enrolled, long attended, long completed, long submitted) {

		public static final Counts ZERO = new Counts(0, 0, 0, 0);

	}

}

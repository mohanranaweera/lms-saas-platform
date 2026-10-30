package com.lms.exammanagement.service.exam;

import com.lms.common.api.PageResponse;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.StoredDocument;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamAttendance;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamResult;
import com.lms.exammanagement.domain.ExamResultLabel;
import com.lms.exammanagement.domain.ExamSubmissionDocument;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamResultRepository;
import com.lms.exammanagement.repository.ExamSubmissionDocumentRepository;
import com.lms.exammanagement.service.exam.ExamManagementViews.CsvExport;
import com.lms.exammanagement.service.exam.ExamManagementViews.DocumentItemView;
import com.lms.exammanagement.service.exam.ExamManagementViews.ExamManagementView;
import com.lms.exammanagement.service.exam.ExamManagementViews.ManagedStudentRowView;
import com.lms.exammanagement.service.exam.ExamManagementViews.ManagementSummaryView;
import com.lms.exammanagement.service.exam.ExamParticipationReader.Counts;
import com.lms.exammanagement.service.exam.ExamViews.DocumentRefView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Klass exam management page and CSV export (contract "Slice D"): summary cards, the enrolled
 * students table with its derived {@link ExamResultLabel}, search by index number / first / last
 * name, pagination, and the full filtered export.
 *
 * <p>Authorization: the exam's course authoring access with {@code VIEW} ({@link
 * ExamAccessGuard}: owning teacher, teacher assistant, staff with {@code DomainArea.EXAMS});
 * students 403, another teacher's course 403, another tenant's exam 404.
 *
 * <p>Before counting or listing, "Enroll all" participants are materialized (D4) and overdue
 * attempts are finalized (D2), so the summary, the rows and the directory card (same {@link
 * ExamParticipationReader} rules) reconcile. Not read-only for that reason.
 */
@Service
@Transactional
public class ExamManagementService {

	static final int DEFAULT_PAGE_SIZE = 10;

	static final int MAX_PAGE_SIZE = 100;

	private final ExamConfigService configService;

	private final ExamEnrollmentService enrollmentService;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamAttemptRepository attemptRepository;

	private final ExamResultRepository resultRepository;

	private final ExamSubmissionDocumentRepository documentRepository;

	private final ExamAttemptFinalizer finalizer;

	private final ExamParticipationReader participationReader;

	private final ExamDocumentMediaApi documentApi;

	private final StudentLookupApi studentLookupApi;

	private final ExamAccessGuard accessGuard;

	private final Clock clock;

	public ExamManagementService(ExamConfigService configService, ExamEnrollmentService enrollmentService,
			ExamEnrollmentRepository enrollmentRepository, ExamAttemptRepository attemptRepository,
			ExamResultRepository resultRepository, ExamSubmissionDocumentRepository documentRepository,
			ExamAttemptFinalizer finalizer, ExamParticipationReader participationReader,
			ExamDocumentMediaApi documentApi, StudentLookupApi studentLookupApi, ExamAccessGuard accessGuard,
			Clock clock) {
		this.configService = configService;
		this.enrollmentService = enrollmentService;
		this.enrollmentRepository = enrollmentRepository;
		this.attemptRepository = attemptRepository;
		this.resultRepository = resultRepository;
		this.documentRepository = documentRepository;
		this.finalizer = finalizer;
		this.participationReader = participationReader;
		this.documentApi = documentApi;
		this.studentLookupApi = studentLookupApi;
		this.accessGuard = accessGuard;
		this.clock = clock;
	}

	public ExamManagementView management(UUID examId, String search, int page, int size) {
		page = Math.max(page, 0);
		size = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		Instant now = clock.instant();
		List<ManagedStudentRowView> all = filter(rows(exam, now), search);
		Counts counts = participationReader.countsFor(List.of(exam)).getOrDefault(exam.getId(), Counts.ZERO);
		ManagementSummaryView summary = new ManagementSummaryView(counts.enrolled(), counts.attended(),
				percent(counts.attended(), counts.enrolled()), counts.completed(),
				percent(counts.completed(), counts.enrolled()), counts.submitted(),
				percent(counts.submitted(), counts.enrolled()));
		int from = (int) Math.min((long) page * size, all.size());
		int to = Math.min(from + size, all.size());
		int totalPages = all.isEmpty() ? 0 : (all.size() + size - 1) / size;
		PageResponse<ManagedStudentRowView> students = new PageResponse<>(all.subList(from, to), page, size,
				all.size(), totalPages);
		return new ExamManagementView(now, exam.getResultsPublishedAt(), configService.toDetail(exam), summary,
				students);
	}

	/** The full filtered set (every page) as CSV (contract "Slice D" CSV rules). */
	public CsvExport export(UUID examId, String search) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		List<ManagedStudentRowView> all = filter(rows(exam, clock.instant()), search);
		return new CsvExport(ExamResultsCsv.fileName(exam.getTitle()), ExamResultsCsv.write(exam.getExamType(), all));
	}

	/** One student's current row (after a marking / document write); the caller has authorized. */
	public ManagedStudentRowView row(Exam exam, UUID studentId) {
		return rows(exam, clock.instant()).stream()
			.filter(row -> row.studentId().equals(studentId))
			.findFirst()
			.orElseThrow(() -> new com.lms.common.error.NotFoundException("This student isn't enrolled in the exam"));
	}

	// ------------------------------------------------------------------
	// Rows.
	// ------------------------------------------------------------------

	private List<ManagedStudentRowView> rows(Exam exam, Instant now) {
		enrollmentService.materializeEnrollAll(exam);
		List<ExamEnrollment> enrollments = enrollmentRepository.findAllByExamId(exam.getId());
		Map<UUID, ExamAttempt> attempts = new HashMap<>();
		for (ExamAttempt attempt : attemptRepository.findKlassAttemptsOfExams(List.of(exam.getId()))) {
			finalizer.finalizeIfDue(attempt, now);
			attempts.put(attempt.getStudentId(), attempt);
		}
		Map<UUID, ExamResult> results = resultRepository.findAllByExamId(exam.getId())
			.stream()
			.collect(Collectors.toMap(ExamResult::getStudentId, result -> result));
		Map<UUID, List<ExamSubmissionDocument>> documents = documentRepository.findLiveByExamId(exam.getId())
			.stream()
			.collect(Collectors.groupingBy(ExamSubmissionDocument::getStudentId));
		Set<UUID> answerMedia = new HashSet<>();
		documents.values().forEach(list -> list.forEach(document -> answerMedia.add(document.getMediaId())));
		Map<UUID, StoredDocument> answerFiles = documentApi.findLiveAll(answerMedia, Purpose.ANSWER_SHEET);
		Set<UUID> markedMedia = results.values()
			.stream()
			.map(ExamResult::getMarkedPaperMediaId)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, StoredDocument> markedFiles = documentApi.findLiveAll(markedMedia, Purpose.MARKED_PAPER);
		Map<UUID, StudentIndexEntry> students = new HashMap<>();
		studentLookupApi
			.findStudentIndexEntries(enrollments.stream().map(ExamEnrollment::getStudentId).collect(Collectors.toSet()))
			.forEach(entry -> students.put(entry.userId(), entry));

		List<ManagedStudentRowView> rows = new ArrayList<>();
		for (ExamEnrollment enrollment : enrollments) {
			UUID studentId = enrollment.getStudentId();
			ExamAttempt attempt = attempts.get(studentId);
			ExamResult result = results.get(studentId);
			List<ExamSubmissionDocument> studentDocuments = documents.getOrDefault(studentId, List.of());
			StudentIndexEntry entry = students.get(studentId);
			String[] name = splitName(entry == null ? null : entry.name());
			List<DocumentItemView> answerDocuments = studentDocuments.stream()
				.map(document -> new DocumentItemView(document.getId(), fileName(answerFiles, document.getMediaId())))
				.toList();
			DocumentRefView markedPaper = result == null || result.getMarkedPaperMediaId() == null ? null
					: new DocumentRefView(fileName(markedFiles, result.getMarkedPaperMediaId()));
			BigDecimal marks = exam.getExamType() == ExamType.MCQ ? (attempt == null ? null : attempt.getScore())
					: (result == null ? null : result.getMarks());
			rows.add(new ManagedStudentRowView(studentId, entry == null ? null : entry.indexNumber(), name[0], name[1],
					label(exam, attempt, result, !studentDocuments.isEmpty(), now), marks,
					attempt == null ? null : attempt.getStatus(),
					exam.getExamType() == ExamType.MCQ && attempt != null ? attempt.getAnsweredCount() : null,
					answerDocuments, markedPaper,
					exam.getExamType() == ExamType.PHYSICAL && result != null ? result.getAttendance() : null,
					enrollment.getSource()));
		}
		rows.sort(Comparator
			.comparing((ManagedStudentRowView row) -> row.indexNumber() == null ? "￿" : row.indexNumber())
			.thenComparing(row -> (row.firstName() + " " + row.lastName()).toLowerCase(Locale.ROOT))
			.thenComparing(ManagedStudentRowView::studentId));
		return rows;
	}

	/**
	 * Contract "Slice D" ResultLabel. Uses the same attended rule as {@link
	 * ExamParticipationReader}, so labels and summary counts agree.
	 */
	static ExamResultLabel label(Exam exam, ExamAttempt attempt, ExamResult result, boolean hasDocuments,
			Instant now) {
		boolean ended = !now.isBefore(exam.getScheduledEnd());
		ExamResultLabel notAttended = ended ? ExamResultLabel.NOT_PARTICIPATED : ExamResultLabel.PENDING;
		switch (exam.getExamType()) {
			case MCQ -> {
				if (attempt == null) {
					return notAttended;
				}
				if (!attempt.isFinalized()) {
					return ExamResultLabel.IN_PROGRESS;
				}
				return attempt.getScore() == null ? ExamResultLabel.AWAITING_MARKS
						: graded(attempt.getScore(), exam.getCutoffMarks());
			}
			case WRITTEN -> {
				if (result != null && result.getMarks() != null) {
					return graded(result.getMarks(), exam.getCutoffMarks());
				}
				if (attempt != null && !attempt.isFinalized() && now.isBefore(attempt.getDeadlineAt())) {
					return ExamResultLabel.IN_PROGRESS;
				}
				return ExamParticipationReader.attended(ExamType.WRITTEN, attempt, result, hasDocuments)
						? ExamResultLabel.AWAITING_MARKS : notAttended;
			}
			default -> {
				if (result != null && result.getAttendance() == ExamAttendance.ABSENT) {
					return ExamResultLabel.ABSENT;
				}
				if (result != null && result.getMarks() != null) {
					return graded(result.getMarks(), exam.getCutoffMarks());
				}
				return ExamParticipationReader.attended(ExamType.PHYSICAL, attempt, result, hasDocuments)
						? ExamResultLabel.AWAITING_MARKS : notAttended;
			}
		}
	}

	private static ExamResultLabel graded(BigDecimal marks, BigDecimal cutoff) {
		if (cutoff == null) {
			return ExamResultLabel.MARKED;
		}
		return marks.compareTo(cutoff) >= 0 ? ExamResultLabel.PASSED : ExamResultLabel.FAILED;
	}

	private static List<ManagedStudentRowView> filter(List<ManagedStudentRowView> rows, String search) {
		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return rows;
		}
		return rows.stream()
			.filter(row -> contains(row.indexNumber(), needle) || contains(row.firstName(), needle)
					|| contains(row.lastName(), needle)
					|| contains(row.firstName() + " " + row.lastName(), needle))
			.toList();
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

	private static String fileName(Map<UUID, StoredDocument> files, UUID mediaId) {
		StoredDocument document = files.get(mediaId);
		return document == null ? "" : document.fileName();
	}

	private static BigDecimal percent(long part, long whole) {
		if (whole <= 0) {
			return BigDecimal.ZERO.setScale(1);
		}
		return BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
	}

	/** First/last name are a whitespace split of the stored full name (students have one name field). */
	static String[] splitName(String fullName) {
		String name = fullName == null ? "" : fullName.strip();
		int space = name.indexOf(' ');
		String first = space < 0 ? name : name.substring(0, space);
		String last = space < 0 ? "" : name.substring(space + 1).strip();
		return new String[] { first, last };
	}

}

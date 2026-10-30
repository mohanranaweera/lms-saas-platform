package com.lms.exammanagement.service.exam;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.enrollmentmanagement.api.UnpaidStudentsEnrollmentApi;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamEnrollment;
import com.lms.exammanagement.domain.ExamEnrollmentSource;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.service.exam.StudentExamViews.EnrollResultView;
import com.lms.exammanagement.service.exam.StudentExamViews.EnrollmentCandidateView;
import com.lms.exammanagement.service.exam.StudentExamViews.SyncResultView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Klass exam enrollment (contract "Slice C", ADR-032, D4): staff enroll / remove / candidates /
 * "Sync payments", and the dynamic "Enroll all students" materialization.
 *
 * <p>Authorization: the exam's course authoring access ({@link ExamAccessGuard}: owning teacher,
 * teacher assistant, or staff with {@code DomainArea.EXAMS} - VIEW for reads, CREATE_EDIT for
 * writes); cross-tenant exam 404. Every student id must be an active participant of the exam's
 * course in the caller's tenant (never trusted from the client). Manual enroll / remove and
 * sync are audit-logged in the same transaction. Course enrollment is never touched.
 *
 * <p>Enroll-all (D4) is dynamic: on save every current course participant is materialized as
 * {@code ENROLL_ALL}; later participants are materialized on their first exam listing / access
 * before {@code endAt} ({@link #materializeForStudent}).
 */
@Service
@Transactional
public class ExamEnrollmentService {

	static final int MAX_STUDENT_IDS = 500;

	static final int MAX_PARTICIPANTS = 100_000;

	static final int DEFAULT_CANDIDATE_PAGE_SIZE = 20;

	static final int MAX_CANDIDATE_PAGE_SIZE = 100;

	private final ExamRepository examRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamPaymentEnrollmentService paymentEnrollmentService;

	private final ExamParticipationReader participationReader;

	private final ExamAccessGuard accessGuard;

	private final ContentCoverageApi contentCoverageApi;

	private final UnpaidStudentsEnrollmentApi participantsApi;

	private final StudentLookupApi studentLookupApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	public ExamEnrollmentService(ExamRepository examRepository, ExamEnrollmentRepository enrollmentRepository,
			ExamPaymentEnrollmentService paymentEnrollmentService,
			ExamParticipationReader participationReader, ExamAccessGuard accessGuard, ContentCoverageApi contentCoverageApi,
			UnpaidStudentsEnrollmentApi participantsApi, StudentLookupApi studentLookupApi, AuditLogApi auditLogApi,
			TenantContext tenantContext, Clock clock) {
		this.examRepository = examRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.paymentEnrollmentService = paymentEnrollmentService;
		this.participationReader = participationReader;
		this.accessGuard = accessGuard;
		this.contentCoverageApi = contentCoverageApi;
		this.participantsApi = participantsApi;
		this.studentLookupApi = studentLookupApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Staff.
	// ------------------------------------------------------------------

	public EnrollResultView enroll(UUID examId, List<UUID> studentIds) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (studentIds == null || studentIds.isEmpty()) {
			throw invalidStudents("Choose at least one student");
		}
		if (studentIds.size() > MAX_STUDENT_IDS) {
			throw invalidStudents("Enroll at most " + MAX_STUDENT_IDS + " students at a time");
		}
		LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
		for (UUID studentId : studentIds) {
			if (studentId == null) {
				throw invalidStudents("Every student must be chosen from the course's students");
			}
			distinct.add(studentId);
		}
		for (UUID studentId : distinct) {
			if (!contentCoverageApi.isParticipant(studentId, exam.getCourseId())) {
				throw invalidStudents("Every student must be enrolled in the exam's course");
			}
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Instant now = clock.instant();
		List<String> added = new ArrayList<>();
		int already = 0;
		for (UUID studentId : distinct) {
			if (enrollmentRepository.insertIfAbsent(exam.getId(), studentId, ExamEnrollmentSource.MANUAL, null, null,
					actorId, now) == 1) {
				added.add(studentId.toString());
			}
			else {
				already++;
			}
		}
		if (!added.isEmpty()) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("studentIds", added);
			metadata.put("alreadyEnrolled", already);
			audit(actorId, "exam.enrollments_added", exam.getId(), metadata);
		}
		return new EnrollResultView(added.size(), already);
	}

	public void remove(UUID examId, UUID studentId) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		lock(exam.getId());
		ExamEnrollment enrollment = enrollmentRepository.findByExamIdAndStudentId(exam.getId(), studentId)
			.orElseThrow(() -> new NotFoundException("This student isn't enrolled in the exam"));
		if (exam.isEnrollAllStudents()) {
			throw ExamStateException.conflict(ExamStateException.ENROLL_ALL_ACTIVE,
					"Every course student is enrolled in this exam. Turn off \"Enroll all students\" to remove one.");
		}
		if (participationReader.hasStudentParticipation(exam.getId(), studentId)) {
			throw ExamStateException.conflict(ExamStateException.HAS_ATTEMPT,
					"This student has already attempted the exam, so they can't be removed");
		}
		enrollmentRepository.deleteEnrollment(enrollment.getId());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("studentId", studentId.toString());
		metadata.put("source", enrollment.getSource().name());
		if (enrollment.getPaymentId() != null) {
			metadata.put("paymentId", enrollment.getPaymentId().toString());
		}
		if (enrollment.getFreeCardId() != null) {
			metadata.put("freeCardId", enrollment.getFreeCardId().toString());
		}
		audit(AuthenticatedPrincipalHolder.get().userId(), "exam.enrollment_removed", exam.getId(), metadata);
	}

	@Transactional(readOnly = true)
	public PageResponse<EnrollmentCandidateView> candidates(UUID examId, String search, int page, int size) {
		if (page < 0) {
			page = 0;
		}
		if (size < 1) {
			size = DEFAULT_CANDIDATE_PAGE_SIZE;
		}
		size = Math.min(size, MAX_CANDIDATE_PAGE_SIZE);
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		Set<UUID> enrolled = new HashSet<>(enrollmentRepository.findStudentIdsByExamId(exam.getId()));
		Set<UUID> candidates = new LinkedHashSet<>();
		participantsApi.findCurrentCourseParticipants(exam.getCourseId(), MAX_PARTICIPANTS)
			.forEach(fact -> {
				if (!enrolled.contains(fact.studentId())) {
					candidates.add(fact.studentId());
				}
			});
		String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
		List<EnrollmentCandidateView> all = studentLookupApi.findStudentIndexEntries(candidates)
			.stream()
			.filter(entry -> needle.isEmpty() || contains(entry.name(), needle) || contains(entry.indexNumber(), needle))
			.sorted(Comparator.comparing((StudentIndexEntry entry) -> entry.name() == null ? "" : entry.name(),
					String.CASE_INSENSITIVE_ORDER).thenComparing(StudentIndexEntry::userId))
			.map(ExamEnrollmentService::candidate)
			.toList();
		int from = (int) Math.min((long) page * size, all.size());
		int to = Math.min(from + size, all.size());
		int totalPages = all.isEmpty() ? 0 : (all.size() + size - 1) / size;
		return new PageResponse<>(all.subList(from, to), page, size, all.size(), totalPages);
	}

	public SyncResultView syncPayments(UUID examId) {
		Exam exam = loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (!exam.isAutoEnrollOnPayment()) {
			throw ExamStateException.conflict(ExamStateException.NOT_SUPPORTED,
					"Turn on \"Auto-enroll on payment\" to enroll students from payments");
		}
		lock(exam.getId());
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		int added = paymentEnrollmentService.syncExam(exam, actorId);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("added", added);
		metadata.put("ended", !clock.instant().isBefore(exam.getScheduledEnd()));
		audit(actorId, "exam.enrollments_synced", exam.getId(), metadata);
		return new SyncResultView(added);
	}

	// ------------------------------------------------------------------
	// Enroll-all / Free Card materialization.
	// ------------------------------------------------------------------

	/**
	 * D4 on save (and for Slice D management listing): materializes {@code ENROLL_ALL} rows for
	 * every current participant of the exam's course while the exam has not ended.
	 * @return the number of new rows.
	 */
	public int materializeEnrollAll(Exam exam) {
		Instant now = clock.instant();
		if (!exam.isEnrollAllStudents() || exam.isDeleted() || !now.isBefore(exam.getScheduledEnd())) {
			return 0;
		}
		UUID actorId = AuthenticatedPrincipalHolder.isSet() ? AuthenticatedPrincipalHolder.get().userId() : null;
		Set<UUID> students = new LinkedHashSet<>();
		participantsApi.findCurrentCourseParticipants(exam.getCourseId(), MAX_PARTICIPANTS)
			.forEach(fact -> students.add(fact.studentId()));
		int added = 0;
		for (UUID studentId : students) {
			added += enrollmentRepository.insertIfAbsent(exam.getId(), studentId, ExamEnrollmentSource.ENROLL_ALL, null,
					null, actorId, now);
		}
		return added;
	}

	/**
	 * D4 / ADR-030 lazily for one student: every live, not-ended exam of the student's courses that
	 * enrolls all students (or auto-enrolls a Free Card month) gets the student's row.
	 */
	public void materializeForStudent(UUID studentId) {
		Set<UUID> courseIds = contentCoverageApi.findCourseIds(studentId);
		if (courseIds.isEmpty()) {
			return;
		}
		Instant now = clock.instant();
		for (Exam exam : examRepository.findLiveKlassExams(courseIds)) {
			materialize(exam, studentId, now);
		}
	}

	/** {@link #materializeForStudent(UUID)} for a single exam. */
	public void materializeForStudent(UUID studentId, Exam exam) {
		Instant now = clock.instant();
		if (!now.isBefore(exam.getScheduledEnd()) || (!exam.isEnrollAllStudents() && !exam.isAutoEnrollOnPayment())) {
			return;
		}
		if (enrollmentRepository.existsByExamIdAndStudentId(exam.getId(), studentId)) {
			return;
		}
		if (!contentCoverageApi.isParticipant(studentId, exam.getCourseId())) {
			return;
		}
		materialize(exam, studentId, now);
	}

	private void materialize(Exam exam, UUID studentId, Instant now) {
		if (!now.isBefore(exam.getScheduledEnd())) {
			return;
		}
		if (exam.isEnrollAllStudents()) {
			enrollmentRepository.insertIfAbsent(exam.getId(), studentId, ExamEnrollmentSource.ENROLL_ALL, null, null,
					null, now);
		}
		else if (exam.isAutoEnrollOnPayment()) {
			paymentEnrollmentService.enrollFreeCards(exam, paymentEnrollmentService.criteria(exam), studentId, null,
					now);
		}
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private Exam loadKlassExam(UUID examId) {
		return examRepository.findLiveKlassExam(examId).orElseThrow(() -> new NotFoundException("Exam not found"));
	}

	private void lock(UUID examId) {
		examRepository.acquireExamConfigLock(ExamLocks.configKey(tenantContext.getTenantId(), examId));
	}

	private void audit(UUID actorId, String action, UUID examId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(actorId, action, "exam", examId, null, metadata));
	}

	private static FieldValidationException invalidStudents(String message) {
		return new FieldValidationException("The students are not valid", List.of(new FieldError("studentIds", message)));
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

	private static EnrollmentCandidateView candidate(StudentIndexEntry entry) {
		String name = entry.name() == null ? "" : entry.name().strip();
		int space = name.indexOf(' ');
		String first = space < 0 ? name : name.substring(0, space);
		String last = space < 0 ? "" : name.substring(space + 1).strip();
		return new EnrollmentCandidateView(entry.userId(), entry.indexNumber(), first, last);
	}

}

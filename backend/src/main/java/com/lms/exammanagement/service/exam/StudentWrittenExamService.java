package com.lms.exammanagement.service.exam;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.StoredDocument;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAttempt;
import com.lms.exammanagement.domain.ExamFinalizedReason;
import com.lms.exammanagement.domain.ExamSubmissionDocument;
import com.lms.exammanagement.domain.ExamSubmissionUploaderRole;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamAttemptRepository;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.repository.ExamSubmissionDocumentRepository;
import com.lms.exammanagement.service.exam.ExamManagementViews.StudentDocumentView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The student side of a WRITTEN Klass exam (contract "Slice D", D2).
 *
 * <p>Flow: the student opens the exam with {@code POST /student/exams/{id}/attempts {password}}
 * (the same start endpoint, rules and errors as MCQ: enrolled, {@code startAt <= now < endAt},
 * password, rate limit, one attempt). That creates the written attempt row - the "paper opened"
 * (attended) fact - with {@code deadlineAt = min(open + duration, endAt)} and no items. While that
 * attempt is open ({@code now < deadlineAt}, not submitted) the student may read the question
 * paper ({@link #paper}) and upload answer sheets ({@link #uploadAnswers}); afterwards both are
 * 409. The marking scheme is never reachable here.
 *
 * <p>Security: STUDENT role only (403 otherwise); tenant from the context; another student's /
 * tenant's / a not-enrolled exam is 404. Uploads are validated in full before anything is stored
 * and the media rows plus submission rows commit together after the deadline is re-checked under
 * the attempt's row lock.
 */
@Service
@Transactional(noRollbackFor = ExamStateException.class)
public class StudentWrittenExamService {

	static final int MAX_FILES = 10;

	private static final String STUDENT_ROLE = "STUDENT";

	private final ExamRepository examRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamAttemptRepository attemptRepository;

	private final ExamSubmissionDocumentRepository documentRepository;

	private final ExamEnrollmentService enrollmentService;

	private final ExamAttemptFinalizer finalizer;

	private final ExamDocumentMediaApi documentApi;

	private final TenantContext tenantContext;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	public StudentWrittenExamService(ExamRepository examRepository, ExamEnrollmentRepository enrollmentRepository,
			ExamAttemptRepository attemptRepository, ExamSubmissionDocumentRepository documentRepository,
			ExamEnrollmentService enrollmentService, ExamAttemptFinalizer finalizer, ExamDocumentMediaApi documentApi,
			TenantContext tenantContext, PlatformTransactionManager transactionManager, Clock clock) {
		this.examRepository = examRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.attemptRepository = attemptRepository;
		this.documentRepository = documentRepository;
		this.enrollmentService = enrollmentService;
		this.finalizer = finalizer;
		this.documentApi = documentApi;
		this.tenantContext = tenantContext;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** The question paper, signed for 10 minutes, while the student's written attempt is open. */
	public ExamDocumentLinkView paper(UUID examId) {
		UUID studentId = requireStudent();
		Exam exam = loadEnrolledWrittenExam(examId, studentId);
		requireOpenAttempt(exam, studentId, clock.instant());
		UUID mediaId = exam.getQuestionPaperMediaId();
		StoredDocument document = documentApi.findLive(mediaId, Purpose.QUESTION_PAPER)
			.orElseThrow(() -> new NotFoundException("This exam has no question paper"));
		String url = documentApi.signUrl(mediaId, Purpose.QUESTION_PAPER, ExamDocumentService.DOCUMENT_URL_TTL)
			.orElseThrow(() -> new NotFoundException("This exam has no question paper"));
		return new ExamDocumentLinkView(url, document.fileName());
	}

	/**
	 * Uploads answer sheets (PDF / PNG / JPEG, at most 20 MB each, at most {@value #MAX_FILES} per
	 * request) before the attempt deadline. Storage runs outside any DB transaction; all files or
	 * none are kept.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public List<StudentDocumentView> uploadAnswers(UUID examId, List<MultipartFile> files) {
		UUID studentId = requireStudent();
		transactionTemplate.executeWithoutResult(status -> {
			Exam exam = loadEnrolledWrittenExam(examId, studentId);
			requireOpenAttempt(exam, studentId, clock.instant());
		});
		if (files == null || files.isEmpty()) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("files", "Choose at least one file")));
		}
		if (files.size() > MAX_FILES) {
			throw new FieldValidationException("Too many files",
					List.of(new FieldError("files", "Upload at most " + MAX_FILES + " files at a time")));
		}
		documentApi.uploadAll(files, Purpose.ANSWER_SHEET, stored -> {
			// Re-checked under the attempt's row lock in the same transaction as the inserts.
			ExamAttempt attempt = attemptRepository.findKlassAttempt(examId, studentId)
				.orElseThrow(StudentWrittenExamService::notStarted);
			attemptRepository.lockAndRefresh(attempt);
			Instant now = clock.instant();
			requireOpen(attempt, now);
			for (StoredDocument document : stored) {
				documentRepository.save(new ExamSubmissionDocument(tenantContext.getTenantId(), examId, studentId,
						document.mediaId(), studentId, ExamSubmissionUploaderRole.STUDENT, now));
			}
			documentRepository.flush();
		});
		return transactionTemplate.execute(status -> documents(examId, studentId));
	}

	/** The student's own live answer documents (theirs and staff-entered ones), in upload order. */
	@Transactional(readOnly = true)
	public List<StudentDocumentView> myDocuments(UUID examId) {
		UUID studentId = requireStudent();
		Exam exam = examRepository.findLiveKlassExam(examId).orElseThrow(StudentWrittenExamService::examNotFound);
		if (!enrollmentRepository.existsByExamIdAndStudentId(exam.getId(), studentId)) {
			throw examNotFound();
		}
		return documents(exam.getId(), studentId);
	}

	// ------------------------------------------------------------------
	// Internals.
	// ------------------------------------------------------------------

	private List<StudentDocumentView> documents(UUID examId, UUID studentId) {
		List<ExamSubmissionDocument> documents = documentRepository.findLiveByExamIdAndStudentId(examId, studentId);
		Map<UUID, StoredDocument> files = documentApi
			.findLiveAll(documents.stream().map(ExamSubmissionDocument::getMediaId).toList(), Purpose.ANSWER_SHEET);
		return documents.stream()
			.map(document -> new StudentDocumentView(document.getId(),
					files.containsKey(document.getMediaId()) ? files.get(document.getMediaId()).fileName() : "",
					document.getUploadedAt(), document.getUploadedByRole()))
			.toList();
	}

	private Exam loadEnrolledWrittenExam(UUID examId, UUID studentId) {
		Exam exam = examRepository.findLiveKlassExam(examId).orElseThrow(StudentWrittenExamService::examNotFound);
		enrollmentService.materializeForStudent(studentId, exam);
		if (!enrollmentRepository.existsByExamIdAndStudentId(exam.getId(), studentId)) {
			throw examNotFound();
		}
		if (exam.getExamType() != ExamType.WRITTEN) {
			throw ExamStateException.conflict(ExamStateException.NOT_SUPPORTED,
					"Only written exams have a question paper and answer sheets");
		}
		return exam;
	}

	private ExamAttempt requireOpenAttempt(Exam exam, UUID studentId, Instant now) {
		ExamAttempt attempt = attemptRepository.findKlassAttempt(exam.getId(), studentId)
			.orElseThrow(StudentWrittenExamService::notStarted);
		finalizer.finalizeIfDue(attempt, now);
		requireOpen(attempt, now);
		return attempt;
	}

	private static void requireOpen(ExamAttempt attempt, Instant now) {
		if (attempt.isFinalized()) {
			if (attempt.getFinalizedReason() == ExamFinalizedReason.TIMEOUT) {
				throw ExamStateException.conflict(ExamStateException.DEADLINE_PASSED, "Time is up for this exam");
			}
			throw ExamStateException.conflict(ExamStateException.FINALIZED, "You have already finished this exam");
		}
		if (!now.isBefore(attempt.getDeadlineAt())) {
			throw ExamStateException.conflict(ExamStateException.DEADLINE_PASSED, "Time is up for this exam");
		}
	}

	private static UUID requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only students can take exams");
		}
		return principal.userId();
	}

	private static ExamStateException notStarted() {
		return ExamStateException.conflict(ExamStateException.NOT_STARTED,
				"Enter the exam password to open the paper first");
	}

	private static NotFoundException examNotFound() {
		return new NotFoundException("Exam not found");
	}

}

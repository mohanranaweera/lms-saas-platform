package com.lms.exammanagement.service.exam;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.StoredDocument;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamAttendance;
import com.lms.exammanagement.domain.ExamResult;
import com.lms.exammanagement.domain.ExamSubmissionDocument;
import com.lms.exammanagement.domain.ExamSubmissionUploaderRole;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.repository.ExamResultRepository;
import com.lms.exammanagement.repository.ExamSubmissionDocumentRepository;
import com.lms.exammanagement.service.exam.ExamManagementViews.ManagedStudentRowView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDetailView;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Written / physical marking and protected written documents (contract "Slice D", D8).
 *
 * <ul>
 * <li>Authorization: the exam's course authoring access ({@link ExamAccessGuard}) - {@code
 * CREATE_EDIT} for writes, {@code VIEW} for staff document links; students 403 on staff paths
 * (except reading their own documents / released marked paper, below); another teacher's course
 * 403; another tenant's exam 404. The student must be enrolled in the exam (else 404).</li>
 * <li>Marks: {@code 0 <= marks <= totalMarks}, at most 2 decimals; changing existing marks
 * requires a reason; every change writes an {@code exam.result_marked} audit entry with before /
 * after in the same transaction, under the exam's configuration lock (serialized with a physical
 * total-marks change).</li>
 * <li>Documents: uploads are validated in full (count, size, sniffed PDF/PNG/JPEG) before anything
 * is stored, and the media rows, the submission rows and the audit entry commit together or not
 * at all ({@link ExamDocumentMediaApi#uploadAll}). Removal is a soft delete, only before results
 * are published, audited.</li>
 * <li>Links: signed for 10 minutes. A student may read only their own answer documents, and their
 * marked paper only once results are published; anything else is 404.</li>
 * </ul>
 */
@Service
@Transactional
public class ExamMarkingService {

	static final int MAX_FILES = 10;

	static final int MAX_REASON_LENGTH = 500;

	static final BigDecimal MAX_TOTAL_MARKS = new BigDecimal("10000");

	private static final String STUDENT_ROLE = "STUDENT";

	private final ExamConfigService configService;

	private final ExamManagementService managementService;

	private final ExamRepository examRepository;

	private final ExamEnrollmentRepository enrollmentRepository;

	private final ExamResultRepository resultRepository;

	private final ExamSubmissionDocumentRepository documentRepository;

	private final ExamDocumentMediaApi documentApi;

	private final ExamAccessGuard accessGuard;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock;

	public ExamMarkingService(ExamConfigService configService, ExamManagementService managementService,
			ExamRepository examRepository, ExamEnrollmentRepository enrollmentRepository,
			ExamResultRepository resultRepository, ExamSubmissionDocumentRepository documentRepository,
			ExamDocumentMediaApi documentApi, ExamAccessGuard accessGuard, AuditLogApi auditLogApi,
			TenantContext tenantContext, Clock clock) {
		this.configService = configService;
		this.managementService = managementService;
		this.examRepository = examRepository;
		this.enrollmentRepository = enrollmentRepository;
		this.resultRepository = resultRepository;
		this.documentRepository = documentRepository;
		this.documentApi = documentApi;
		this.accessGuard = accessGuard;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Marks / attendance.
	// ------------------------------------------------------------------

	public ManagedStudentRowView saveResult(UUID examId, UUID studentId, BigDecimal marks, String attendanceValue,
			String reason) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (exam.getExamType() == ExamType.MCQ) {
			throw ExamStateException.conflict(ExamStateException.NOT_SUPPORTED,
					"MCQ exams are marked automatically");
		}
		lock(exam);
		requireEnrolled(exam, studentId);
		Instant now = clock.instant();
		if (now.isBefore(exam.getScheduledStart())) {
			throw ExamStateException.conflict(ExamStateException.NOT_OPEN,
					"Marks can be entered once the exam has started");
		}
		ExamAttendance attendance = parseAttendance(exam, attendanceValue);
		if (marks != null) {
			if (exam.getTotalMarks() == null) {
				throw ExamStateException.conflict(ExamStateException.TOTAL_MARKS_REQUIRED,
						"Set the exam's total marks before entering marks");
			}
			validateMarks(marks, exam.getTotalMarks());
			if (exam.getExamType() == ExamType.PHYSICAL) {
				if (attendance == ExamAttendance.ABSENT) {
					throw invalid("marks", "An absent student can't have marks");
				}
				attendance = ExamAttendance.PRESENT;
			}
		}
		String trimmedReason = reason == null || reason.isBlank() ? null : reason.strip();
		if (trimmedReason != null && trimmedReason.length() > MAX_REASON_LENGTH) {
			throw invalid("reason", "Keep the reason under " + MAX_REASON_LENGTH + " characters");
		}

		ExamResult result = resultRepository.findByExamIdAndStudentId(exam.getId(), studentId).orElse(null);
		BigDecimal beforeMarks = result == null ? null : result.getMarks();
		ExamAttendance beforeAttendance = result == null ? null : result.getAttendance();
		boolean marksChanged = !sameMarks(beforeMarks, marks);
		if (!marksChanged && beforeAttendance == attendance) {
			return managementService.row(exam, studentId);
		}
		if (marksChanged && beforeMarks != null && trimmedReason == null) {
			throw invalid("reason", "Give a reason for changing the marks");
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		if (result == null) {
			result = new ExamResult(tenantContext.getTenantId(), exam.getId(), studentId);
		}
		result.mark(marks, attendance, actorId, now);
		result = resultRepository.saveAndFlush(result);

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("examId", exam.getId().toString());
		metadata.put("studentId", studentId.toString());
		metadata.put("before", snapshot(beforeMarks, beforeAttendance));
		metadata.put("after", snapshot(marks, attendance));
		auditLogApi.record(new AuditLogEntry(actorId, "exam.result_marked", "exam_result", result.getId(),
				trimmedReason, metadata));
		return managementService.row(exam, studentId);
	}

	/** D8: a PHYSICAL exam's total marks, set on the management page; 409 if a mark exceeds it. */
	public ExamDetailView setTotalMarks(UUID examId, BigDecimal totalMarks) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (exam.getExamType() != ExamType.PHYSICAL) {
			throw ExamStateException.conflict(ExamStateException.NOT_SUPPORTED,
					"Only physical exams set their total marks here");
		}
		lock(exam);
		if (totalMarks == null || totalMarks.signum() <= 0 || totalMarks.compareTo(MAX_TOTAL_MARKS) > 0
				|| Math.max(totalMarks.stripTrailingZeros().scale(), 0) > 2) {
			throw invalid("totalMarks", "Total marks must be between 1 and 10000, with at most 2 decimals");
		}
		if (exam.getCutoffMarks() != null && exam.getCutoffMarks().compareTo(totalMarks) > 0) {
			throw invalid("totalMarks", "Total marks can't be lower than the cut-off ("
					+ exam.getCutoffMarks().stripTrailingZeros().toPlainString() + ")");
		}
		BigDecimal highest = resultRepository.findAllByExamId(exam.getId())
			.stream()
			.map(ExamResult::getMarks)
			.filter(java.util.Objects::nonNull)
			.max(BigDecimal::compareTo)
			.orElse(null);
		if (highest != null && highest.compareTo(totalMarks) > 0) {
			throw ExamStateException.conflict(ExamStateException.MARKS_EXCEED_TOTAL,
					"A student already has " + highest.stripTrailingZeros().toPlainString()
							+ " marks, more than the new total");
		}
		BigDecimal before = exam.getTotalMarks();
		if (before != null && before.compareTo(totalMarks) == 0) {
			return configService.toDetail(exam);
		}
		exam.setTotalMarks(totalMarks);
		examRepository.saveAndFlush(exam);
		Map<String, Object> metadata = new LinkedHashMap<>();
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("before", plain(before));
		change.put("after", plain(totalMarks));
		metadata.put("totalMarks", change);
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), "exam.total_marks_changed",
				"exam", exam.getId(), null, metadata));
		return configService.toDetail(exam);
	}

	// ------------------------------------------------------------------
	// Written documents (staff).
	// ------------------------------------------------------------------

	/** "Enter answer sheet" on the student's behalf. Storage runs outside any DB transaction. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ManagedStudentRowView uploadOnBehalf(UUID examId, UUID studentId, List<MultipartFile> files) {
		Exam exam = writtenExamForStaffWrite(examId, studentId);
		if (files == null || files.isEmpty()) {
			throw invalid("files", "Choose at least one file");
		}
		if (files.size() > MAX_FILES) {
			throw invalid("files", "Upload at most " + MAX_FILES + " files at a time");
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		documentApi.uploadAll(files, Purpose.ANSWER_SHEET, stored -> {
			lock(exam);
			Exam current = configService.loadKlassExam(examId);
			requireEnrolled(current, studentId);
			requireNotPublished(current);
			Instant now = clock.instant();
			List<String> ids = new java.util.ArrayList<>();
			for (StoredDocument document : stored) {
				ExamSubmissionDocument saved = documentRepository.save(new ExamSubmissionDocument(
						tenantContext.getTenantId(), examId, studentId, document.mediaId(), actorId,
						ExamSubmissionUploaderRole.STAFF, now));
				ids.add(saved.getId().toString());
			}
			documentRepository.flush();
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("studentId", studentId.toString());
			metadata.put("documentIds", ids);
			metadata.put("onBehalf", true);
			auditLogApi.record(new AuditLogEntry(actorId, "exam.submission_documents_added", "exam", examId, null,
					metadata));
		});
		return managementService.row(exam, studentId);
	}

	/** Soft delete, staff only, before results are published; audited. */
	public void deleteDocument(UUID examId, UUID studentId, UUID documentId) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		lock(exam);
		ExamSubmissionDocument document = documentRepository.findById(documentId)
			.filter(candidate -> candidate.getExamId().equals(exam.getId())
					&& candidate.getStudentId().equals(studentId) && !candidate.isDeleted())
			.orElseThrow(() -> new NotFoundException("Document not found"));
		requireNotPublished(exam);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		document.markDeleted(actorId, clock.instant());
		documentRepository.saveAndFlush(document);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("examId", exam.getId().toString());
		metadata.put("studentId", studentId.toString());
		metadata.put("mediaId", document.getMediaId().toString());
		metadata.put("uploadedByRole", document.getUploadedByRole().name());
		auditLogApi.record(new AuditLogEntry(actorId, "exam.submission_document_removed", "exam_submission_document",
				document.getId(), null, metadata));
	}

	/** The marked answer paper of a WRITTEN exam (replaces an earlier one; audited before / after). */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ManagedStudentRowView uploadMarkedPaper(UUID examId, UUID studentId, MultipartFile file) {
		Exam exam = writtenExamForStaffWrite(examId, studentId);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		documentApi.uploadAll(java.util.Collections.singletonList(file), Purpose.MARKED_PAPER, stored -> {
			lock(exam);
			// Re-check under the exam lock: results may have been published while the file
			// was being stored (outside any transaction).
			Exam current = configService.loadKlassExam(examId);
			requireEnrolled(current, studentId);
			requireNotPublished(current);
			ExamResult result = resultRepository.findByExamIdAndStudentId(examId, studentId)
				.orElseGet(() -> new ExamResult(tenantContext.getTenantId(), examId, studentId));
			UUID before = result.getMarkedPaperMediaId();
			UUID after = stored.get(0).mediaId();
			result.attachMarkedPaper(after);
			result = resultRepository.saveAndFlush(result);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("examId", examId.toString());
			metadata.put("studentId", studentId.toString());
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("before", before == null ? null : before.toString());
			change.put("after", after.toString());
			metadata.put("markedPaperMediaId", change);
			auditLogApi.record(new AuditLogEntry(actorId, "exam.marked_paper_uploaded", "exam_result", result.getId(),
					null, metadata));
		});
		return managementService.row(exam, studentId);
	}

	// ------------------------------------------------------------------
	// Signed links (staff, or the student for their own).
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public ExamDocumentLinkView documentLink(UUID examId, UUID studentId, UUID documentId) {
		Exam exam = configService.loadKlassExam(examId);
		authorizeRead(exam, studentId);
		ExamSubmissionDocument document = documentRepository.findById(documentId)
			.filter(candidate -> candidate.getExamId().equals(exam.getId())
					&& candidate.getStudentId().equals(studentId) && !candidate.isDeleted())
			.orElseThrow(() -> new NotFoundException("Document not found"));
		return link(document.getMediaId(), Purpose.ANSWER_SHEET);
	}

	@Transactional(readOnly = true)
	public ExamDocumentLinkView markedPaperLink(UUID examId, UUID studentId) {
		Exam exam = configService.loadKlassExam(examId);
		boolean student = authorizeRead(exam, studentId);
		if (student && exam.getResultsPublishedAt() == null) {
			throw new NotFoundException("Document not found");
		}
		UUID mediaId = resultRepository.findByExamIdAndStudentId(exam.getId(), studentId)
			.map(ExamResult::getMarkedPaperMediaId)
			.orElseThrow(() -> new NotFoundException("Document not found"));
		return link(mediaId, Purpose.MARKED_PAPER);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	/** @return {@code true} when the caller is the student themself. */
	private boolean authorizeRead(Exam exam, UUID studentId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (STUDENT_ROLE.equals(principal.role())) {
			if (!principal.userId().equals(studentId)
					|| !enrollmentRepository.existsByExamIdAndStudentId(exam.getId(), studentId)) {
				throw new NotFoundException("Document not found");
			}
			return true;
		}
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		return false;
	}

	private ExamDocumentLinkView link(UUID mediaId, Purpose purpose) {
		StoredDocument document = documentApi.findLive(mediaId, purpose)
			.orElseThrow(() -> new NotFoundException("Document not found"));
		String url = documentApi.signUrl(mediaId, purpose, ExamDocumentService.DOCUMENT_URL_TTL)
			.orElseThrow(() -> new NotFoundException("Document not found"));
		return new ExamDocumentLinkView(url, document.fileName());
	}

	private Exam writtenExamForStaffWrite(UUID examId, UUID studentId) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.CREATE_EDIT);
		if (exam.getExamType() != ExamType.WRITTEN) {
			throw ExamStateException.conflict(ExamStateException.NOT_SUPPORTED,
					"Only written exams have answer documents");
		}
		requireEnrolled(exam, studentId);
		if (clock.instant().isBefore(exam.getScheduledStart())) {
			throw ExamStateException.conflict(ExamStateException.NOT_OPEN,
					"Answer documents can be added once the exam has started");
		}
		requireNotPublished(exam);
		return exam;
	}

	private void requireEnrolled(Exam exam, UUID studentId) {
		if (!enrollmentRepository.existsByExamIdAndStudentId(exam.getId(), studentId)) {
			throw new NotFoundException("This student isn't enrolled in the exam");
		}
	}

	private static void requireNotPublished(Exam exam) {
		if (exam.getResultsPublishedAt() != null) {
			throw ExamStateException.conflict(ExamStateException.RESULTS_PUBLISHED,
					"Results are already published, so answer documents can't be changed");
		}
	}

	private void lock(Exam exam) {
		examRepository.acquireExamConfigLock(ExamLocks.configKey(tenantContext.getTenantId(), exam.getId()));
	}

	private static ExamAttendance parseAttendance(Exam exam, String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		if (exam.getExamType() != ExamType.PHYSICAL) {
			throw invalid("attendance", "Attendance is recorded for physical exams only");
		}
		try {
			return ExamAttendance.valueOf(value);
		}
		catch (IllegalArgumentException e) {
			throw invalid("attendance", "Use PRESENT or ABSENT");
		}
	}

	private static void validateMarks(BigDecimal marks, BigDecimal totalMarks) {
		if (marks.signum() < 0 || marks.compareTo(totalMarks) > 0) {
			throw invalid("marks", "Marks must be between 0 and " + totalMarks.stripTrailingZeros().toPlainString());
		}
		if (Math.max(marks.stripTrailingZeros().scale(), 0) > 2) {
			throw invalid("marks", "Use at most 2 decimals");
		}
	}

	private static boolean sameMarks(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	private static Map<String, Object> snapshot(BigDecimal marks, ExamAttendance attendance) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("marks", plain(marks));
		snapshot.put("attendance", attendance == null ? null : attendance.name());
		return snapshot;
	}

	private static String plain(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("The request is not valid", List.of(new FieldError(field, message)));
	}

}

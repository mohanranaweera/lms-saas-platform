package com.lms.exammanagement.service.exam;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.StoredDocument;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.service.exam.ExamViews.ExamDocumentLinkView;
import com.lms.exammanagement.service.exam.ExamViews.UploadedExamDocumentView;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.PermissionAction;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Written-exam documents (question paper, marking scheme): upload by authoring roles, and
 * short-lived signed download links for staff / the owning teacher only (students are 403
 * in this slice, other tenants 404). A document is only reachable through the exam that
 * references it - never by a bare media id.
 */
@Service
public class ExamDocumentService {

	/** Lifetime of a document download link. */
	public static final Duration DOCUMENT_URL_TTL = Duration.ofMinutes(10);

	private final ExamDocumentMediaApi documentApi;

	private final ExamAccessGuard accessGuard;

	private final ExamConfigService configService;

	public ExamDocumentService(ExamDocumentMediaApi documentApi, ExamAccessGuard accessGuard,
			ExamConfigService configService) {
		this.documentApi = documentApi;
		this.accessGuard = accessGuard;
		this.configService = configService;
	}

	/** Not transactional: object storage is called outside any DB transaction. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public UploadedExamDocumentView upload(String purpose, MultipartFile file) {
		accessGuard.requireAuthoringRole(PermissionAction.CREATE_EDIT);
		StoredDocument stored = documentApi.upload(file, parsePurpose(purpose));
		return new UploadedExamDocumentView(stored.mediaId(), stored.fileName());
	}

	@Transactional(readOnly = true)
	public ExamDocumentLinkView link(UUID examId, String purpose) {
		Exam exam = configService.loadKlassExam(examId);
		accessGuard.requireAuthoringAccess(exam.getCourseId(), PermissionAction.VIEW);
		Purpose parsed = parsePurpose(purpose);
		UUID mediaId = parsed == Purpose.QUESTION_PAPER ? exam.getQuestionPaperMediaId()
				: exam.getMarkingSchemeMediaId();
		StoredDocument document = mediaId == null ? null : documentApi.findLive(mediaId, parsed).orElse(null);
		if (document == null) {
			throw new NotFoundException("This exam has no such document");
		}
		String url = documentApi.signUrl(mediaId, parsed, DOCUMENT_URL_TTL)
			.orElseThrow(() -> new NotFoundException("This exam has no such document"));
		return new ExamDocumentLinkView(url, document.fileName());
	}

	private static Purpose parsePurpose(String purpose) {
		if ("QUESTION_PAPER".equals(purpose)) {
			return Purpose.QUESTION_PAPER;
		}
		if ("MARKING_SCHEME".equals(purpose)) {
			return Purpose.MARKING_SCHEME;
		}
		throw new FieldValidationException("Unknown document purpose",
				List.of(new FieldError("purpose", "Use QUESTION_PAPER or MARKING_SCHEME")));
	}

}

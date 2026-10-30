package com.lms.exammanagement.service.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.Purpose;
import com.lms.contentmanagement.api.ExamDocumentMediaApi.StoredDocument;
import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamType;
import com.lms.exammanagement.repository.ExamEnrollmentRepository;
import com.lms.exammanagement.repository.ExamRepository;
import com.lms.exammanagement.repository.ExamResultRepository;
import com.lms.exammanagement.repository.ExamSubmissionDocumentRepository;
import com.lms.exammanagement.support.ExamAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Security review L3: results published while a marked paper is being stored (outside any
 * transaction) must still block the write - the in-transaction callback re-loads the exam under
 * the exam lock and re-checks "not published" before touching {@code exam_result}.
 */
class ExamMarkingServiceMarkedPaperRaceTest {

	private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

	private final UUID tenantId = UUID.randomUUID();

	private final UUID examId = UUID.randomUUID();

	private final UUID studentId = UUID.randomUUID();

	private final ExamConfigService configService = mock(ExamConfigService.class);

	private final ExamRepository examRepository = mock(ExamRepository.class);

	private final ExamEnrollmentRepository enrollmentRepository = mock(ExamEnrollmentRepository.class);

	private final ExamResultRepository resultRepository = mock(ExamResultRepository.class);

	private final ExamDocumentMediaApi documentApi = mock(ExamDocumentMediaApi.class);

	private final AuditLogApi auditLogApi = mock(AuditLogApi.class);

	private ExamMarkingService service;

	@BeforeEach
	void setUp() {
		TenantContext tenantContext = mock(TenantContext.class);
		when(tenantContext.getTenantId()).thenReturn(tenantId);
		service = new ExamMarkingService(configService, mock(ExamManagementService.class), examRepository,
				enrollmentRepository, resultRepository, mock(ExamSubmissionDocumentRepository.class), documentApi,
				mock(ExamAccessGuard.class), auditLogApi, tenantContext, Clock.fixed(NOW, ZoneOffset.UTC));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), tenantId, "TEACHER",
				UUID.randomUUID()));
		when(enrollmentRepository.existsByExamIdAndStudentId(any(), eq(studentId))).thenReturn(true);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	private Exam writtenExam(Instant resultsPublishedAt) {
		Exam exam = Exam.klassExam(tenantId, UUID.randomUUID(), ExamType.WRITTEN, "Essay",
				NOW.minus(2, ChronoUnit.HOURS), NOW.minus(1, ChronoUnit.HOURS), 60);
		exam.setResultsPublishedAt(resultsPublishedAt);
		return exam;
	}

	@SuppressWarnings("unchecked")
	@Test
	void resultsPublishedDuringTheUploadBlockTheMarkedPaperWrite() {
		// Unpublished when the request starts, published by the time the callback runs.
		when(configService.loadKlassExam(examId)).thenReturn(writtenExam(null), writtenExam(NOW));
		when(documentApi.uploadAll(any(), eq(Purpose.MARKED_PAPER), any())).thenAnswer(invocation -> {
			Consumer<List<StoredDocument>> callback = invocation.getArgument(2);
			callback.accept(List.of(new StoredDocument(UUID.randomUUID(), "marked.pdf", UUID.randomUUID())));
			return List.of();
		});

		assertThatThrownBy(() -> service.uploadMarkedPaper(examId, studentId,
				new MockMultipartFile("file", "marked.pdf", "application/pdf", new byte[] { 1 })))
			.isInstanceOfSatisfying(ExamStateException.class,
					e -> assertThat(e.getErrorCode()).isEqualTo(ExamStateException.RESULTS_PUBLISHED));

		InOrder order = inOrder(configService, examRepository);
		order.verify(configService).loadKlassExam(examId);
		order.verify(examRepository).acquireExamConfigLock(anyString());
		order.verify(configService).loadKlassExam(examId);
		verify(resultRepository, never()).saveAndFlush(any());
		verify(auditLogApi, never()).record(any());
	}

}

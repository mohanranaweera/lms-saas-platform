package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import com.lms.exammanagement.repository.ExamRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link ExamStatusAdvanceWriter} - the
 * {@code REQUIRES_NEW} collaborator {@link ExamLifecycleService} delegates
 * to. A plain unit test cannot exercise the real {@code REQUIRES_NEW}
 * transaction propagation itself (that requires a real Spring transaction
 * manager - see {@code ExamLifecycleClockBoundaryIntegrationTest} for that
 * proof); this covers the method's own read-modify-write logic: it re-loads
 * by id rather than trusting a passed-in reference, and it never regresses an
 * already-more-advanced persisted status.
 */
@ExtendWith(MockitoExtension.class)
class ExamStatusAdvanceWriterTest {

	private static final Instant START = Instant.parse("2026-01-10T10:00:00Z");

	private static final Instant END = Instant.parse("2026-01-10T12:00:00Z");

	@Mock
	private ExamRepository examRepository;

	private ExamStatusAdvanceWriter writer;

	@BeforeEach
	void setUp() {
		// Constructed here, not as a field initializer, because Mockito injects
		// @Mock fields after instance construction but before @BeforeEach runs -
		// a field initializer would have captured a still-null examRepository.
		writer = new ExamStatusAdvanceWriter(examRepository);
	}

	@Test
	void advancesTheFreshlyReloadedExamToTheTargetStatusAndSavesIt() {
		UUID examId = UUID.randomUUID();
		Exam reloaded = new Exam(UUID.randomUUID(), UUID.randomUUID(), "Midterm", START, END, 60,
				ExamStatus.SCHEDULED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(reloaded));

		writer.advanceStatus(examId, ExamStatus.PUBLISHED);

		assertThat(reloaded.getStatus()).isEqualTo(ExamStatus.PUBLISHED);
		verify(examRepository).save(reloaded);
	}

	@Test
	void neverRegressesAnAlreadyMoreAdvancedPersistedStatus() {
		UUID examId = UUID.randomUUID();
		// Simulates a concurrent request having already committed CLOSED before
		// this call's (stale) computed target of PUBLISHED runs.
		Exam alreadyClosed = new Exam(UUID.randomUUID(), UUID.randomUUID(), "Midterm", START, END, 60,
				ExamStatus.CLOSED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(alreadyClosed));

		writer.advanceStatus(examId, ExamStatus.PUBLISHED);

		assertThat(alreadyClosed.getStatus()).isEqualTo(ExamStatus.CLOSED);
		verify(examRepository, never()).save(alreadyClosed);
	}

	@Test
	void isANoOpWhenTheTargetStatusEqualsTheAlreadyReloadedStatus() {
		UUID examId = UUID.randomUUID();
		Exam alreadyPublished = new Exam(UUID.randomUUID(), UUID.randomUUID(), "Midterm", START, END, 60,
				ExamStatus.PUBLISHED);
		when(examRepository.findById(examId)).thenReturn(Optional.of(alreadyPublished));

		writer.advanceStatus(examId, ExamStatus.PUBLISHED);

		assertThat(alreadyPublished.getStatus()).isEqualTo(ExamStatus.PUBLISHED);
		verify(examRepository, never()).save(alreadyPublished);
	}

	@Test
	void isASilentNoOpWhenTheExamCannotBeReloadedById() {
		UUID examId = UUID.randomUUID();
		when(examRepository.findById(examId)).thenReturn(Optional.empty());

		writer.advanceStatus(examId, ExamStatus.CLOSED);

		verify(examRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

}

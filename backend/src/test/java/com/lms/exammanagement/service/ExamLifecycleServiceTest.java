package com.lms.exammanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-based unit coverage for {@link ExamLifecycleService} - the
 * window-based lazy {@code SCHEDULED -> PUBLISHED -> CLOSED} status
 * advancement (MVP-017 plan §7's boxed note, §18's "rejects before {@code
 * scheduled_start} and after {@code scheduled_end} using an injected {@code
 * Clock}, allows strictly inside the window" requirement). Every case here
 * uses a {@link Clock#fixed} instance, never {@code Instant.now()}, so
 * window-boundary behavior is deterministic.
 *
 * <p>{@link ExamStatusAdvanceWriter} (the {@code REQUIRES_NEW} collaborator
 * that now owns the actual persisted write - see {@link ExamLifecycleService}'s
 * class javadoc for why) is mocked here rather than real: this is a pure unit
 * test with no Spring context and no real transaction manager, so it cannot
 * itself prove commit-independence across a real rollback boundary. What it
 * DOES prove at this level: (a) {@code resolveCurrentStatus} never mutates the
 * passed-in {@link Exam} entity anymore (the old bug's root cause) - the
 * caller must rely solely on the returned value; (b) the writer is invoked
 * with the correct {@code examId}/target status exactly when the computed
 * live status actually differs from the stored one, and never otherwise (the
 * cheap guard that avoids paying for a {@code REQUIRES_NEW} transaction on
 * every call). The real proof that the write survives a caller-side rollback
 * is {@code ExamLifecycleClockBoundaryIntegrationTest} (real Testcontainers
 * Postgres, real Spring transaction manager, asserts the DB row directly) -
 * deliberately not re-invented here as an artificial unit-level workaround.
 */
@ExtendWith(MockitoExtension.class)
class ExamLifecycleServiceTest {

	private static final Instant START = Instant.parse("2026-01-10T10:00:00Z");

	private static final Instant END = Instant.parse("2026-01-10T12:00:00Z");

	@Mock
	private ExamStatusAdvanceWriter examStatusAdvanceWriter;

	private static Exam scheduledExam() {
		return new Exam(UUID.randomUUID(), UUID.randomUUID(), "Midterm", START, END, 60, ExamStatus.SCHEDULED);
	}

	private static Exam publishedExam() {
		return new Exam(UUID.randomUUID(), UUID.randomUUID(), "Midterm", START, END, 60, ExamStatus.PUBLISHED);
	}

	private ExamLifecycleService serviceAt(Instant instant) {
		return new ExamLifecycleService(Clock.fixed(instant, ZoneOffset.UTC), examStatusAdvanceWriter);
	}

	@Test
	void nowReturnsTheInjectedClocksInstantNeverTheSystemClock() {
		Instant fixed = Instant.parse("2026-05-01T00:00:00Z");
		ExamLifecycleService service = serviceAt(fixed);

		assertThat(service.now()).isEqualTo(fixed);
	}

	@Test
	void aDraftExamNeverAdvancesRegardlessOfHowFarPastTheWindowTheClockIsAndNeverTriggersAWrite() {
		Exam draft = new Exam(UUID.randomUUID(), UUID.randomUUID(), "Draft exam", START, END, 60, ExamStatus.DRAFT);
		ExamLifecycleService service = serviceAt(END.plusSeconds(3600));

		ExamStatus resolved = service.resolveCurrentStatus(draft);

		assertThat(resolved).isEqualTo(ExamStatus.DRAFT);
		verifyNoInteractions(examStatusAdvanceWriter);
	}

	@Test
	void aScheduledExamStaysScheduledStrictlyBeforeScheduledStartAndNeverTriggersAWrite() {
		Exam exam = scheduledExam();
		ExamLifecycleService service = serviceAt(START.minusSeconds(1));

		ExamStatus resolved = service.resolveCurrentStatus(exam);

		assertThat(resolved).isEqualTo(ExamStatus.SCHEDULED);
		verifyNoInteractions(examStatusAdvanceWriter);
	}

	@Test
	void aScheduledExamAdvancesToPublishedTheInstantScheduledStartArrivesAndDelegatesTheWriteWithoutMutatingTheEntity() {
		Exam exam = scheduledExam();
		ExamLifecycleService service = serviceAt(START);

		ExamStatus resolved = service.resolveCurrentStatus(exam);

		assertThat(resolved).isEqualTo(ExamStatus.PUBLISHED);
		// The old bug's root cause: this must NOT be mutated in place anymore -
		// the caller must rely solely on the returned resolved value, since the
		// persisted write now happens out-of-band via the REQUIRES_NEW writer.
		assertThat(exam.getStatus()).isEqualTo(ExamStatus.SCHEDULED);
		verify(examStatusAdvanceWriter).advanceStatus(exam.getId(), ExamStatus.PUBLISHED);
	}

	@Test
	void aPublishedExamStaysPublishedStrictlyBeforeScheduledEndAndNeverTriggersAWrite() {
		Exam exam = publishedExam();
		ExamLifecycleService service = serviceAt(END.minusSeconds(1));

		ExamStatus resolved = service.resolveCurrentStatus(exam);

		assertThat(resolved).isEqualTo(ExamStatus.PUBLISHED);
		verifyNoInteractions(examStatusAdvanceWriter);
	}

	@Test
	void aPublishedExamAdvancesToClosedTheInstantScheduledEndArrivesAndDelegatesTheWriteWithoutMutatingTheEntity() {
		Exam exam = publishedExam();
		ExamLifecycleService service = serviceAt(END);

		ExamStatus resolved = service.resolveCurrentStatus(exam);

		assertThat(resolved).isEqualTo(ExamStatus.CLOSED);
		assertThat(exam.getStatus()).isEqualTo(ExamStatus.PUBLISHED);
		verify(examStatusAdvanceWriter).advanceStatus(exam.getId(), ExamStatus.CLOSED);
	}

	@Test
	void aScheduledExamCanAdvanceThroughBothTransitionsInOneCallWhenBothBoundariesHaveAlreadyPassed() {
		Exam exam = scheduledExam();
		ExamLifecycleService service = serviceAt(END.plusSeconds(1));

		ExamStatus resolved = service.resolveCurrentStatus(exam);

		assertThat(resolved).isEqualTo(ExamStatus.CLOSED);
		// A single delegated write straight to the final resolved status - not
		// one write per intermediate transition.
		verify(examStatusAdvanceWriter, times(1)).advanceStatus(exam.getId(), ExamStatus.CLOSED);
		verify(examStatusAdvanceWriter, never()).advanceStatus(exam.getId(), ExamStatus.PUBLISHED);
	}

	@Test
	void aClosedExamNeverRevertsAndNeverTriggersAWrite() {
		Exam exam = new Exam(UUID.randomUUID(), UUID.randomUUID(), "Midterm", START, END, 60, ExamStatus.CLOSED);
		ExamLifecycleService service = serviceAt(START.minusSeconds(3600));

		ExamStatus resolved = service.resolveCurrentStatus(exam);

		assertThat(resolved).isEqualTo(ExamStatus.CLOSED);
		verifyNoInteractions(examStatusAdvanceWriter);
	}

	@Test
	void recomputingTwiceAtTheSameInstantIsIdempotentAndReturnsTheSameResolvedStatusEachDelegatingItsOwnWrite() {
		Exam exam = scheduledExam();
		ExamLifecycleService service = serviceAt(END.plusSeconds(1));

		ExamStatus first = service.resolveCurrentStatus(exam);
		ExamStatus second = service.resolveCurrentStatus(exam);

		assertThat(first).isEqualTo(ExamStatus.CLOSED);
		assertThat(second).isEqualTo(ExamStatus.CLOSED);
		// exam.getStatus() is never mutated by this method, so both calls
		// recompute from the same stored SCHEDULED value and each independently
		// delegates a (harmless, idempotent-at-the-DB-level) write.
		verify(examStatusAdvanceWriter, times(2)).advanceStatus(exam.getId(), ExamStatus.CLOSED);
	}

}

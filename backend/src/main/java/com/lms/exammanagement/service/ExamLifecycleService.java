package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.Exam;
import com.lms.exammanagement.domain.ExamStatus;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * The lazy {@code SCHEDULED -> PUBLISHED -> CLOSED} status advancement (plan
 * §7's boxed note, §9) - mirrors {@code
 * enrollmentmanagement.service.EnrollmentExpiryService}'s "computed live, one
 * idempotent guarded write" pattern.
 *
 * <p><b>Why the persisted write is delegated to a {@code REQUIRES_NEW}
 * sub-transaction (revised - see below for the superseded approach).</b> An
 * earlier version of this method mutated the passed, already-managed {@code
 * exam} entity in place and relied on the caller's own ambient {@code
 * @Transactional} method to flush it via ordinary Hibernate dirty-checking.
 * That looked harmless but every access-check caller ({@code
 * ExamAttemptService#requireAttemptableWindow} chief among them) immediately
 * throws a business exception in the SAME transactional method whenever the
 * resolved status means the request should be rejected (exam not yet open /
 * window closed) - precisely the case that most needs the advanced status to
 * stick. Spring's default rollback-on-unchecked-exception behavior then rolls
 * back the entire transaction, silently discarding the correct status
 * mutation along with it, so the persisted {@code exam.status} column could
 * get stuck at a stale value forever (caught by {@code
 * ExamLifecycleClockBoundaryIntegrationTest}). Access-control decisions were
 * never wrong - every call recomputes the live status fresh from {@code
 * scheduled_start}/{@code scheduled_end} vs. the injected {@link Clock}
 * regardless of what is persisted - but anything that reads the raw {@code
 * status} column directly (admin dashboards, {@code
 * idx_exam_tenant_status_scheduled_start}-based queries, a Teacher/staff
 * {@code ExamResponse}) could see a permanently-frozen value.
 *
 * <p>The fix: this method no longer mutates the passed-in {@code exam} at
 * all - it is purely a read/compute. Once it determines the live status
 * differs from the stored one, the actual write is delegated to {@link
 * ExamStatusAdvanceWriter#advanceStatus}, a small collaborator whose method
 * is annotated {@code @Transactional(propagation = REQUIRES_NEW)} and
 * re-loads the {@link Exam} by id (tenant-scoped, via {@code ExamRepository})
 * inside its own sub-transaction rather than reusing the entity reference
 * passed in from the caller's outer persistence context - called BEFORE the
 * caller goes on to decide whether to throw, so the write commits
 * independently of the outer transaction's eventual outcome, exactly
 * mirroring {@code EnrollmentExpiryEventWriter}'s established shape.
 *
 * <p>The {@code REQUIRES_NEW} write only runs when the computed live status
 * actually differs from {@code exam.getStatus()} as already loaded by the
 * caller - a cheap, in-memory guard (mirroring {@code
 * EnrollmentExpiryService}'s own "cheap, read-only, no-new-transaction
 * pre-check" comment) that avoids paying for a transaction begin/commit and
 * connection checkout on the overwhelmingly common case (a request mid-window
 * or against an already-{@code DRAFT}/already-{@code CLOSED} exam, neither of
 * which this computation ever advances), since {@code
 * resolveCurrentStatus}/this replacement is called on essentially every
 * attempt-related request (start/answer/submit).
 *
 * <p>Every "current time" check in {@code exam-management} goes through the
 * injected {@link Clock} (never {@code Instant.now()} directly), so
 * window-boundary behavior stays deterministically unit-testable.
 */
@Service
public class ExamLifecycleService {

	private final Clock clock;

	private final ExamStatusAdvanceWriter examStatusAdvanceWriter;

	public ExamLifecycleService(Clock clock, ExamStatusAdvanceWriter examStatusAdvanceWriter) {
		this.clock = clock;
		this.examStatusAdvanceWriter = examStatusAdvanceWriter;
	}

	public Instant now() {
		return clock.instant();
	}

	/**
	 * Recomputes the live {@code exam.status} from {@code scheduled_start}/
	 * {@code scheduled_end} vs. the injected {@link Clock} - never mutates
	 * the passed {@code exam} entity itself. When the computed status has
	 * advanced past what {@code exam.getStatus()} already holds, the
	 * persisted write is delegated to {@link ExamStatusAdvanceWriter}, which
	 * commits in its own {@code REQUIRES_NEW} sub-transaction independently
	 * of whatever this method's caller does afterward (including throwing).
	 * @return the resolved, live status - never the possibly-stale value
	 * {@code exam.getStatus()} held before this call. Callers must use this
	 * return value for their own in-request decision; they must never
	 * re-read {@code exam.getStatus()} afterward expecting it to reflect the
	 * resolved value; it stays unchanged.
	 */
	public ExamStatus resolveCurrentStatus(Exam exam) {
		ExamStatus storedStatus = exam.getStatus();
		ExamStatus resolvedStatus = computeLiveStatus(storedStatus, exam.getScheduledStart(), exam.getScheduledEnd());
		if (resolvedStatus != storedStatus) {
			examStatusAdvanceWriter.advanceStatus(exam.getId(), resolvedStatus);
		}
		return resolvedStatus;
	}

	private ExamStatus computeLiveStatus(ExamStatus storedStatus, Instant scheduledStart, Instant scheduledEnd) {
		Instant currentInstant = now();
		ExamStatus status = storedStatus;
		if (status == ExamStatus.SCHEDULED && !currentInstant.isBefore(scheduledStart)) {
			status = ExamStatus.PUBLISHED;
		}
		if (status == ExamStatus.PUBLISHED && !currentInstant.isBefore(scheduledEnd)) {
			status = ExamStatus.CLOSED;
		}
		return status;
	}

}

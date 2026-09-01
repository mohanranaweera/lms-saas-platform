package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link EnrollmentExpiryService#resolveAccessState}'s
 * pure-logic matrix, closing plan §18's unit-level requirement (not
 * previously covered by any existing test - {@code
 * EnrollmentActivationServiceTest} covers activation, not the live
 * access-state computation this class owns). Covers every state named in the
 * plan: never activated, active/no expiry (lifetime), active/not-yet-expired,
 * expired/no reactivation request, expired/open request already exists.
 *
 * <p>The expiry-event write's own "exactly once, race-safe" guarantee moved
 * to {@link EnrollmentExpiryEventWriter} (its own {@code REQUIRES_NEW}
 * transaction - see that class's javadoc for why) - this test now only
 * verifies {@link EnrollmentExpiryService} correctly DELEGATES to it exactly
 * when the resolved state is expired, never when active/never-enrolled;
 * {@code EnrollmentExpiryEventWriterTest} covers the write's own
 * idempotency/race behavior directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnrollmentExpiryServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID STUDENT_ID = UUID.randomUUID();

	private static final UUID COURSE_ID = UUID.randomUUID();

	@Mock
	private EnrollmentRepository enrollmentRepository;

	@Mock
	private EnrollmentExpiryEventWriter enrollmentExpiryEventWriter;

	@Mock
	private ReactivationRequestRepository reactivationRequestRepository;

	private EnrollmentExpiryService service;

	@BeforeEach
	void setUp() {
		service = new EnrollmentExpiryService(enrollmentRepository, enrollmentExpiryEventWriter,
				reactivationRequestRepository);
	}

	@Test
	void neverActivatedReportsNeverEnrolled() {
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.empty());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.NEVER_ENROLLED);
		assertThat(state.enrollmentId()).isNull();
		assertThat(state.accessExpiresAt()).isNull();
		assertThat(state.canRequestReactivation()).isFalse();
		verify(enrollmentExpiryEventWriter, never()).recordExpiryEventIfAbsent(any());
	}

	@Test
	void activeWithNoExpiryLifetimeAccessIsReportedAsActive() {
		Enrollment enrollment = enrollmentFixture(null);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.ACTIVE);
		assertThat(state.accessExpiresAt()).isNull();
		assertThat(state.canRequestReactivation()).isFalse();
		verify(enrollmentExpiryEventWriter, never()).recordExpiryEventIfAbsent(any());
	}

	@Test
	void activeAndNotYetExpiredIsReportedAsActive() {
		Instant future = Instant.now().plusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(future);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.ACTIVE);
		assertThat(state.accessExpiresAt()).isEqualTo(future);
		verify(enrollmentExpiryEventWriter, never()).recordExpiryEventIfAbsent(any());
	}

	@Test
	void expiredWithNoOpenReactivationRequestAllowsRequestingOne() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(enrollmentExpiryEventWriter.alreadyRecorded(enrollment.getId())).thenReturn(false);
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.accessExpiresAt()).isEqualTo(past);
		assertThat(state.canRequestReactivation()).isTrue();
		verify(enrollmentExpiryEventWriter, times(1)).recordExpiryEventIfAbsent(enrollment.getId());
	}

	/**
	 * Fix for the REQUIRES_NEW-per-already-expired-enrollment overhead: when
	 * the cheap, no-new-transaction {@link EnrollmentExpiryEventWriter#alreadyRecorded}
	 * pre-check reports {@code true} (the overwhelmingly common case for an
	 * enrollment that has been observed as expired before), the full
	 * REQUIRES_NEW write path ({@code recordExpiryEventIfAbsent}) must never
	 * be invoked at all.
	 */
	@Test
	void whenTheCheapPreCheckReportsAlreadyRecordedTheGuardedWritePathIsNeverInvoked() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(enrollmentExpiryEventWriter.alreadyRecorded(enrollment.getId())).thenReturn(true);
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		verify(enrollmentExpiryEventWriter, times(1)).alreadyRecorded(enrollment.getId());
		verify(enrollmentExpiryEventWriter, never()).recordExpiryEventIfAbsent(any());
	}

	@Test
	void expiredWithAnOpenReactivationRequestAlreadyPendingDisallowsRequestingAnother() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.of(new ReactivationRequest(TENANT_ID, enrollment.getId(), STUDENT_ID)));

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.canRequestReactivation()).isFalse();
		verify(enrollmentExpiryEventWriter, times(1)).recordExpiryEventIfAbsent(enrollment.getId());
	}

	/**
	 * {@link EnrollmentExpiryService} delegates to {@link
	 * EnrollmentExpiryEventWriter} on EVERY resolve call while expired - the
	 * "exactly once, ever" write guarantee is {@code
	 * EnrollmentExpiryEventWriter}'s own responsibility now (its internal
	 * existence pre-check + the schema-enforced unique index), not something
	 * this service tracks or short-circuits itself.
	 */
	@Test
	void delegatesToTheExpiryEventWriterOnEveryResolveCallWhileExpired() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		service.resolveAccessState(STUDENT_ID, COURSE_ID);
		service.resolveAccessState(STUDENT_ID, COURSE_ID);

		verify(enrollmentExpiryEventWriter, times(2)).recordExpiryEventIfAbsent(enrollment.getId());
	}

	/**
	 * {@link EnrollmentExpiryEventWriter#recordExpiryEventIfAbsent} is
	 * documented to propagate {@link DataIntegrityViolationException} on a
	 * lost race (see that class's javadoc for why it must not swallow it
	 * itself). This proves {@link EnrollmentExpiryService} is the layer that
	 * actually catches it - fully inside {@code resolveAccessState}'s own
	 * body, never re-thrown - so the caller still gets a correct EXPIRED
	 * response rather than a propagated exception.
	 */
	@Test
	void aRaceLostWhileWritingTheExpiryEventStillResolvesToExpired() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());
		doThrow(new DataIntegrityViolationException("uq_enrollment_expiry_event_tenant_enrollment_type"))
			.when(enrollmentExpiryEventWriter)
			.recordExpiryEventIfAbsent(enrollment.getId());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.canRequestReactivation()).isTrue();
	}

	/**
	 * A {@link DataIntegrityViolationException} whose root cause does NOT
	 * reference {@code uq_enrollment_expiry_event_tenant_enrollment_type} is
	 * an unexpected integrity violation (e.g. a different constraint, or a
	 * genuinely corrupt write) and must never be silently swallowed
	 * alongside the expected lost-race case - it must propagate out of
	 * {@code resolveAccessState}.
	 */
	@Test
	void aDataIntegrityViolationNotMatchingTheExpectedConstraintIsRethrownNotSwallowed() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		doThrow(new DataIntegrityViolationException("some_other_unrelated_constraint_violation"))
			.when(enrollmentExpiryEventWriter)
			.recordExpiryEventIfAbsent(enrollment.getId());

		assertThatThrownBy(() -> service.resolveAccessState(STUDENT_ID, COURSE_ID))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("some_other_unrelated_constraint_violation");
	}

	/**
	 * Plan §18's named matrix cell "expired, current row + reactivated": a
	 * CURRENT row that is itself the product of a prior reactivation (i.e.
	 * {@code reactivatedFromEnrollmentId != null}) must resolve identically
	 * to a first-time-activated current row once it too lapses -
	 * {@code resolveAccessState}/{@code isCurrentlyActive} never inspect
	 * {@code reactivatedFromEnrollmentId}, only {@code supersededAt}/{@code
	 * accessExpiresAt}, so this proves that by direct observation rather than
	 * by inspection of the implementation.
	 */
	@Test
	void expiredAndTheCurrentRowIsItselfAPriorReactivationIsReportedAsExpired() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = reactivatedEnrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.accessExpiresAt()).isEqualTo(past);
		assertThat(state.canRequestReactivation()).isTrue();
		verify(enrollmentExpiryEventWriter, times(1)).recordExpiryEventIfAbsent(enrollment.getId());
	}

	private static Enrollment enrollmentFixture(Instant accessExpiresAt) {
		Enrollment enrollment = Enrollment.fromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID, UUID.randomUUID(),
				accessExpiresAt);
		ReflectionTestUtils.setField(enrollment, "id", UUID.randomUUID());
		return enrollment;
	}

	private static Enrollment reactivatedEnrollmentFixture(Instant accessExpiresAt) {
		Enrollment enrollment = Enrollment.reactivatedFromConfirmedPayment(TENANT_ID, STUDENT_ID, COURSE_ID,
				UUID.randomUUID(), accessExpiresAt, UUID.randomUUID());
		ReflectionTestUtils.setField(enrollment, "id", UUID.randomUUID());
		return enrollment;
	}

}

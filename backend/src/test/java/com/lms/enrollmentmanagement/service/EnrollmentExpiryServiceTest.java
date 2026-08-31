package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.EnrollmentExpiryEventType;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.repository.EnrollmentExpiryEventRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-only unit coverage for {@link EnrollmentExpiryService#resolveAccessState}'s
 * pure-logic matrix, closing plan §18's unit-level requirement (not
 * previously covered by any existing test - {@code
 * EnrollmentActivationServiceTest} covers activation, not the live
 * access-state computation this class owns). Covers every state named in the
 * plan: never activated, active/no expiry (lifetime), active/not-yet-expired,
 * expired/no reactivation request, expired/open request already exists, and
 * the lazy {@code enrollment_expiry_event} write happening exactly once
 * across repeated calls (idempotency).
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
	private EnrollmentExpiryEventRepository enrollmentExpiryEventRepository;

	@Mock
	private ReactivationRequestRepository reactivationRequestRepository;

	@Mock
	private TenantContext tenantContext;

	private EnrollmentExpiryService service;

	@BeforeEach
	void setUp() {
		service = new EnrollmentExpiryService(enrollmentRepository, enrollmentExpiryEventRepository,
				reactivationRequestRepository, tenantContext);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
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
		verify(enrollmentExpiryEventRepository, never()).saveAndFlush(any());
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
		verify(enrollmentExpiryEventRepository, never()).saveAndFlush(any());
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
		verify(enrollmentExpiryEventRepository, never()).saveAndFlush(any());
	}

	@Test
	void expiredWithNoOpenReactivationRequestAllowsRequestingOne() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollment.getId(),
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(false);
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.accessExpiresAt()).isEqualTo(past);
		assertThat(state.canRequestReactivation()).isTrue();
		verify(enrollmentExpiryEventRepository, times(1)).saveAndFlush(any());
	}

	@Test
	void expiredWithAnOpenReactivationRequestAlreadyPendingDisallowsRequestingAnother() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollment.getId(),
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(true);
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.of(new ReactivationRequest(TENANT_ID, enrollment.getId(), STUDENT_ID)));

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.canRequestReactivation()).isFalse();
	}

	/**
	 * The lazy {@code enrollment_expiry_event} write must happen exactly once
	 * across repeated live-check calls past the same expiry - the second call
	 * finds the pre-check already {@code true} (as it would once the first
	 * call's write has landed) and must never attempt a second save.
	 */
	@Test
	void theExpiryEventWriteHappensExactlyOnceAcrossRepeatedCalls() {
		Instant past = Instant.now().minusSeconds(3600);
		Enrollment enrollment = enrollmentFixture(past);
		when(enrollmentRepository.findCurrentByStudentIdAndCourseId(STUDENT_ID, COURSE_ID))
			.thenReturn(Optional.of(enrollment));
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollment.getId(),
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(false).thenReturn(true);
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		service.resolveAccessState(STUDENT_ID, COURSE_ID);
		service.resolveAccessState(STUDENT_ID, COURSE_ID);

		verify(enrollmentExpiryEventRepository, times(1)).saveAndFlush(any());
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
		when(enrollmentExpiryEventRepository.existsByEnrollmentIdAndEventType(enrollment.getId(),
				EnrollmentExpiryEventType.EXPIRED)).thenReturn(false);
		when(reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId()))
			.thenReturn(Optional.empty());

		EnrollmentAccessState state = service.resolveAccessState(STUDENT_ID, COURSE_ID);

		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(state.accessExpiresAt()).isEqualTo(past);
		assertThat(state.canRequestReactivation()).isTrue();
		verify(enrollmentExpiryEventRepository, times(1)).saveAndFlush(any());
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

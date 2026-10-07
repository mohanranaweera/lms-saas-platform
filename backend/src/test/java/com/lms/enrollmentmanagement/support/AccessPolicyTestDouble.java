package com.lms.enrollmentmanagement.support;

import static org.mockito.Mockito.mock;

import com.lms.common.error.DeviceNotAuthorizedException;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.ContentAvailability;
import com.lms.enrollmentmanagement.api.CourseAccessDecision;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.repository.CourseAccessPolicyRepository;
import com.lms.enrollmentmanagement.repository.EnrollmentAccessExtensionRepository;
import com.lms.enrollmentmanagement.repository.TenantAccessPolicyRepository;
import com.lms.enrollmentmanagement.service.AccessPolicyService;
import com.lms.enrollmentmanagement.service.AccessWindowCalculator;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Unit-test stand-in for {@link AccessPolicyApi} (Wave 10) that keeps the
 * pre-Wave-10 guard tests meaningful: the entitlement answer comes from the
 * test own {@link EnrollmentAccessApi} mock (so existing {@code
 * when(enrollmentAccessApi.resolveAccessState(...))} stubs and verifications
 * still drive the outcome), no device restriction applies, and the content
 * window mirrors {@link AccessPolicyService}. Device limits and
 * windows are covered for real by the Wave 10 integration tests.
 */
public final class AccessPolicyTestDouble implements AccessPolicyApi {

	private final EnrollmentAccessApi enrollmentAccessApi;

	private AccessPolicyTestDouble(EnrollmentAccessApi enrollmentAccessApi) {
		this.enrollmentAccessApi = enrollmentAccessApi;
	}

	public static AccessPolicyApi backedBy(EnrollmentAccessApi enrollmentAccessApi) {
		return new AccessPolicyTestDouble(enrollmentAccessApi);
	}

	/** A real calculator over mocked repositories: no extensions, no grace, no course overrides. */
	public static AccessWindowCalculator calculatorWithoutOverrides() {
		return new AccessWindowCalculator(mock(EnrollmentAccessExtensionRepository.class),
				mock(TenantAccessPolicyRepository.class), mock(CourseAccessPolicyRepository.class));
	}

	@Override
	public CourseAccessDecision evaluateStudentCourseAccess(UUID studentId, UUID courseId) {
		EnrollmentAccessState state = enrollmentAccessApi.resolveAccessState(studentId, courseId);
		if (state == null) {
			return new CourseAccessDecision(AccessOutcome.NOT_ENROLLED, null, null, null, false, false);
		}
		AccessOutcome outcome = switch (state.state()) {
			case ACTIVE -> AccessOutcome.ALLOWED;
			case EXPIRED -> AccessOutcome.EXPIRED;
			case NEVER_ENROLLED -> AccessOutcome.NOT_ENROLLED;
		};
		return new CourseAccessDecision(outcome, state.enrollmentId(), state.accessExpiresAt(),
				state.accessExpiresAt(), false, state.canRequestReactivation());
	}

	@Override
	public CourseAccessDecision requireStudentCourseAccess(UUID courseId,
			Supplier<? extends RuntimeException> deniedAsNotFound) {
		CourseAccessDecision decision = evaluateStudentCourseAccess(AuthenticatedPrincipalHolder.get().userId(),
				courseId);
		if (decision.outcome() == AccessOutcome.DEVICE_NOT_AUTHORIZED) {
			throw new DeviceNotAuthorizedException("device");
		}
		if (!decision.allowed()) {
			throw deniedAsNotFound.get();
		}
		return decision;
	}

	@Override
	public CourseAccessDecision requireStudentCourseAccessOnDevice(UUID studentId, UUID courseId, UUID deviceId,
			Supplier<? extends RuntimeException> deniedAsNotFound) {
		CourseAccessDecision decision = evaluateStudentCourseAccess(studentId, courseId);
		if (!decision.allowed()) {
			throw deniedAsNotFound.get();
		}
		return decision;
	}

	@Override
	public ContentAvailability evaluateContentWindow(Instant availableFrom, Instant availableUntil) {
		Instant now = Instant.now();
		if (availableFrom != null && availableFrom.isAfter(now)) {
			return ContentAvailability.NOT_YET_AVAILABLE;
		}
		if (availableUntil != null && availableUntil.isBefore(now)) {
			return ContentAvailability.EXPIRED;
		}
		return ContentAvailability.AVAILABLE;
	}

}

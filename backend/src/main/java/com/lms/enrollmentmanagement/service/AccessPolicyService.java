package com.lms.enrollmentmanagement.service;

import com.lms.common.error.DeviceNotAuthorizedException;
import com.lms.enrollmentmanagement.api.AccessOutcome;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.ContentAvailability;
import com.lms.enrollmentmanagement.api.CourseAccessDecision;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.domain.CourseAccessPolicy;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.CourseAccessPolicyRepository;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DeviceAccessApi;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link AccessPolicyApi} - see its javadoc. Entitlement and the
 * billing window come from {@link EnrollmentExpiryService#resolveAccessState}
 * (which itself uses {@link AccessWindowCalculator}, so extensions and grace
 * are already applied and the first observed expiry is still recorded); the
 * device dimension is delegated to identity-access-service through {@link
 * DeviceAccessApi}, with this course limit as the course level of the
 * precedence.
 */
@Service
public class AccessPolicyService implements AccessPolicyApi {

	private final EnrollmentExpiryService enrollmentExpiryService;

	private final EnrollmentRepository enrollmentRepository;

	private final AccessWindowCalculator accessWindowCalculator;

	private final CourseAccessPolicyRepository coursePolicyRepository;

	private final DeviceAccessApi deviceAccessApi;

	public AccessPolicyService(EnrollmentExpiryService enrollmentExpiryService,
			EnrollmentRepository enrollmentRepository, AccessWindowCalculator accessWindowCalculator,
			CourseAccessPolicyRepository coursePolicyRepository, DeviceAccessApi deviceAccessApi) {
		this.enrollmentExpiryService = enrollmentExpiryService;
		this.enrollmentRepository = enrollmentRepository;
		this.accessWindowCalculator = accessWindowCalculator;
		this.coursePolicyRepository = coursePolicyRepository;
		this.deviceAccessApi = deviceAccessApi;
	}

	@Override
	@Transactional
	public CourseAccessDecision evaluateStudentCourseAccess(UUID studentId, UUID courseId) {
		EnrollmentAccessState state = enrollmentExpiryService.resolveAccessState(studentId, courseId);
		if (state.state() == EnrollmentAccessStateType.NEVER_ENROLLED) {
			return new CourseAccessDecision(AccessOutcome.NOT_ENROLLED, null, null, null, false, false);
		}
		Enrollment enrollment = enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId)
			.orElseThrow();
		AccessWindowCalculator.AccessWindow window = accessWindowCalculator.windowFor(enrollment);
		if (state.state() == EnrollmentAccessStateType.EXPIRED) {
			return new CourseAccessDecision(AccessOutcome.EXPIRED, enrollment.getId(), window.effectiveExpiresAt(),
					window.accessEndsAt(), false, state.canRequestReactivation());
		}
		boolean inGrace = window.inGracePeriod(Instant.now());
		if (!isCurrentDeviceAuthorized(studentId, courseId)) {
			return new CourseAccessDecision(AccessOutcome.DEVICE_NOT_AUTHORIZED, enrollment.getId(),
					window.effectiveExpiresAt(), window.accessEndsAt(), inGrace, false);
		}
		return new CourseAccessDecision(AccessOutcome.ALLOWED, enrollment.getId(), window.effectiveExpiresAt(),
				window.accessEndsAt(), inGrace, false);
	}

	@Override
	@Transactional
	public CourseAccessDecision requireStudentCourseAccess(UUID courseId,
			Supplier<? extends RuntimeException> deniedAsNotFound) {
		CourseAccessDecision decision = evaluateStudentCourseAccess(AuthenticatedPrincipalHolder.get().userId(),
				courseId);
		switch (decision.outcome()) {
			case ALLOWED -> {
				return decision;
			}
			case DEVICE_NOT_AUTHORIZED -> throw new DeviceNotAuthorizedException(
					"This course can only be opened on a limited number of devices, and this device is not one of them.");
			default -> throw deniedAsNotFound.get();
		}
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

	/**
	 * The device check applies to the caller own session only - an evaluation
	 * on behalf of another student (or with no authenticated request) has no
	 * device to check.
	 */
	private boolean isCurrentDeviceAuthorized(UUID studentId, UUID courseId) {
		if (!AuthenticatedPrincipalHolder.isSet()) {
			return true;
		}
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!studentId.equals(principal.userId()) || principal.sessionId() == null) {
			return true;
		}
		Integer courseMaxDevices = coursePolicyRepository.findByCourseId(courseId)
			.map(CourseAccessPolicy::getMaxDevices)
			.orElse(null);
		return deviceAccessApi.isSessionDeviceAuthorized(studentId, principal.sessionId(), courseMaxDevices);
	}

}

package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live {@code resolveAccessState} computation (plan §7/§9) plus the
 * lazy, idempotent {@code enrollment_expiry_event} write (plan §4.1 step 3),
 * factored out of {@link EnrollmentAccessApiImpl} so it is unit-testable in
 * isolation (plan §18) and directly reusable by {@code
 * ReactivationRequestService#submit} (same-domain internal use, without
 * going through the {@code api} layer). The expiry-event write itself is
 * delegated to {@link EnrollmentExpiryEventWriter} - see that class's javadoc
 * for why it must run in its own {@code REQUIRES_NEW} transaction rather than
 * inline here (fixes a real 500 found by {@code
 * EnrollmentExpiryConcurrencyIntegrationTest} under genuine concurrent load).
 */
@Service
public class EnrollmentExpiryService {

	private static final Logger log = LoggerFactory.getLogger(EnrollmentExpiryService.class);

	/**
	 * The exact unique-constraint name (V22) whose violation is the ONLY
	 * expected, safely-ignorable "lost the race" case documented above and
	 * in {@link EnrollmentExpiryEventWriter}'s javadoc. A {@link
	 * DataIntegrityViolationException} whose root cause does not reference
	 * this constraint is an unexpected integrity violation and must be
	 * rethrown, never silently swallowed alongside the expected race.
	 */
	private static final String EXPECTED_LOST_RACE_CONSTRAINT = "uq_enrollment_expiry_event_tenant_enrollment_type";

	private final EnrollmentRepository enrollmentRepository;

	private final EnrollmentExpiryEventWriter enrollmentExpiryEventWriter;

	private final ReactivationRequestRepository reactivationRequestRepository;

	public EnrollmentExpiryService(EnrollmentRepository enrollmentRepository,
			EnrollmentExpiryEventWriter enrollmentExpiryEventWriter,
			ReactivationRequestRepository reactivationRequestRepository) {
		this.enrollmentRepository = enrollmentRepository;
		this.enrollmentExpiryEventWriter = enrollmentExpiryEventWriter;
		this.reactivationRequestRepository = reactivationRequestRepository;
	}

	/**
	 * @see com.lms.enrollmentmanagement.api.EnrollmentAccessApi#resolveAccessState(UUID, UUID)
	 */
	@Transactional
	public EnrollmentAccessState resolveAccessState(UUID studentId, UUID courseId) {
		Optional<Enrollment> current = enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId);
		if (current.isEmpty()) {
			return EnrollmentAccessState.neverEnrolled();
		}
		Enrollment enrollment = current.get();
		Instant now = Instant.now();
		if (enrollment.isCurrentlyActive(now)) {
			return EnrollmentAccessState.active(enrollment.getId(), enrollment.getAccessExpiresAt());
		}
		// Cheap, read-only, no-new-transaction pre-check (runs in whatever
		// transaction is already active, including this method's own) - the
		// overwhelmingly common case, once an enrollment has been observed
		// as expired once, is that the event is already recorded, so skip
		// straight to "nothing to do" without paying for a REQUIRES_NEW
		// transaction begin/commit and connection checkout. This is purely
		// an optimization: recordExpiryEventIfAbsent keeps its own internal
		// existence check too, since a race can still occur between this
		// pre-check and the guarded REQUIRES_NEW write below.
		if (!enrollmentExpiryEventWriter.alreadyRecorded(enrollment.getId())) {
			// Delegated to its own REQUIRES_NEW transaction - see
			// EnrollmentExpiryEventWriter's javadoc for why this write must not
			// run inside THIS method's ambient transaction, and for why the
			// exception is caught HERE (fully inside this method's own body,
			// never re-thrown) rather than inside the writer itself.
			try {
				enrollmentExpiryEventWriter.recordExpiryEventIfAbsent(enrollment.getId());
			}
			catch (DataIntegrityViolationException ex) {
				if (!referencesExpectedConstraint(ex)) {
					// Not the known, safely-ignorable lost-race case against
					// uq_enrollment_expiry_event_tenant_enrollment_type - an
					// unexpected integrity violation must not be silently
					// swallowed alongside the expected race.
					throw ex;
				}
				// Lost a race against a concurrent read past the same expiry -
				// uq_enrollment_expiry_event_tenant_enrollment_type (V22) already
				// has the row, which is success, not failure. The writer's own
				// REQUIRES_NEW transaction has already rolled back cleanly by
				// this point; catching here (not inside the writer) means this
				// method's own ambient transaction never sees the exception
				// cross its boundary, so it stays healthy and this call can
				// safely continue.
				log.debug(
						"Lost race writing enrollment_expiry_event for enrollment {}; already recorded by a concurrent reader",
						enrollment.getId());
			}
		}
		boolean hasOpenRequest = reactivationRequestRepository.findCurrentOpenByEnrollmentId(enrollment.getId())
			.isPresent();
		return EnrollmentAccessState.expired(enrollment.getId(), enrollment.getAccessExpiresAt(), !hasOpenRequest);
	}

	/**
	 * @return {@code true} only if {@code ex}'s root cause message references
	 * the specific unique constraint ({@link #EXPECTED_LOST_RACE_CONSTRAINT})
	 * that backs the expected, safely-ignorable lost-race case. A violation
	 * of any other constraint is a genuinely unexpected integrity error and
	 * must not be treated the same way.
	 */
	private static boolean referencesExpectedConstraint(DataIntegrityViolationException ex) {
		Throwable mostSpecificCause = ex.getMostSpecificCause();
		String message = mostSpecificCause != null ? mostSpecificCause.getMessage() : null;
		return message != null && message.contains(EXPECTED_LOST_RACE_CONSTRAINT);
	}

}

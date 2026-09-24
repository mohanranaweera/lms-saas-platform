package com.lms.liveclassmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.LiveClassProviderApi;
import com.lms.integrationmanagement.api.MeetingCreationResult;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import com.lms.liveclassmanagement.support.LiveClassAccessGuard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates Wave 4's two-phase schedule/retry-provisioning flow (plan §4
 * item 1), mirroring {@code
 * paymentmanagement.payment.service.PaymentInitiationService}'s exact
 * two-phase shape. Deliberately NOT {@code @Transactional} at either the
 * class or method level - {@link ClassSessionWriteService}'s two calls below
 * each open and commit their OWN short transaction, with {@link
 * LiveClassProviderApi#createMeeting} called strictly in between, outside
 * any open transaction. If this class were itself transactional, both
 * {@link ClassSessionWriteService} calls would join this method's single
 * enclosing transaction instead, holding a DB transaction open across the
 * provider call - exactly what {@code .claude/rules/backend.md} forbids.
 *
 * <p>A provider failure (an exception from {@link
 * LiveClassProviderApi#createMeeting}) never deletes/hides the session - it
 * is caught here and persisted as {@code providerStatus == FAILED} with a
 * client-safe failure reason, leaving the session {@code SCHEDULED} and
 * retryable via {@link #retryProvisioning}, per plan §4's "never silently
 * drop a session on provider failure" requirement.
 */
@Service
public class ClassSessionSchedulingService {

	private static final Logger log = LoggerFactory.getLogger(ClassSessionSchedulingService.class);

	private final LiveClassAccessGuard accessGuard;

	private final CourseLookupApi courseLookupApi;

	private final ClassSessionWriteService writeService;

	private final ClassSessionRepository classSessionRepository;

	private final LiveClassProviderApi liveClassProviderApi;

	public ClassSessionSchedulingService(LiveClassAccessGuard accessGuard, CourseLookupApi courseLookupApi,
			ClassSessionWriteService writeService, ClassSessionRepository classSessionRepository,
			LiveClassProviderApi liveClassProviderApi) {
		this.accessGuard = accessGuard;
		this.courseLookupApi = courseLookupApi;
		this.writeService = writeService;
		this.classSessionRepository = classSessionRepository;
		this.liveClassProviderApi = liveClassProviderApi;
	}

	public ClassSessionView scheduleSession(NewSessionCommand command) {
		accessGuard.requireManagementAccess(command.courseId(), PermissionAction.CREATE_EDIT);
		UUID teacherId = courseLookupApi.getTeacherId(command.courseId())
			.orElseThrow(() -> new NotFoundException("Course not found"));
		requireValidWindow(command.scheduledStart(), command.scheduledEnd());
		UUID lessonId = requireValidLesson(command.courseId(), command.lessonId());

		ClassSession pending = writeService.createPendingSession(command.courseId(), teacherId, lessonId,
				command.title(), command.description(), command.scheduledStart(), command.scheduledEnd());

		return ClassSessionView.from(provisionOrMarkFailed(pending.getTenantId(), pending.getId(), pending.getTitle(),
				pending.getScheduledStart(), pending.getScheduledEnd()));
	}

	/** Idempotent no-op if already {@code PROVISIONED} - re-attempts otherwise. */
	public ClassSessionView retryProvisioning(UUID sessionId) {
		ClassSession session = classSessionRepository.findById(sessionId)
			.orElseThrow(() -> new NotFoundException("Class session not found"));
		accessGuard.requireManagementAccess(session.getCourseId(), PermissionAction.CREATE_EDIT);
		if (!session.isRetryable()) {
			return ClassSessionView.from(session);
		}
		return ClassSessionView.from(provisionOrMarkFailed(session.getTenantId(), session.getId(), session.getTitle(),
				session.getScheduledStart(), session.getScheduledEnd()));
	}

	private ClassSession provisionOrMarkFailed(UUID tenantId, UUID sessionId, String title, Instant scheduledStart,
			Instant scheduledEnd) {
		try {
			MeetingCreationResult creation = liveClassProviderApi.createMeeting(tenantId, sessionId, title,
					scheduledStart, scheduledEnd);
			return writeService.markProvisioned(sessionId, creation.providerReference());
		}
		catch (RuntimeException ex) {
			log.atWarn()
				.setMessage("live_class.provisioning_failed")
				.addKeyValue("tenantId", tenantId)
				.addKeyValue("sessionId", sessionId)
				.addKeyValue("reason", ex.getMessage())
				.log();
			return writeService.markProvisioningFailed(sessionId, safeFailureReason(ex));
		}
	}

	private String safeFailureReason(RuntimeException ex) {
		String message = ex.getMessage();
		return (message == null || message.isBlank()) ? "The meeting provider request failed" : message;
	}

	private void requireValidWindow(Instant scheduledStart, Instant scheduledEnd) {
		if (scheduledStart == null || scheduledEnd == null || !scheduledEnd.isAfter(scheduledStart)) {
			throw new FieldValidationException("Invalid session schedule window",
					List.of(new FieldError("scheduledEnd", "must be after scheduledStart")));
		}
	}

	/**
	 * @return {@code null} if {@code lessonId} is {@code null} (a session need
	 * not be tied to a specific lesson); otherwise the validated lesson id.
	 * @throws NotFoundException if the lesson does not exist in the caller's
	 * tenant, or exists but belongs to a DIFFERENT course than {@code
	 * courseId} - mirrors {@code MaterialAccessGuard}'s lesson-ownership
	 * cross-check rationale.
	 */
	private UUID requireValidLesson(UUID courseId, UUID lessonId) {
		if (lessonId == null) {
			return null;
		}
		LessonOwnership ownership = courseLookupApi.resolveLessonOwnership(lessonId)
			.orElseThrow(() -> new NotFoundException("Lesson not found"));
		if (!ownership.courseId().equals(courseId)) {
			throw new NotFoundException("Lesson not found");
		}
		return lessonId;
	}

}

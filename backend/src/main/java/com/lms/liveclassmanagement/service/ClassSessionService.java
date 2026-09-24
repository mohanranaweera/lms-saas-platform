package com.lms.liveclassmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.LiveClassProviderApi;
import com.lms.integrationmanagement.api.ShortLivedJoinLink;
import com.lms.integrationmanagement.api.ShortLivedPlaybackLink;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.domain.ClassSessionProviderStatus;
import com.lms.liveclassmanagement.domain.ClassSessionRecording;
import com.lms.liveclassmanagement.domain.ClassSessionRecordingStatus;
import com.lms.liveclassmanagement.domain.ClassSessionStatus;
import com.lms.liveclassmanagement.repository.ClassSessionRecordingRepository;
import com.lms.liveclassmanagement.repository.ClassSessionRepository;
import com.lms.liveclassmanagement.support.LiveClassAccessGuard;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read/join/recording/status-transition orchestration for {@code
 * class_session} (Wave 4 plan §4). Scheduling/retry-provisioning (the
 * two-phase, provider-call-spanning flow) live in {@link
 * ClassSessionSchedulingService} instead - everything here either performs
 * no outbound call at all, or is a plain read, so ordinary {@code
 * @Transactional} is safe.
 */
@Service
public class ClassSessionService {

	private static final String TEACHER_PARTICIPANT_ROLE = "HOST";

	private static final String STUDENT_PARTICIPANT_ROLE = "PARTICIPANT";

	private final ClassSessionRepository classSessionRepository;

	private final ClassSessionRecordingRepository recordingRepository;

	private final LiveClassAccessGuard accessGuard;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final LiveClassProviderApi liveClassProviderApi;

	private final PermissionCheckService permissionCheckService;

	public ClassSessionService(ClassSessionRepository classSessionRepository,
			ClassSessionRecordingRepository recordingRepository, LiveClassAccessGuard accessGuard,
			EnrollmentAccessApi enrollmentAccessApi, LiveClassProviderApi liveClassProviderApi,
			PermissionCheckService permissionCheckService) {
		this.classSessionRepository = classSessionRepository;
		this.recordingRepository = recordingRepository;
		this.accessGuard = accessGuard;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.liveClassProviderApi = liveClassProviderApi;
		this.permissionCheckService = permissionCheckService;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public ClassSessionView getSession(UUID sessionId) {
		ClassSession session = requireExisting(sessionId);
		accessGuard.requireEntitlement(session, PermissionAction.VIEW);
		return ClassSessionView.from(session);
	}

	/**
	 * Three-way filtered list (Wave 4 plan §4/§7): Teacher sees only their own
	 * courses' sessions, staff (with a {@code LIVE_CLASSES}/{@code VIEW}
	 * grant) sees every session in the tenant, Student sees only sessions in
	 * courses they currently hold an {@code ACTIVE} enrollment for - the
	 * client-supplied {@link ClassSessionListFilter#courseId()} is only ever
	 * an additional narrowing filter on top of that server-derived set, never
	 * a way to widen it.
	 */
	@Transactional(readOnly = true)
	public List<ClassSessionView> listSessions(ClassSessionListFilter filter) {
		List<ClassSession> candidates;
		if (accessGuard.isCurrentPrincipalTeacher()) {
			candidates = classSessionRepository.findByTeacherId(AuthenticatedPrincipalHolder.get().userId());
		}
		else if (accessGuard.isCurrentPrincipalStudent()) {
			Set<UUID> enrolledCourseIds = enrollmentAccessApi
				.listCurrentlyEnrolledCourseIds(AuthenticatedPrincipalHolder.get().userId());
			candidates = classSessionRepository.findByCourseIdIn(enrolledCourseIds);
		}
		else {
			permissionCheckService.requirePermission(DomainArea.LIVE_CLASSES, PermissionAction.VIEW);
			candidates = classSessionRepository.findAllForTenant();
		}
		return candidates.stream()
			.filter(session -> filter.courseId() == null || filter.courseId().equals(session.getCourseId()))
			.filter(session -> filter.status() == null || filter.status() == session.getStatus())
			.filter(session -> filter.matches(session.getScheduledStart()))
			.map(ClassSessionView::from)
			.toList();
	}

	// ------------------------------------------------------------------
	// Management: edit / status transitions.
	// ------------------------------------------------------------------

	@Transactional
	public ClassSessionView updateSession(UUID sessionId, EditSessionCommand command) {
		ClassSession session = requireExisting(sessionId);
		accessGuard.requireManagementAccess(session.getCourseId(), PermissionAction.CREATE_EDIT);
		try {
			session.reschedule(command.title(), command.description(), command.scheduledStart(),
					command.scheduledEnd());
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
		return ClassSessionView.from(classSessionRepository.save(session));
	}

	@Transactional
	public ClassSessionView startSession(UUID sessionId) {
		return transition(sessionId, ClassSession::start);
	}

	@Transactional
	public ClassSessionView completeSession(UUID sessionId) {
		return transition(sessionId, ClassSession::complete);
	}

	@Transactional
	public ClassSessionView cancelSession(UUID sessionId) {
		return transition(sessionId, ClassSession::cancel);
	}

	private ClassSessionView transition(UUID sessionId, java.util.function.Consumer<ClassSession> transition) {
		ClassSession session = requireExisting(sessionId);
		accessGuard.requireManagementAccess(session.getCourseId(), PermissionAction.CREATE_EDIT);
		try {
			transition.accept(session);
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
		return ClassSessionView.from(classSessionRepository.save(session));
	}

	// ------------------------------------------------------------------
	// Join / recording.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public ShortLivedJoinLink join(UUID sessionId) {
		ClassSession session = requireExisting(sessionId);
		accessGuard.requireEntitlement(session, PermissionAction.VIEW);
		if (session.getStatus() != ClassSessionStatus.LIVE) {
			throw new ConflictException("This class session is not currently live");
		}
		if (session.getProviderStatus() != ClassSessionProviderStatus.PROVISIONED
				|| session.getProviderReference() == null) {
			throw new ConflictException("This class session's meeting has not been successfully provisioned yet");
		}
		String participantRole = accessGuard.isCurrentPrincipalStudent() ? STUDENT_PARTICIPANT_ROLE
				: TEACHER_PARTICIPANT_ROLE;
		String displayName = AuthenticatedPrincipalHolder.get().userId().toString();
		return liveClassProviderApi.getJoinUrl(session.getProviderReference(), session.getTenantId(), participantRole,
				displayName);
	}

	@Transactional(readOnly = true)
	public ShortLivedPlaybackLink getRecordingPlaybackUrl(UUID sessionId) {
		ClassSession session = requireExisting(sessionId);
		accessGuard.requireEntitlement(session, PermissionAction.VIEW);
		if (session.getStatus() != ClassSessionStatus.COMPLETED) {
			throw new ConflictException("This class session has not completed yet");
		}
		ClassSessionRecording recording = recordingRepository.findBySessionId(sessionId)
			.filter(candidate -> candidate.getStatus() == ClassSessionRecordingStatus.AVAILABLE)
			.orElseThrow(() -> new NotFoundException("Recording not available"));
		return liveClassProviderApi.getRecordingPlaybackUrl(recording.getProviderRecordingReference(),
				session.getTenantId());
	}

	private ClassSession requireExisting(UUID sessionId) {
		return classSessionRepository.findById(sessionId)
			.orElseThrow(() -> new NotFoundException("Class session not found"));
	}

}

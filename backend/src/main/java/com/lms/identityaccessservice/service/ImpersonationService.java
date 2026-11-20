package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.RequestRefusedException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.ImpersonationApi;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.ImpersonationSession;
import com.lms.identityaccessservice.domain.ImpersonationSession.EndReason;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.ImpersonationSessionRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-033 support ("Log as Student") sessions: start, handoff-cookie exchange for a
 * short-lived impersonation access token, per-request validation (called by {@code
 * JwtAuthenticationFilter}), current-session read and end. Every lookup is
 * tenant-scoped through the resolved {@link TenantContext}.
 *
 * <p>A session is usable only while it is open, before its absolute {@code
 * expires_at}, the admin account is ACTIVE and the admin's own device session is
 * still usable; a session found unusable for one of those reasons is ended
 * (EXPIRED / ACTOR_REVOKED) the first time that is noticed.
 */
@Service
public class ImpersonationService implements ImpersonationApi {

	public static final String COOKIE_NAME = "lms_impersonation";

	public static final String COOKIE_PATH = "/api/v1/auth/impersonation";

	public static final String AUDIT_STARTED = "student.impersonation_started";

	public static final String AUDIT_ENDED = "student.impersonation_ended";

	private static final Logger log = LoggerFactory.getLogger(ImpersonationService.class);

	private final ImpersonationSessionRepository sessionRepository;

	private final TenantUserRepository tenantUserRepository;

	private final DeviceSessionRepository deviceSessionRepository;

	private final DeviceSessionCacheService cacheService;

	private final TokenService tokenService;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Duration ttl;

	private final boolean secureCookie;

	public ImpersonationService(ImpersonationSessionRepository sessionRepository,
			TenantUserRepository tenantUserRepository, DeviceSessionRepository deviceSessionRepository,
			DeviceSessionCacheService cacheService, TokenService tokenService,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext,
			Environment environment, @Value("${app.security.impersonation.ttl:30m}") Duration ttl) {
		this.sessionRepository = sessionRepository;
		this.tenantUserRepository = tenantUserRepository;
		this.deviceSessionRepository = deviceSessionRepository;
		this.cacheService = cacheService;
		this.tokenService = tokenService;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.ttl = ttl;
		// Mirrors AuthController's refresh cookie: Secure everywhere except the local profile.
		this.secureCookie = !environment.acceptsProfiles(Profiles.of("local"));
	}

	/** What the support banner / token exchange shows about a session. */
	public record SessionView(UUID sessionId, Instant expiresAt, UUID studentProfileId, String studentIndexNumber,
			String studentFullName, UUID actorUserId, String actorName) {
	}

	public record TokenGrant(String accessToken, Instant expiresAt, SessionView session) {
	}

	// ------------------------------------------------------------------
	// Start (ImpersonationApi, called by user-management).
	// ------------------------------------------------------------------

	@Override
	@Transactional
	public StartedImpersonation start(UUID subjectUserId, UUID subjectStudentProfileId, String subjectIndexNumber,
			String subjectFullName, String reason) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (principal.isImpersonation()) {
			throw new RequestRefusedException(HttpStatus.FORBIDDEN, ApiErrorCodes.IMPERSONATION_NOT_ALLOWED,
					"A support session cannot be started from inside another support session");
		}
		permissionCheckService.requirePermission(DomainArea.STUDENT_IMPERSONATION, PermissionAction.CREATE_EDIT);
		String trimmedReason = reason == null ? "" : reason.strip();
		if (trimmedReason.length() < 5 || trimmedReason.length() > 500) {
			throw new RequestRefusedException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"The reason must be 5-500 characters");
		}
		TenantUser subject = tenantUserRepository.findById(subjectUserId)
			.filter(user -> user.getRole() == Role.STUDENT)
			.orElseThrow(() -> new NotFoundException("Student account not found"));
		if (subject.getId().equals(principal.userId())) {
			throw new NotFoundException("Student account not found");
		}
		if (subject.getStatus() != AccountStatus.ACTIVE) {
			throw new RequestRefusedException(HttpStatus.CONFLICT, ApiErrorCodes.STUDENT_NOT_ACTIVE,
					"Only an active student can be viewed in a support session");
		}

		Instant now = Instant.now();
		// One open session per admin: the previous one ends first (flushed before the
		// insert so the partial unique index sees it closed).
		for (ImpersonationSession previous : sessionRepository.findOpenForActor(principal.userId())) {
			endAndAudit(previous, EndReason.REPLACED, now, principal.userId());
		}
		sessionRepository.flush();

		String rawSecret = tokenService.generateOpaqueRefreshToken();
		Instant expiresAt = now.plus(ttl);
		ImpersonationSession session = sessionRepository.saveAndFlush(new ImpersonationSession(
				tenantContext.getTenantId(), principal.userId(), principal.sessionId(), subject.getId(),
				subjectStudentProfileId, subjectIndexNumber, subjectFullName, trimmedReason,
				tokenService.hashRefreshToken(rawSecret), now, expiresAt));

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("impersonationSessionId", session.getId().toString());
		metadata.put("actorUserId", principal.userId().toString());
		metadata.put("subjectUserId", subject.getId().toString());
		metadata.put("expiresAt", expiresAt.toString());
		auditLogApi.record(new AuditLogEntry(principal.userId(), AUDIT_STARTED, "student_profile",
				subjectStudentProfileId, trimmedReason, metadata));
		log.info("Support session {} started in tenant {} by admin {} for student {}", session.getId(),
				session.getTenantId(), principal.userId(), subject.getId());

		ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, rawSecret)
			.httpOnly(true)
			.secure(secureCookie)
			.sameSite("Strict")
			.path(COOKIE_PATH)
			.maxAge(ttl)
			.build();
		return new StartedImpersonation(session.getId(), expiresAt, cookie.toString());
	}

	// ------------------------------------------------------------------
	// Token exchange / current / end (ImpersonationController).
	// ------------------------------------------------------------------

	/**
	 * Exchanges the handoff cookie for an in-memory impersonation access token. Not
	 * rolled back on refusal, so a session found expired/actor-revoked stays ended.
	 */
	@Transactional(noRollbackFor = RequestRefusedException.class)
	public TokenGrant exchange(String rawSecret) {
		if (rawSecret == null || rawSecret.isBlank() || !TenantContextHolder.isSet()) {
			// No resolved tenant: fail closed (401) before any tenant-scoped lookup.
			throw invalidSession();
		}
		ImpersonationSession session = sessionRepository
			.findByHandoffSecretHash(tokenService.hashRefreshToken(rawSecret))
			.orElseThrow(ImpersonationService::invalidSession);
		if (!ensureUsable(session, Instant.now())) {
			throw invalidSession();
		}
		TenantUser subject = tenantUserRepository.findById(session.getSubjectUserId())
			.filter(user -> user.getStatus() == AccountStatus.ACTIVE && user.getRole() == Role.STUDENT)
			.orElseThrow(ImpersonationService::invalidSession);
		String token = tokenService.issueImpersonationAccessToken(subject.getId(), session.getTenantId(),
				session.getId(), session.getActorUserId(), session.getExpiresAt());
		Instant tokenExpiry = tokenService.parseAndValidate(token).expiresAt();
		return new TokenGrant(token, tokenExpiry, view(session));
	}

	/** The caller's own current support session (impersonation principal only). */
	@Transactional(readOnly = true)
	public SessionView current() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.isSet() ? AuthenticatedPrincipalHolder.get()
				: null;
		if (principal == null || !principal.isImpersonation()) {
			throw new NotFoundException("No active support session");
		}
		return sessionRepository.findById(principal.sessionId())
			.filter(session -> session.isActive(Instant.now()))
			.map(this::view)
			.orElseThrow(() -> new NotFoundException("No active support session"));
	}

	/**
	 * Ends the support session identified by the impersonation principal and/or the
	 * handoff cookie (EXITED). Idempotent: nothing to end is not an error.
	 */
	@Transactional
	public void end(String rawSecret) {
		Instant now = Instant.now();
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.isSet() ? AuthenticatedPrincipalHolder.get()
				: null;
		if (principal != null && principal.isImpersonation()) {
			sessionRepository.findById(principal.sessionId())
				.ifPresent(session -> endAndAudit(session, EndReason.EXITED, now, session.getActorUserId()));
		}
		if (rawSecret != null && !rawSecret.isBlank()) {
			sessionRepository.findByHandoffSecretHash(tokenService.hashRefreshToken(rawSecret))
				.ifPresent(session -> endAndAudit(session, EndReason.EXITED, now, session.getActorUserId()));
		}
	}

	public String clearCookieHeader() {
		return ResponseCookie.from(COOKIE_NAME, "")
			.httpOnly(true)
			.secure(secureCookie)
			.sameSite("Strict")
			.path(COOKIE_PATH)
			.maxAge(Duration.ZERO)
			.build()
			.toString();
	}

	// ------------------------------------------------------------------
	// Per-request validation (JwtAuthenticationFilter).
	// ------------------------------------------------------------------

	/**
	 * True when the impersonation token's session is usable right now and matches the
	 * token's subject and actor claims. Ends a session found expired / actor-revoked.
	 */
	@Transactional
	public boolean isTokenSessionUsable(UUID sessionId, UUID subjectUserId, UUID actorUserId) {
		if (!TenantContextHolder.isSet()) {
			return false;
		}
		Optional<ImpersonationSession> found = sessionRepository.findById(sessionId);
		if (found.isEmpty()) {
			return false;
		}
		ImpersonationSession session = found.get();
		if (!session.getSubjectUserId().equals(subjectUserId) || !session.getActorUserId().equals(actorUserId)) {
			return false;
		}
		return ensureUsable(session, Instant.now());
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private boolean ensureUsable(ImpersonationSession session, Instant now) {
		if (session.isEnded()) {
			return false;
		}
		// Fail closed (never a 500, never a write) without a resolved tenant or for a session of
		// another tenant: the actor's role and grants can only be re-checked in its own tenant.
		if (!TenantContextHolder.isSet() || !session.getTenantId().equals(tenantContext.getTenantId())) {
			return false;
		}
		if (!now.isBefore(session.getExpiresAt())) {
			endAndAudit(session, EndReason.EXPIRED, now, session.getActorUserId());
			return false;
		}
		boolean actorActive = tenantUserRepository.findById(session.getActorUserId())
			// Still ACTIVE and still holding Log as Student: a Tenant Admin, or (STAFF-MGMT-03)
			// a member whose role the tenant has granted STUDENTS_LOG_AS. A mid-session demotion
			// or a revoked grant ends support access on the next request. Grants are read for
			// the trusted resolved tenant (the session's own tenant - JwtAuthenticationFilter
			// rejects a token presented on another tenant's host before this runs).
			.map(actor -> actor.getStatus() == AccountStatus.ACTIVE
					&& (actor.getRole() == Role.TENANT_ADMIN
							|| permissionCheckService.roleHoldsCapability(actor.getRole().name(),
									Capability.STUDENTS_LOG_AS)))
			.orElse(false);
		if (!actorActive || !isActorSessionUsable(session.getActorSessionId(), now)) {
			endAndAudit(session, EndReason.ACTOR_REVOKED, now, session.getActorUserId());
			return false;
		}
		return true;
	}

	private boolean isActorSessionUsable(UUID actorSessionId, Instant now) {
		if (cacheService.isActive(actorSessionId)) {
			return true;
		}
		return deviceSessionRepository.findById(actorSessionId).map(session -> {
			boolean usable = session.isUsable(now);
			if (usable) {
				cacheService.markActive(actorSessionId, session.getExpiresAt());
			}
			return usable;
		}).orElse(false);
	}

	private void endAndAudit(ImpersonationSession session, EndReason reason, Instant now, UUID auditActorId) {
		if (!session.end(reason, now)) {
			return;
		}
		sessionRepository.save(session);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("impersonationSessionId", session.getId().toString());
		metadata.put("actorUserId", session.getActorUserId().toString());
		metadata.put("subjectUserId", session.getSubjectUserId().toString());
		metadata.put("endReason", reason.name());
		auditLogApi.record(new AuditLogEntry(auditActorId, AUDIT_ENDED, "student_profile",
				session.getSubjectStudentProfileId(), null, metadata));
		log.info("Support session {} ended ({}) in tenant {}", session.getId(), reason, session.getTenantId());
	}

	private SessionView view(ImpersonationSession session) {
		String actorName = tenantUserRepository.findById(session.getActorUserId())
			.map(TenantUser::getEmail)
			.orElse(null);
		return new SessionView(session.getId(), session.getExpiresAt(), session.getSubjectStudentProfileId(),
				session.getSubjectIndexNumber(), session.getSubjectFullName(), session.getActorUserId(), actorName);
	}

	private static RequestRefusedException invalidSession() {
		return new RequestRefusedException(HttpStatus.UNAUTHORIZED, ApiErrorCodes.IMPERSONATION_SESSION_INVALID,
				"The support session has ended or is not valid");
	}

}

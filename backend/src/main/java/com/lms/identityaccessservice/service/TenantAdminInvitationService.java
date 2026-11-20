package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.ConflictException;
import com.lms.common.error.TooManyRequestsException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.PasswordEmailRequestedEvent;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import com.lms.identityaccessservice.api.TenantAdminInvitationApi;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.PasswordToken;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.PasswordTokenRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tenant's first Tenant Admin (AUTH-PWD-01 / ADR-020), for Platform Admin
 * actions. Platform Admin requests carry no {@code TenantContext}, so each
 * method sets it explicitly to the target tenant (the path variable, never
 * client-body input) and restores whatever was there before.
 */
@Service
public class TenantAdminInvitationService implements TenantAdminInvitationApi {

	private static final SecureRandom RANDOM = new SecureRandom();

	static final Duration RESEND_WINDOW = Duration.ofMinutes(1);

	private static final String TARGET = "tenant_user";

	/**
	 * What happened inside the tenant context. The audit entry is written only
	 * after that context is cleared: {@code AuditLogApi#recordForTenant} (the
	 * Platform-Admin path) refuses to run while a TenantContext is set.
	 */
	private record Outcome(FirstAdminView view, UUID userId, String action, Map<String, Object> metadata) {

	}

	private final TenantUserRepository users;

	private final PasswordTokenRepository tokens;

	private final PasswordTokenService passwordTokenService;

	private final PasswordEncoder passwordEncoder;

	private final AuthRateLimiter rateLimiter;

	private final ApplicationEventPublisher eventPublisher;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public TenantAdminInvitationService(TenantUserRepository users, PasswordTokenRepository tokens,
			PasswordTokenService passwordTokenService, PasswordEncoder passwordEncoder, AuthRateLimiter rateLimiter,
			ApplicationEventPublisher eventPublisher, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.users = users;
		this.tokens = tokens;
		this.passwordTokenService = passwordTokenService;
		this.passwordEncoder = passwordEncoder;
		this.rateLimiter = rateLimiter;
		this.eventPublisher = eventPublisher;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Override
	@Transactional
	public FirstAdminView inviteFirstAdmin(UUID tenantId, String email, UUID actorId) {
		Outcome outcome = inTenant(tenantId, () -> {
			if (firstAdminUser().isPresent()) {
				throw new ConflictException("This tenant already has a Tenant Admin");
			}
			return invite(tenantId, normalise(email));
		});
		return audited(tenantId, actorId, outcome);
	}

	@Override
	@Transactional
	public FirstAdminView inviteOrResend(UUID tenantId, String email, String fallbackEmail, UUID actorId) {
		Outcome outcome = inTenant(tenantId, () -> {
			if (!rateLimiter.tryAcquire("invite:" + tenantId, 1, RESEND_WINDOW)) {
				throw new TooManyRequestsException("An invitation was just sent. Try again in a minute.");
			}
			Optional<TenantUser> admin = firstAdminUser();
			if (admin.isEmpty()) {
				String target = (email != null && !email.isBlank()) ? email : fallbackEmail;
				return invite(tenantId, normalise(target));
			}
			TenantUser user = admin.get();
			if (user.getStatus() != AccountStatus.INVITED) {
				throw new ConflictException("The Tenant Admin has already set up the account");
			}
			Map<String, Object> metadata = new HashMap<>();
			String action = "tenant_admin.invitation_resent";
			if (email != null && !email.isBlank() && !normalise(email).equals(user.getEmail())) {
				String corrected = normalise(email);
				if (users.findByEmail(corrected).isPresent()) {
					throw new ConflictException("Another account in this tenant already uses that email");
				}
				metadata.put("previousEmail", PasswordPolicy.maskEmail(user.getEmail()));
				user.correctInvitedEmail(corrected);
				users.save(user);
				action = "tenant_admin.invitation_email_corrected";
			}
			// Old links stop working now, not only when the new email is sent.
			passwordTokenService.invalidateAllOpen(user.getId());
			metadata.put("email", PasswordPolicy.maskEmail(user.getEmail()));
			eventPublisher.publishEvent(
					new PasswordEmailRequestedEvent(tenantId, user.getId(), PasswordTokenPurpose.ACCOUNT_SETUP));
			return new Outcome(view(user), user.getId(), action, metadata);
		});
		return audited(tenantId, actorId, outcome);
	}

	private FirstAdminView audited(UUID tenantId, UUID actorId, Outcome outcome) {
		auditLogApi.recordForTenant(tenantId,
				new AuditLogEntry(actorId, outcome.action(), TARGET, outcome.userId(), null, outcome.metadata()));
		return outcome.view();
	}

	@Override
	@Transactional(readOnly = true)
	public FirstAdminView firstAdmin(UUID tenantId) {
		return inTenant(tenantId, () -> firstAdminUser().map(this::view)
			.orElseGet(() -> new FirstAdminView("NONE", null, null, null)));
	}

	private Outcome invite(UUID tenantId, String email) {
		if (email == null || email.isBlank()) {
			throw new ConflictException("The tenant has no contact email to invite");
		}
		if (users.findByEmail(email).isPresent()) {
			throw new ConflictException("Another account in this tenant already uses that email");
		}
		TenantUser user = users.save(TenantUser.invited(tenantId, email, unusablePasswordHash(), Role.TENANT_ADMIN));
		eventPublisher.publishEvent(
				new PasswordEmailRequestedEvent(tenantId, user.getId(), PasswordTokenPurpose.ACCOUNT_SETUP));
		return new Outcome(view(user), user.getId(), "tenant_admin.invited",
				Map.of("email", PasswordPolicy.maskEmail(email)));
	}

	private Optional<TenantUser> firstAdminUser() {
		return users.findAllByRoleOldestFirst(Role.TENANT_ADMIN).stream().findFirst();
	}

	private FirstAdminView view(TenantUser user) {
		if (user.getStatus() != AccountStatus.INVITED) {
			return new FirstAdminView(user.getStatus().name(), PasswordPolicy.maskEmail(user.getEmail()), null, null);
		}
		Optional<PasswordToken> latest = tokens.findLatest(user.getId(), PasswordTokenPurpose.ACCOUNT_SETUP);
		return new FirstAdminView("INVITED", PasswordPolicy.maskEmail(user.getEmail()),
				latest.map(PasswordToken::getCreatedAt).orElse(user.getCreatedAt()),
				latest.filter(token -> token.getUsedAt() == null).map(PasswordToken::getExpiresAt).orElse(null));
	}

	/** Hash of 256 random bits nobody ever sees - the account cannot log in with any password. */
	private String unusablePasswordHash() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return passwordEncoder.encode(Base64.getEncoder().encodeToString(bytes));
	}

	private static String normalise(String email) {
		return (email == null) ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	/** Runs {@code work} with {@code TenantContext} set to the target tenant, restoring the previous value. */
	private <T> T inTenant(UUID tenantId, Supplier<T> work) {
		UUID previous = TenantContextHolder.isSet() ? tenantContext.getTenantId() : null;
		TenantContextHolder.set(tenantId);
		try {
			return work.get();
		}
		finally {
			if (previous == null) {
				TenantContextHolder.clear();
			}
			else {
				TenantContextHolder.set(previous);
			}
		}
	}

}

package com.lms.identityaccessservice.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.TooManyRequestsException;
import com.lms.identityaccessservice.api.PasswordEmailRequestedEvent;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.InvalidRoleCodeException;
import com.lms.identityaccessservice.repository.DeviceSessionLoginQuery;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import com.lms.identityaccessservice.repository.RoleCatalogRepository;
import com.lms.identityaccessservice.domain.RoleCatalogEntry;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link UserProvisioningApi} - the first cross-module "create a
 * login" capability in this codebase, added for {@code user-management}'s
 * Staff Management module (MVP-005). {@code @Transactional} here joins
 * whatever transaction the calling module's own {@code @Transactional}
 * service method already started (in-process call, same JVM, same DB
 * transaction) rather than opening a separate one, matching this
 * architecture's "synchronous in-process api call for request-time
 * consistency" pattern.
 */
@Service
@Transactional
public class UserProvisioningService implements UserProvisioningApi {

	/** Alphanumeric-plus-symbol charset for generated temporary passwords - avoids ambiguous-looking characters. */
	private static final String TEMP_PASSWORD_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

	private static final int TEMP_PASSWORD_LENGTH = 16;

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private final TenantUserRepository tenantUserRepository;

	private final PlatformAdminUserRepository platformAdminUserRepository;

	private final PasswordEncoder passwordEncoder;

	private final TenantContext tenantContext;

	/**
	 * Resolved on first use: LoginMobileService -> TenantConfigApi ->
	 * AuditLogApi -> ... -> UserProvisioningApi would otherwise be a
	 * constructor-injection cycle.
	 */
	private final ObjectProvider<LoginMobileService> loginMobileService;

	private final DeviceSessionLoginQuery deviceSessionLoginQuery;

	private final ObjectProvider<RefreshTokenService> refreshTokenService;

	/** STAFF-MGMT-02: generic, tenant-local duplicate message (never reveals other tenants). */
	static final String EMAIL_TAKEN_MESSAGE = "An account with this email already exists in this institute";

	static final Duration INVITE_RESEND_WINDOW = Duration.ofMinutes(1);

	private final ObjectProvider<PasswordTokenService> passwordTokenService;

	private final ObjectProvider<AuthRateLimiter> rateLimiter;

	private final ApplicationEventPublisher eventPublisher;

	private final RoleCatalogRepository roleCatalogRepository;

	public UserProvisioningService(TenantUserRepository tenantUserRepository,
			PlatformAdminUserRepository platformAdminUserRepository, PasswordEncoder passwordEncoder,
			TenantContext tenantContext, ObjectProvider<LoginMobileService> loginMobileService,
			DeviceSessionLoginQuery deviceSessionLoginQuery, ObjectProvider<RefreshTokenService> refreshTokenService,
			ObjectProvider<PasswordTokenService> passwordTokenService, ObjectProvider<AuthRateLimiter> rateLimiter,
			ApplicationEventPublisher eventPublisher, RoleCatalogRepository roleCatalogRepository) {
		this.roleCatalogRepository = roleCatalogRepository;
		this.passwordTokenService = passwordTokenService;
		this.rateLimiter = rateLimiter;
		this.eventPublisher = eventPublisher;
		this.loginMobileService = loginMobileService;
		this.deviceSessionLoginQuery = deviceSessionLoginQuery;
		this.refreshTokenService = refreshTokenService;
		this.tenantUserRepository = tenantUserRepository;
		this.platformAdminUserRepository = platformAdminUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.tenantContext = tenantContext;
	}

	@Override
	public ProvisionedUser provisionTenantUser(String email, String rawPassword, String roleCode,
			boolean mustChangePassword) {
		Role role = parseRole(roleCode);
		String passwordHash = passwordEncoder.encode(rawPassword);
		// Resolved explicitly from the trusted, already-populated
		// TenantContext (the same one TenantAwareRepository reads) - never
		// accepted as a parameter on this method.
		TenantUser user = new TenantUser(tenantContext.getTenantId(), email, passwordHash, role, mustChangePassword);

		try {
			user = tenantUserRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// uq_tenant_user_tenant_email (V3) is the only unique constraint
			// this insert can violate, so this message is accurate without
			// inspecting the cause. This is the race-safe guard; the caller's
			// own existsByEmail pre-check is a friendlier, but TOCTOU-prone,
			// convenience check only.
			throw new ConflictException("A user with this email already exists");
		}

		return new ProvisionedUser(user.getId(), user.getEmail());
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> findTenantUserIdsByEmailContaining(String term, int limit) {
		if (term == null || term.isBlank() || limit <= 0) {
			return List.of();
		}
		String pattern = "%" + term.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
			.replace("_", "\\_") + "%";
		// findAll(Specification, Pageable) is tenant-scoped by TenantAwareRepositoryImpl.
		return tenantUserRepository
			.findAll((root, query, cb) -> cb.like(cb.lower(root.get("email")), pattern, '\\'),
					PageRequest.of(0, limit, Sort.by("email")))
			.map(TenantUser::getId)
			.getContent();
	}

	@Override
	public void setLoginMobile(UUID userId, String rawMobile, String field) {
		loginMobileService.getObject().set(userId, rawMobile, field);
	}

	@Override
	public java.util.Map<UUID, String> findLoginMobiles(Collection<UUID> userIds) {
		return loginMobileService.getObject().find(userIds);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<UUID, java.time.Instant> findLastLoginAt(Collection<UUID> userIds) {
		return deviceSessionLoginQuery.findLastLoginAt(userIds);
	}

	@Override
	public void revokeAllSessions(UUID userId) {
		refreshTokenService.getObject().revokeAllTenantSessions(userId, null);
	}

	@Override
	public boolean existsByEmail(String email) {
		return tenantUserRepository.findByEmail(email).isPresent();
	}

	@Override
	public boolean existsByEmailIgnoreCase(String email) {
		return email != null && tenantUserRepository.existsByEmailIgnoreCase(email);
	}

	@Override
	@Transactional(readOnly = true)
	public List<TenantUserSummary> findTenantUserSummaries(Collection<UUID> userIds) {
		// findAllById is tenant-scoped by TenantAwareRepositoryImpl, so an id
		// belonging to a different tenant is silently absent from the result,
		// never returned cross-tenant.
		return tenantUserRepository.findAllById(userIds)
			.stream()
			.map(user -> new TenantUserSummary(user.getId(), user.getEmail(), user.getRole().name(),
					user.getStatus().name()))
			.toList();
	}

	@Override
	public void suspendTenantUser(UUID userId) {
		// findById is tenant-scoped by TenantAwareRepositoryImpl - a userId
		// belonging to a different tenant is structurally invisible here,
		// surfacing as 404, never a cross-tenant mutation.
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new NotFoundException("User not found"));
		user.suspend();
		tenantUserRepository.save(user);
		// STAFF-MGMT-02: a suspended account's open setup/reset links die now, so a later
		// reactivation (which may restore INVITED) never revives an old link.
		passwordTokenService.getObject().invalidateAllOpen(user.getId());
	}

	@Override
	public void activateTenantUser(UUID userId) {
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new NotFoundException("User not found"));
		user.activate();
		tenantUserRepository.save(user);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean actorExists(UUID actorId) {
		if (TenantContextHolder.isSet()) {
			// Tenant-scoped path: findById is tenant-scoped by
			// TenantAwareRepositoryImpl, so an id belonging to a different
			// tenant correctly reports as not-existing here too.
			return tenantUserRepository.findById(actorId).isPresent();
		}
		// No TenantContext resolved - the Platform Admin request path.
		// platform_admin_user is platform-level, never tenant-scoped.
		return platformAdminUserRepository.findById(actorId).isPresent();
	}

	@Override
	public String resetPassword(UUID userId) {
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new NotFoundException("User not found"));
		String rawTempPassword = generateTempPassword();
		user.resetPassword(passwordEncoder.encode(rawTempPassword));
		tenantUserRepository.save(user);
		return rawTempPassword;
	}

	@Override
	public ProvisionedUser inviteTenantUser(String email, String roleCode) {
		Role role = parseRole(roleCode);
		String normalised = (email == null) ? "" : email.trim().toLowerCase(Locale.ROOT);
		if (normalised.isEmpty()) {
			throw new IllegalArgumentException("email is required");
		}
		if (tenantUserRepository.existsByEmailIgnoreCase(normalised)) {
			throw new ConflictException(EMAIL_TAKEN_MESSAGE);
		}
		UUID tenantId = tenantContext.getTenantId();
		TenantUser user;
		try {
			user = tenantUserRepository
				.saveAndFlush(TenantUser.invited(tenantId, normalised, unusablePasswordHash(), role));
		}
		catch (DataIntegrityViolationException ex) {
			// uq_tenant_user_tenant_email: a concurrent double submit lost the race.
			throw new ConflictException(EMAIL_TAKEN_MESSAGE);
		}
		eventPublisher.publishEvent(
				new PasswordEmailRequestedEvent(tenantId, user.getId(), PasswordTokenPurpose.ACCOUNT_SETUP));
		return new ProvisionedUser(user.getId(), user.getEmail());
	}

	@Override
	public void resendInvite(UUID userId) {
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new NotFoundException("User not found"));
		if (user.getStatus() != AccountStatus.INVITED) {
			throw new ConflictException("This account has already been set up");
		}
		if (!rateLimiter.getObject().tryAcquire("staff-invite:" + user.getId(), 1, INVITE_RESEND_WINDOW)) {
			throw new TooManyRequestsException("An invitation was just sent. Try again in a minute.");
		}
		// Old links stop working now, not only when the new email is sent.
		passwordTokenService.getObject().invalidateAllOpen(user.getId());
		eventPublisher.publishEvent(new PasswordEmailRequestedEvent(user.getTenantId(), user.getId(),
				PasswordTokenPurpose.ACCOUNT_SETUP));
	}

	@Override
	public void changeRole(UUID userId, String roleCode) {
		Role role = parseRole(roleCode);
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new NotFoundException("User not found"));
		user.changeRole(role);
		tenantUserRepository.save(user);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<String, String> roleDisplayNames() {
		java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
		for (RoleCatalogEntry entry : roleCatalogRepository
			.findByScopeAndIsActiveTrueOrderBySortOrderAsc(RoleCatalogEntry.RoleScope.TENANT)) {
			names.put(entry.getCode(), entry.getDisplayName());
		}
		return names;
	}

	/** Hash of 256 random bits nobody ever sees - the invited account cannot log in with any password. */
	private String unusablePasswordHash() {
		byte[] bytes = new byte[32];
		SECURE_RANDOM.nextBytes(bytes);
		return passwordEncoder.encode(Base64.getEncoder().encodeToString(bytes));
	}

	private static String generateTempPassword() {
		StringBuilder builder = new StringBuilder(TEMP_PASSWORD_LENGTH);
		for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
			builder.append(TEMP_PASSWORD_CHARSET.charAt(SECURE_RANDOM.nextInt(TEMP_PASSWORD_CHARSET.length())));
		}
		return builder.toString();
	}

	private static Role parseRole(String roleCode) {
		try {
			return Role.valueOf(roleCode);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new InvalidRoleCodeException(String.valueOf(roleCode));
		}
	}

}

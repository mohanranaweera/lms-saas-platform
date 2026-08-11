package com.lms.identityaccessservice.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.InvalidRoleCodeException;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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

	private final TenantUserRepository tenantUserRepository;

	private final PasswordEncoder passwordEncoder;

	private final TenantContext tenantContext;

	private static final SecureRandom RANDOM = new SecureRandom();

	public UserProvisioningService(TenantUserRepository tenantUserRepository, PasswordEncoder passwordEncoder,
			TenantContext tenantContext) {
		this.tenantUserRepository = tenantUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.tenantContext = tenantContext;
	}

	@Override
	public ProvisionedUser provisionTenantUser(String email, String roleCode, boolean mustChangePassword) {
		Role role = parseRole(roleCode);
		// The Tenant Admin creating this account is never the custodian of
		// its real login secret (per the approved MVP-005 fix) - the raw
		// password is generated here, never accepted as a parameter, and is
		// only ever handed back once via the returned ProvisionedUser for
		// the caller to relay out-of-band. Never logged.
		String rawPassword = generateTemporaryPassword();
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

		return new ProvisionedUser(user.getId(), user.getEmail(), rawPassword);
	}

	@Override
	public boolean existsByEmail(String email) {
		return tenantUserRepository.findByEmail(email).isPresent();
	}

	@Override
	public void updateTenantUserRole(UUID userId, String roleCode) {
		Role role = parseRole(roleCode);
		// Tenant-scoped by TenantAwareRepositoryImpl exactly like every
		// other read in this service - a userId belonging to a different
		// tenant is structurally invisible here, surfacing as "not found",
		// never a cross-tenant mutation.
		TenantUser user = tenantUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
		user.changeRole(role);
		// No explicit save() call needed: this method runs inside the
		// class-level @Transactional boundary, and `user` is a managed
		// entity read in this same transaction, so Hibernate's
		// dirty-checking flushes the role change on commit - matching this
		// service's existing transactional style.
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

	/**
	 * Generates a random, high-entropy temporary password for a newly
	 * provisioned {@code tenant_user} - mirrors {@link
	 * com.lms.identityaccessservice.service.TokenService}'s opaque
	 * refresh-token generation pattern exactly ({@link SecureRandom} + {@link
	 * Base64.Encoder#withoutPadding()}), but is kept private and local to
	 * this service rather than a shared utility: credential generation is
	 * this service's own concern (Argon2-hashed, handed back to the caller
	 * exactly once), distinct from {@code TokenService}'s refresh-token
	 * concern (SHA-256-hashed, persisted for lookup). 128 bits of randomness,
	 * base64url-encoded, trivially exceeds any reasonable password
	 * complexity policy - no separate strength check is needed.
	 */
	private static String generateTemporaryPassword() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

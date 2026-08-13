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

	public UserProvisioningService(TenantUserRepository tenantUserRepository, PasswordEncoder passwordEncoder,
			TenantContext tenantContext) {
		this.tenantUserRepository = tenantUserRepository;
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
	public boolean existsByEmail(String email) {
		return tenantUserRepository.findByEmail(email).isPresent();
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
	}

	@Override
	public void activateTenantUser(UUID userId) {
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new NotFoundException("User not found"));
		user.activate();
		tenantUserRepository.save(user);
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

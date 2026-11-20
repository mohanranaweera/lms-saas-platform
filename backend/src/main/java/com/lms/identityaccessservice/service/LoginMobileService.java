package com.lms.identityaccessservice.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.MobileAlreadyRegisteredException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-031: identity owns each user's sign-in mobile - normalisation (tenant
 * {@code GENERAL.phone_region}), per-tenant uniqueness and the audit entry.
 * Every lookup and write is tenant-scoped through {@link TenantUserRepository}
 * (ADR-006), so a number or user id from another institute is never seen.
 * Exposed to other modules only through {@code UserProvisioningApi}.
 */
@Service
public class LoginMobileService {

	static final String AUDIT_ACTION = "identity.login_mobile_changed";

	private final TenantUserRepository tenantUserRepository;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	public LoginMobileService(TenantUserRepository tenantUserRepository, TenantConfigApi tenantConfigApi,
			TenantContext tenantContext, AuditLogApi auditLogApi) {
		this.tenantUserRepository = tenantUserRepository;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
	}

	/** E.164 form of {@code raw} for the current tenant's region, or empty when invalid. */
	public Optional<String> normalize(String raw) {
		return PhoneNumbers.toE164(raw, tenantConfigApi.resolvePhoneRegion(tenantContext.getTenantId()));
	}

	/**
	 * Strict set: blank/null clears mobile sign-in; an invalid number is
	 * {@code 400} and a number another account uses is {@code 409}, both with
	 * a field error on {@code field}.
	 */
	@Transactional
	public void set(UUID userId, String raw, String field) {
		TenantUser user = load(userId);
		String next = null;
		if (raw != null && !raw.isBlank()) {
			next = normalize(raw).orElseThrow(() -> new FieldValidationException("Invalid mobile number",
					List.of(new FieldError(field, "Enter a valid mobile number"))));
		}
		apply(user, next, field);
	}

	/** userId -> E.164 for the given users of the current tenant that have one. */
	@Transactional(readOnly = true)
	public Map<UUID, String> find(java.util.Collection<UUID> userIds) {
		Map<UUID, String> result = new HashMap<>();
		tenantUserRepository.findAllById(userIds).forEach(user -> {
			if (user.getLoginMobile() != null) {
				result.put(user.getId(), user.getLoginMobile());
			}
		});
		return result;
	}

	private TenantUser load(UUID userId) {
		return tenantUserRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
	}

	private void apply(TenantUser user, String next, String field) {
		String previous = user.getLoginMobile();
		if (Objects.equals(previous, next)) {
			return;
		}
		if (next != null) {
			tenantUserRepository.findByLoginMobile(next)
				.filter(other -> !other.getId().equals(user.getId()))
				.ifPresent(other -> {
					throw new MobileAlreadyRegisteredException(field);
				});
		}
		user.changeLoginMobile(next);
		try {
			tenantUserRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException ex) {
			// uq_tenant_user_tenant_login_mobile (V107): a concurrent writer took the number.
			throw new MobileAlreadyRegisteredException(field);
		}
		// Self-registration has no signed-in actor: the user is their own actor.
		UUID actorId = AuthenticatedPrincipalHolder.isSet() ? AuthenticatedPrincipalHolder.get().userId() : user.getId();
		// AuditLogEntry copies metadata with Map.copyOf (no null values): "none" = no number.
		Map<String, Object> metadata = Map.of("before", previous == null ? "none" : PhoneNumbers.masked(previous),
				"after", next == null ? "none" : PhoneNumbers.masked(next));
		auditLogApi.record(new AuditLogEntry(actorId, AUDIT_ACTION, "tenant_user", user.getId(), null, metadata));
	}

}

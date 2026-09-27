package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.OnlinePaymentOption;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.integrationmanagement.domain.GatewayDisabledBy;
import com.lms.integrationmanagement.domain.GatewayMode;
import com.lms.integrationmanagement.domain.GatewayProvider;
import com.lms.integrationmanagement.domain.TenantPaymentGatewayAccount;
import com.lms.integrationmanagement.gateway.CredentialCipher;
import com.lms.integrationmanagement.gateway.PayHereGatewayAdapter;
import com.lms.integrationmanagement.repository.TenantPaymentGatewayAccountRepository;
import com.lms.tenantmanagement.api.TenantLookupApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant payment gateway accounts (PAY-GW-01 / ADR-017).
 *
 * <ul>
 * <li>Tenant Admin ({@link DomainArea#PAYMENT_GATEWAY} CREATE_EDIT) saves or
 * replaces the tenant's PayHere merchant id/secret/mode and can switch it
 * off; VIEW roles see status only.</li>
 * <li>Platform Admin can switch any tenant's account off and back on. A
 * PLATFORM switch-off blocks the Tenant Admin from saving or re-enabling until
 * a Platform Admin re-enables it (owner decision 4).</li>
 * </ul>
 *
 * The merchant secret is encrypted before it is persisted, never returned,
 * and never written to the audit log. Every change is audit-logged.
 */
@Service
public class PaymentGatewayAccountService implements PaymentGatewayAccountApi {

	private static final Pattern MERCHANT_ID = Pattern.compile("^[A-Za-z0-9]{1,64}$");

	private static final int MAX_SECRET_LENGTH = 256;

	private static final String TARGET_ENTITY = "tenant_payment_gateway_account";

	private final TenantPaymentGatewayAccountRepository repository;

	private final CredentialCipher credentialCipher;

	private final PayHereGatewayAdapter payHereAdapter;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TenantLookupApi tenantLookupApi;

	public PaymentGatewayAccountService(TenantPaymentGatewayAccountRepository repository,
			CredentialCipher credentialCipher, PayHereGatewayAdapter payHereAdapter,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext,
			TenantLookupApi tenantLookupApi) {
		this.repository = repository;
		this.credentialCipher = credentialCipher;
		this.payHereAdapter = payHereAdapter;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.tenantLookupApi = tenantLookupApi;
	}

	// ------------------------------------------------------------------
	// Tenant Admin
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<PaymentGatewayAccountView> listForCurrentTenant() {
		permissionCheckService.requirePermission(DomainArea.PAYMENT_GATEWAY, PermissionAction.VIEW);
		return repository.findLatest(GatewayProvider.PAYHERE).map(this::toView).stream().toList();
	}

	/**
	 * Saves the tenant's PayHere account. A blank {@code merchantSecret} keeps
	 * the stored secret (so mode/merchant id can change without re-entering
	 * it); it is required when no secret is stored yet.
	 */
	@Transactional
	public PaymentGatewayAccountView savePayHere(String merchantId, String merchantSecret, GatewayMode mode) {
		permissionCheckService.requirePermission(DomainArea.PAYMENT_GATEWAY, PermissionAction.CREATE_EDIT);
		String trimmedMerchantId = (merchantId == null) ? "" : merchantId.trim();
		if (!MERCHANT_ID.matcher(trimmedMerchantId).matches()) {
			throw new FieldValidationException("Invalid PayHere account",
					List.of(new FieldError("merchantId", "must be 1-64 letters or digits")));
		}
		if (mode == null) {
			throw new FieldValidationException("Invalid PayHere account",
					List.of(new FieldError("mode", "must be SANDBOX or LIVE")));
		}
		boolean newSecret = merchantSecret != null && !merchantSecret.isBlank();
		if (newSecret && merchantSecret.length() > MAX_SECRET_LENGTH) {
			throw new FieldValidationException("Invalid PayHere account",
					List.of(new FieldError("merchantSecret", "must be at most " + MAX_SECRET_LENGTH + " characters")));
		}

		Optional<TenantPaymentGatewayAccount> latest = repository.findLatest(GatewayProvider.PAYHERE);
		if (latest.isPresent() && latest.get().getDisabledBy() == GatewayDisabledBy.PLATFORM) {
			throw new ConflictException(
					"Online payments were switched off by the platform. Contact platform support to re-enable them.");
		}
		if (!newSecret && latest.isEmpty()) {
			throw new FieldValidationException("Invalid PayHere account",
					List.of(new FieldError("merchantSecret", "is required")));
		}
		if (latest.isPresent() && latest.get().isActive() && !newSecret
				&& latest.get().getMerchantId().equals(trimmedMerchantId) && latest.get().getMode() == mode) {
			return toView(latest.get());
		}

		String ciphertext = newSecret ? credentialCipher.encrypt(merchantSecret.trim())
				: latest.get().getMerchantSecretCiphertext();
		Instant now = Instant.now();
		latest.filter(TenantPaymentGatewayAccount::isActive).ifPresent(previous -> {
			previous.disable(GatewayDisabledBy.TENANT, "Replaced by new PayHere account settings", now);
			repository.saveAndFlush(previous);
		});
		TenantPaymentGatewayAccount saved = repository.saveAndFlush(new TenantPaymentGatewayAccount(
				tenantContext.getTenantId(), GatewayProvider.PAYHERE, mode, trimmedMerchantId, ciphertext));

		auditLogApi.record(new AuditLogEntry(currentActor(), "payment_gateway.account_saved", TARGET_ENTITY,
				saved.getId(), null, Map.of("provider", saved.getProvider().name(), "mode", saved.getMode().name(),
						"merchantId", mask(saved.getMerchantId()), "secretChanged", newSecret)));
		return toView(saved);
	}

	@Transactional
	public PaymentGatewayAccountView disableForCurrentTenant(String reason) {
		permissionCheckService.requirePermission(DomainArea.PAYMENT_GATEWAY, PermissionAction.CREATE_EDIT);
		TenantPaymentGatewayAccount account = repository.findActive(GatewayProvider.PAYHERE)
			.orElseThrow(() -> new ConflictException("Online payments are not currently enabled"));
		account.disable(GatewayDisabledBy.TENANT, blankToDefault(reason, "Switched off by tenant admin"),
				Instant.now());
		repository.saveAndFlush(account);
		auditLogApi.record(new AuditLogEntry(currentActor(), "payment_gateway.account_disabled", TARGET_ENTITY,
				account.getId(), account.getDisabledReason(), Map.of("disabledBy", GatewayDisabledBy.TENANT.name())));
		return toView(account);
	}

	// ------------------------------------------------------------------
	// Platform Admin (target tenant from the path, validated to exist)
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<PaymentGatewayAccountView> listForTenant(UUID tenantId) {
		return asPlatformAdminFor(tenantId, () -> repository.findLatest(GatewayProvider.PAYHERE)
			.map(this::toView)
			.stream()
			.toList());
	}

	@Transactional
	public PaymentGatewayAccountView platformDisable(UUID tenantId, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new FieldValidationException("A reason is required",
					List.of(new FieldError("reason", "must not be blank")));
		}
		PaymentGatewayAccountView view = asPlatformAdminFor(tenantId, () -> {
			TenantPaymentGatewayAccount account = repository.findActive(GatewayProvider.PAYHERE)
				.orElseThrow(() -> new ConflictException("This institute has no active online payment account"));
			account.disable(GatewayDisabledBy.PLATFORM, reason.trim(), Instant.now());
			repository.saveAndFlush(account);
			return toView(account);
		});
		// recordForTenant must run with no tenant context resolved (Platform
		// Admin path) - i.e. after asPlatformAdminFor has cleared it.
		auditLogApi.recordForTenant(tenantId, new AuditLogEntry(currentActor(), "payment_gateway.account_disabled",
				TARGET_ENTITY, view.id(), reason.trim(), Map.of("disabledBy", GatewayDisabledBy.PLATFORM.name())));
		return view;
	}

	@Transactional
	public PaymentGatewayAccountView platformEnable(UUID tenantId) {
		PaymentGatewayAccountView view = asPlatformAdminFor(tenantId, () -> {
			TenantPaymentGatewayAccount account = repository.findLatest(GatewayProvider.PAYHERE)
				.filter(latest -> latest.getDisabledBy() == GatewayDisabledBy.PLATFORM)
				.orElseThrow(() -> new ConflictException(
						"Only an account switched off by the platform can be re-enabled here"));
			account.reEnable();
			repository.saveAndFlush(account);
			return toView(account);
		});
		auditLogApi.recordForTenant(tenantId, new AuditLogEntry(currentActor(), "payment_gateway.account_reenabled",
				TARGET_ENTITY, view.id(), null, Map.of("provider", view.provider())));
		return view;
	}

	// ------------------------------------------------------------------
	// PaymentGatewayAccountApi (checkout options, current tenant)
	// ------------------------------------------------------------------

	@Override
	@Transactional(readOnly = true)
	public Optional<OnlinePaymentOption> findOnlinePaymentOption() {
		if (!payHereAdapter.isOperational()) {
			return Optional.empty();
		}
		return repository.findActive(GatewayProvider.PAYHERE)
			.map(account -> new OnlinePaymentOption(account.getProvider().name(), account.getMode().name()));
	}

	private <T> T asPlatformAdminFor(UUID tenantId, Supplier<T> work) {
		AuthenticatedPrincipalHolder.requireRole("PLATFORM_ADMIN");
		if (tenantLookupApi.findSubdomain(tenantId).isEmpty()) {
			throw new NotFoundException("Tenant not found");
		}
		try {
			TenantContextHolder.set(tenantId);
			return work.get();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private static UUID currentActor() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	private static String blankToDefault(String value, String fallback) {
		return (value == null || value.isBlank()) ? fallback : value.trim();
	}

	static String mask(String merchantId) {
		if (merchantId == null || merchantId.length() <= 4) {
			return "****";
		}
		return "*".repeat(merchantId.length() - 4) + merchantId.substring(merchantId.length() - 4);
	}

	private PaymentGatewayAccountView toView(TenantPaymentGatewayAccount account) {
		return new PaymentGatewayAccountView(account.getId(), account.getProvider().name(), account.getMode().name(),
				mask(account.getMerchantId()), account.getStatus().name(), true,
				(account.getDisabledBy() == null) ? null : account.getDisabledBy().name(), account.getDisabledReason(),
				account.getDisabledAt(), account.getUpdatedAt(), account.isActive() && payHereAdapter.isOperational());
	}

}

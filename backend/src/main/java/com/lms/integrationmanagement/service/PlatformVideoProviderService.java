package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.domain.TenantMediaIntegration;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.repository.TenantMediaIntegrationRepository;
import com.lms.tenantmanagement.api.TenantLookupApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TD-008 (VIDEO Phase 4): a Platform Admin can switch a tenant's YouTube
 * channel off for uploads - the abuse/incident lever that does not depend on
 * the tenant admin - and only a Platform Admin can switch it back on. Same
 * shape as PAY-GW-01's {@code PaymentGatewayAccountService#platformDisable}:
 * the tenant comes from the path, is checked to exist, and is set as the
 * tenant context only for the duration of the work; the audit entry is
 * written with {@code recordForTenant} afterwards.
 *
 * <p>Switching off keeps the connection and its credentials: videos already
 * on YouTube keep playing (Q-004), processing status still syncs, and the
 * upload worker parks instead of uploading.
 */
@Service
public class PlatformVideoProviderService {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private static final String TARGET_ENTITY = MediaConnectionStore.TARGET_ENTITY;

	/** What a Platform Admin sees. Never contains credentials. */
	/** @param integrationName VIDEO Phase 5d: the institute's name for this channel's integration (card) */
	public record PlatformVideoProviderView(UUID id, String integrationName, String provider, String status,
			String channelId,
			String channelTitle, Instant connectedAt, boolean platformDisabled, String platformDisabledReason,
			Instant platformDisabledAt) {

	}

	private final MediaProviderConnectionRepository repository;

	private final AuditLogApi auditLogApi;

	private final TenantLookupApi tenantLookupApi;

	private final TenantMediaIntegrationRepository integrations;

	public PlatformVideoProviderService(MediaProviderConnectionRepository repository, AuditLogApi auditLogApi,
			TenantLookupApi tenantLookupApi, TenantMediaIntegrationRepository integrations) {
		this.integrations = integrations;
		this.repository = repository;
		this.auditLogApi = auditLogApi;
		this.tenantLookupApi = tenantLookupApi;
	}

	@Transactional(readOnly = true)
	public List<PlatformVideoProviderView> list(UUID tenantId) {
		return asPlatformAdminFor(tenantId, () -> repository.findAllForTenant(MediaProvider.YOUTUBE)
			.stream()
			.map(this::toView)
			.toList());
	}

	@Transactional
	public PlatformVideoProviderView disable(UUID tenantId, UUID accountId, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new FieldValidationException("A reason is required",
					List.of(new FieldError("reason", "must not be blank")));
		}
		PlatformVideoProviderView view = asPlatformAdminFor(tenantId, () -> {
			MediaProviderConnection connection = requireTenantChannel(accountId);
			if (connection.isDisconnected()) {
				throw new ConflictException("This channel is already disconnected");
			}
			if (connection.isPlatformDisabled()) {
				throw new ConflictException("This channel is already switched off by the platform");
			}
			connection.platformDisable(reason.trim(), Instant.now());
			return toView(repository.saveAndFlush(connection));
		});
		auditLogApi.recordForTenant(tenantId, new AuditLogEntry(currentActor(), "video_provider.platform_disabled",
				TARGET_ENTITY, accountId, reason.trim(), Map.of("provider", MediaProvider.YOUTUBE.name())));
		securityLog.atWarn()
			.setMessage("video_provider.platform_disabled")
			.addKeyValue("tenantId", tenantId)
			.addKeyValue("connectionId", accountId)
			.log();
		return view;
	}

	@Transactional
	public PlatformVideoProviderView enable(UUID tenantId, UUID accountId) {
		PlatformVideoProviderView view = asPlatformAdminFor(tenantId, () -> {
			MediaProviderConnection connection = requireTenantChannel(accountId);
			if (!connection.isPlatformDisabled()) {
				throw new ConflictException("Only a channel switched off by the platform can be re-enabled here");
			}
			connection.platformEnable();
			return toView(repository.saveAndFlush(connection));
		});
		auditLogApi.recordForTenant(tenantId, new AuditLogEntry(currentActor(), "video_provider.platform_enabled",
				TARGET_ENTITY, accountId, null, Map.of("provider", MediaProvider.YOUTUBE.name())));
		return view;
	}

	/** 404 unless the id is that tenant's own YouTube channel row. */
	private MediaProviderConnection requireTenantChannel(UUID accountId) {
		return repository.findById(accountId)
			.filter(c -> c.getScope() == ConnectionScope.TENANT && c.getProvider() == MediaProvider.YOUTUBE)
			.orElseThrow(() -> new NotFoundException("Video provider account not found"));
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

	/** Runs inside {@link #asPlatformAdminFor}, so the integration lookup is the target tenant's. */
	private PlatformVideoProviderView toView(MediaProviderConnection connection) {
		String integrationName = (connection.getIntegrationId() == null) ? null
				: integrations.findById(connection.getIntegrationId()).map(TenantMediaIntegration::getName).orElse(null);
		return new PlatformVideoProviderView(connection.getId(), integrationName, connection.getProvider().name(),
				connection.getStatus().name(), MediaConnectionViews.mask(connection.getExternalAccountId()),
				connection.getExternalAccountLabel(), connection.getCreatedAt(), connection.isPlatformDisabled(),
				connection.getPlatformDisabledReason(), connection.getPlatformDisabledAt());
	}

}

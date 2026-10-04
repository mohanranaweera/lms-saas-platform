package com.lms.integrationmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.media.ProviderIdentity;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.service.MediaConnectionViews.AuthorizationUrlView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionTestResult;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.OAuthStateStore.PendingConnection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The tenant's own video provider accounts (VIDEO Phase 3, ADR-018
 * Amendment 2) - today the tenant YouTube channel the Media Library uploads
 * into. Tenant Admin only ({@code INTEGRATIONS}); the tenant always comes
 * from the authenticated context.
 *
 * <p>Connecting reuses the ADR-018 OAuth flow with scope TENANT: state bound
 * to tenant + initiating admin, a public callback that trusts only that
 * state, and a parked result that only the SAME admin of the SAME tenant can
 * preview and confirm. A pending authorization confirmed by anyone else is
 * consumed and revoked at Google, exactly like the teacher flow.
 *
 * <p>Deliberately NOT {@code @Transactional}: Google calls happen with no
 * transaction open; {@link MediaConnectionStore} commits the outcome.
 */
@Service
public class TenantVideoProviderService {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	/** Owner-facing account states (plan §10). */
	public enum AccountStatus {

		NOT_CONNECTED, CONNECTED, EXPIRED, ERROR

	}

	/**
	 * @param connection the current connection, or the latest one when none is
	 * live (so the page can show when/why it was disconnected); {@code null} if
	 * the tenant never connected
	 * @param connectedByUserId the Tenant Admin who confirmed the connection
	 */
	public record VideoProviderAccountView(String provider, boolean available, AccountStatus status,
			ConnectionView connection, UUID connectedByUserId, boolean platformDisabled,
			String platformDisabledReason) {

		static VideoProviderAccountView of(boolean available, AccountStatus status, MediaProviderConnection latest,
				ConnectionView view) {
			return new VideoProviderAccountView(MediaProvider.YOUTUBE.name(), available, status, view,
					(latest == null) ? null : latest.getCreatedBy(), latest != null && latest.isPlatformDisabled(),
					(latest == null) ? null : latest.getPlatformDisabledReason());
		}

	}

	/** What the admin is about to confirm. Never contains tokens. */
	public record PendingChannelView(String channelId, String channelTitle, boolean replacesCurrentChannel,
			String currentChannelTitle) {

	}

	private final PermissionCheckService permissionCheckService;

	private final OAuthConnectFlowService connectFlow;

	private final OAuthStateStore stateStore;

	private final MediaConnectionStore store;

	private final MediaConnectionService connectionService;

	private final MediaProviderConnectionRepository repository;

	private final MediaConnectionViews views;

	private final TenantContext tenantContext;

	public TenantVideoProviderService(PermissionCheckService permissionCheckService,
			OAuthConnectFlowService connectFlow, OAuthStateStore stateStore, MediaConnectionStore store,
			MediaConnectionService connectionService, MediaProviderConnectionRepository repository,
			MediaConnectionViews views, TenantContext tenantContext) {
		this.permissionCheckService = permissionCheckService;
		this.connectFlow = connectFlow;
		this.stateStore = stateStore;
		this.store = store;
		this.connectionService = connectionService;
		this.repository = repository;
		this.views = views;
		this.tenantContext = tenantContext;
	}

	public List<VideoProviderAccountView> list() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		MediaProviderConnection latest = repository.findCurrentForTenant(MediaProvider.YOUTUBE)
			.or(() -> repository.findAllForTenant(MediaProvider.YOUTUBE).stream().findFirst())
			.orElse(null);
		return List.of(VideoProviderAccountView.of(connectFlow.isAvailable(MediaProvider.YOUTUBE), statusOf(latest),
				latest, (latest == null) ? null : views.toView(latest)));
	}

	public AuthorizationUrlView beginYouTubeConnect() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		requireNotPlatformDisabled();
		return connectFlow.beginTenantYouTubeConnect(IntegrationActors.currentUserId());
	}

	/**
	 * TD-008: while a Platform Admin has the tenant channel switched off, the
	 * tenant may not connect (or reconnect, or replace) a channel - that would
	 * sidestep the switch-off. The latest row counts even once disconnected.
	 */
	private void requireNotPlatformDisabled() {
		if (isPlatformDisabled()) {
			throw platformDisabled();
		}
	}

	private boolean isPlatformDisabled() {
		return repository.findAllForTenant(MediaProvider.YOUTUBE)
			.stream()
			.findFirst()
			.map(MediaProviderConnection::isPlatformDisabled)
			.orElse(false);
	}

	private static ProviderConflictException platformDisabled() {
		return new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_DISABLED_BY_PLATFORM,
				"Video uploads were switched off by the platform for your institute. Contact platform support.");
	}

	/** Non-consuming preview for the admin who started the connection; anyone else sees "expired". */
	public PendingChannelView previewPending(String pendingId) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		PendingConnection pending = stateStore.peekPending(pendingId)
			.filter(this::isOwnedByCaller)
			.orElseThrow(this::expiredRequest);
		Optional<MediaProviderConnection> current = repository.findCurrentForTenant(MediaProvider.YOUTUBE);
		boolean replaces = current.map(c -> !pending.externalAccountId().equals(c.getExternalAccountId()))
			.orElse(false);
		return new PendingChannelView(pending.externalAccountId(), pending.accountLabel(), replaces,
				replaces ? current.get().getExternalAccountLabel() : null);
	}

	/**
	 * Saves the parked channel. Replacing a DIFFERENT live channel needs
	 * {@code replaceChannel = true}; without it the request fails with 409 and
	 * the pending authorization is kept, so the admin can confirm again.
	 */
	public VideoProviderAccountView confirmPending(String pendingId, boolean replaceChannel) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		PendingConnection peeked = stateStore.peekPending(pendingId).orElseThrow(this::expiredRequest);
		if (!isOwnedByCaller(peeked)) {
			stateStore.consumePending(pendingId).ifPresent(this::rejectForeignPending);
			throw expiredRequest();
		}
		if (isPlatformDisabled()) {
			// The grant can never be used while switched off: discard and revoke it now.
			stateStore.consumePending(pendingId).ifPresent(connectFlow::revokePending);
			throw platformDisabled();
		}
		Optional<MediaProviderConnection> current = repository.findCurrentForTenant(MediaProvider.YOUTUBE);
		boolean replaces = current.map(c -> !peeked.externalAccountId().equals(c.getExternalAccountId()))
			.orElse(false);
		if (replaces && !replaceChannel) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_CHANNEL_REPLACEMENT_UNCONFIRMED,
					"This replaces your current YouTube channel. Confirm the replacement to continue.");
		}
		PendingConnection pending = stateStore.consumePending(pendingId).orElseThrow(this::expiredRequest);
		MediaProviderConnection saved;
		try {
			saved = store.saveTenantOAuthConnection(IntegrationActors.currentUserId(), MediaProvider.YOUTUBE,
					new ProviderIdentity(pending.externalAccountId(), pending.accountLabel(), pending.accountType()),
					pending.credentialsCiphertext(), pending.accessTokenExpiresAt(), pending.grantedScopes());
		}
		catch (DataIntegrityViolationException ex) {
			connectFlow.revokePending(pending);
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_CHANNEL_IN_USE,
					"This YouTube channel is already connected to another institute.");
		}
		return VideoProviderAccountView.of(true, statusOf(saved), saved, views.toView(saved));
	}

	public ConnectionTestResult test(UUID connectionId) {
		requireTenantVideoConnection(connectionId);
		return connectionService.adminTest(connectionId);
	}

	public ConnectionView disconnect(UUID connectionId, String reason) {
		requireTenantVideoConnection(connectionId);
		return connectionService.adminDisconnect(connectionId, reason);
	}

	/** 404 unless the id is this tenant's own YouTube channel connection (tenant filter + shape check). */
	private void requireTenantVideoConnection(UUID connectionId) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		MediaProviderConnection connection = store.require(connectionId);
		if (connection.getScope() != ConnectionScope.TENANT || connection.getProvider() != MediaProvider.YOUTUBE) {
			throw new NotFoundException("Connection not found");
		}
	}

	private boolean isOwnedByCaller(PendingConnection pending) {
		return pending.isTenantScope() && pending.tenantId().equals(tenantContext.getTenantId())
				&& pending.userId().equals(IntegrationActors.currentUserId());
	}

	private void rejectForeignPending(PendingConnection pending) {
		securityLog.atWarn()
			.setMessage("video_provider.pending_confirm_rejected")
			.addKeyValue("provider", pending.provider())
			.addKeyValue("sameTenant", pending.tenantId().equals(tenantContext.getTenantId()))
			.addKeyValue("tenantScope", pending.isTenantScope())
			.log();
		connectFlow.revokePending(pending);
	}

	private NotFoundException expiredRequest() {
		return new NotFoundException("This connection request has expired or was already used. Connect again.");
	}

	private static AccountStatus statusOf(MediaProviderConnection connection) {
		if (connection == null || connection.getStatus() == MediaConnectionStatus.DISCONNECTED) {
			return AccountStatus.NOT_CONNECTED;
		}
		if (connection.getStatus() == MediaConnectionStatus.NEEDS_RECONNECT) {
			return AccountStatus.EXPIRED;
		}
		return (connection.getLastError() != null) ? AccountStatus.ERROR : AccountStatus.CONNECTED;
	}

}

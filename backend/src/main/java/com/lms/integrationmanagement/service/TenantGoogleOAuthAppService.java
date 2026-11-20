package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.domain.PlatformOAuthProvider;
import com.lms.integrationmanagement.domain.TenantOAuthApp;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import com.lms.integrationmanagement.gateway.media.GoogleClientCredentials;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient.ClientCheck;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.repository.TenantOAuthAppRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant Admin → Video providers → the institute's own Google app for its
 * YouTube channel (VIDEO Phase 5c, ADR-018 Amendment 5 as revised by the
 * owner on 2026-10-05). Without one the platform app applies. {@code
 * INTEGRATIONS} VIEW / CREATE_EDIT; the tenant is always the caller's own.
 * The secret is write-only; every change is audited. When the app used for
 * the channel changes, the live channel is marked "needs reconnect" - Google
 * ties its grant to the app that issued it.
 */
@Service
public class TenantGoogleOAuthAppService {

	static final String AUDIT_SAVED = "video_provider.oauth_app_saved";

	static final String AUDIT_REMOVED = "video_provider.oauth_app_removed";

	static final String RECONNECT_REASON = "Your institute's Google app changed. Connect the channel again.";

	public enum Source {

		/** The institute's own app. */
		TENANT,
		/** The platform's app (saved by a Platform Admin, or the environment). */
		PLATFORM,
		/** Neither - YouTube can't be connected. */
		NOT_CONFIGURED

	}

	/**
	 * @param clientId the institute's own client id (null when it uses the platform app)
	 * @param secretSaved whether the institute's own secret is saved (its value is never returned)
	 */
	public record TenantGoogleOAuthAppView(Source source, String clientId, boolean secretSaved,
			boolean platformAppAvailable, String redirectUri, boolean encryptionAvailable, Instant updatedAt,
			boolean channelConnected) {

	}

	public record VerifyView(ClientCheck result, Source source, String message) {

	}

	private final TenantOAuthAppRepository repository;

	private final MediaProviderConnectionRepository connections;

	private final MediaConnectionStore store;

	private final MediaCredentialCipher cipher;

	private final GoogleClientCredentials credentials;

	private final YouTubeOAuthClient youTube;

	private final OAuthConnectFlowService connectFlow;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public TenantGoogleOAuthAppService(TenantOAuthAppRepository repository,
			MediaProviderConnectionRepository connections, MediaConnectionStore store, MediaCredentialCipher cipher,
			GoogleClientCredentials credentials, YouTubeOAuthClient youTube, OAuthConnectFlowService connectFlow,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.repository = repository;
		this.connections = connections;
		this.store = store;
		this.cipher = cipher;
		this.credentials = credentials;
		this.youTube = youTube;
		this.connectFlow = connectFlow;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public TenantGoogleOAuthAppView view() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		return currentView();
	}

	/** Saves (or replaces) the institute's own app; both values are always required. */
	@Transactional
	public TenantGoogleOAuthAppView save(String clientId, String clientSecret) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		UUID tenantId = tenantContext.getTenantId();
		String id = (clientId == null) ? "" : clientId.trim();
		String secret = (clientSecret == null) ? "" : clientSecret.trim();
		GoogleOAuthAppService.validate(id, secret, cipher);

		String effectiveBefore = effectiveClientId(tenantId);
		Optional<TenantOAuthApp> existing = repository.findForCurrentTenant(PlatformOAuthProvider.GOOGLE);
		String before = existing.map(TenantOAuthApp::getClientId).orElse(null);
		boolean secretChanged = existing.map(app -> !secret.equals(decryptQuietly(app, tenantId))).orElse(true);
		TenantOAuthApp app = existing.orElseGet(() -> new TenantOAuthApp(tenantId, PlatformOAuthProvider.GOOGLE));
		app.replace(id, cipher.encrypt(secret, GoogleClientCredentials.tenantSecretAad(tenantId)));
		TenantOAuthApp saved = repository.saveAndFlush(app);

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("clientIdBefore", before);
		metadata.put("clientIdAfter", id);
		metadata.put("secretChanged", secretChanged);
		metadata.put("effectiveClientIdBefore", effectiveBefore);
		boolean channelReset = resetChannelIfAppChanged(effectiveBefore, id);
		metadata.put("channelMarkedForReconnect", channelReset);
		auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(), AUDIT_SAVED, "tenant_oauth_app",
				saved.getId(), null, withoutNulls(metadata)));
		return currentView();
	}

	/** Removes the institute's own app; the platform app applies again. */
	@Transactional
	public TenantGoogleOAuthAppView remove() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		UUID tenantId = tenantContext.getTenantId();
		Optional<TenantOAuthApp> existing = repository.findForCurrentTenant(PlatformOAuthProvider.GOOGLE);
		if (existing.isPresent()) {
			TenantOAuthApp app = existing.get();
			repository.delete(app);
			repository.flush();
			String after = effectiveClientId(tenantId);
			boolean channelReset = resetChannelIfAppChanged(app.getClientId(), after);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("clientIdBefore", app.getClientId());
			metadata.put("effectiveClientIdAfter", after);
			metadata.put("channelMarkedForReconnect", channelReset);
			auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(), AUDIT_REMOVED,
					"tenant_oauth_app", app.getId(), null, withoutNulls(metadata)));
		}
		return currentView();
	}

	/**
	 * Asks Google whether the app this institute uses is valid. Not
	 * transactional: it calls Google ({@code .claude/rules/backend.md}).
	 */
	public VerifyView verify() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		UUID tenantId = tenantContext.getTenantId();
		GoogleClientCredentials.Credentials current = credentials.current(tenantId)
			.orElseThrow(() -> new FieldValidationException("Nothing to verify",
					List.of(new FieldError("clientId", "save a client id and secret first"))));
		ClientCheck result = youTube.verifyClient(current.clientId(), current.clientSecret(),
				connectFlow.redirectUri(MediaProvider.YOUTUBE));
		Source source = (current.source() == GoogleClientCredentials.Source.TENANT) ? Source.TENANT : Source.PLATFORM;
		return new VerifyView(result, source, GoogleOAuthAppService.verifyMessage(result));
	}

	private TenantGoogleOAuthAppView currentView() {
		Optional<TenantOAuthApp> own = repository.findForCurrentTenant(PlatformOAuthProvider.GOOGLE);
		boolean platformAvailable = credentials.isConfigured();
		Source source = own.isPresent() ? Source.TENANT : platformAvailable ? Source.PLATFORM : Source.NOT_CONFIGURED;
		boolean channelConnected = connections.findCurrentTenantYouTube()
			.stream()
			.anyMatch(connection -> connection.getStatus() == MediaConnectionStatus.ACTIVE);
		String redirect = connectFlow.redirectUri(MediaProvider.YOUTUBE);
		return new TenantGoogleOAuthAppView(source, own.map(TenantOAuthApp::getClientId).orElse(null),
				own.isPresent(), platformAvailable, redirect.startsWith("http") ? redirect : null,
				cipher.isConfigured(), own.map(TenantOAuthApp::getUpdatedAt).orElse(null), channelConnected);
	}

	/** Google ties the channel's grant to the app that issued it: a different app means reconnecting. */
	private boolean resetChannelIfAppChanged(String clientIdBefore, String clientIdAfter) {
		if (clientIdBefore == null || clientIdBefore.equals(clientIdAfter)) {
			return false;
		}
		// VIDEO Phase 5d: every live channel of the institute uses the same Google app.
		List<MediaProviderConnection> live = connections.findCurrentTenantYouTube()
			.stream()
			.filter(connection -> connection.getStatus() == MediaConnectionStatus.ACTIVE)
			.toList();
		live.forEach(connection -> store.markNeedsReconnect(connection.getId(), RECONNECT_REASON));
		return !live.isEmpty();
	}

	/** {@link AuditLogEntry} copies its metadata with {@code Map.copyOf}, which rejects null values. */
	private static Map<String, Object> withoutNulls(Map<String, Object> metadata) {
		metadata.values().removeIf(java.util.Objects::isNull);
		return metadata;
	}

	private String effectiveClientId(UUID tenantId) {
		try {
			return credentials.current(tenantId).map(GoogleClientCredentials.Credentials::clientId).orElse(null);
		}
		catch (ServiceUnavailableException | IllegalStateException ex) {
			return null;
		}
	}

	private String decryptQuietly(TenantOAuthApp app, UUID tenantId) {
		try {
			return cipher.decrypt(app.getClientSecretCiphertext(), GoogleClientCredentials.tenantSecretAad(tenantId));
		}
		catch (IllegalStateException ex) {
			return null;
		}
	}

}

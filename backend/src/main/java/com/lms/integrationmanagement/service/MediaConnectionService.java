package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionAuthType;
import com.lms.integrationmanagement.domain.ConnectionDisconnectedBy;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.domain.TenantMediaIntegrationSettings;
import com.lms.integrationmanagement.domain.VideoHost;
import com.lms.integrationmanagement.gateway.media.BunnyAccountClient;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.gateway.media.OAuthProviderClient;
import com.lms.integrationmanagement.gateway.media.ProviderIdentity;
import com.lms.integrationmanagement.gateway.media.TenantVideoHostClient;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionTestResult;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ImpactView;
import com.lms.integrationmanagement.service.MediaConnectionViews.ProviderToggleView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherConnectionRow;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherIntegrationsView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherProviderView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherProvidersView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TenantIntegrationsView;
import com.lms.integrationmanagement.service.MediaConnectionViews.VideoHostView;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.CredentialContext;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.StoredCredentials;
import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher self-service and Tenant Admin management of media provider
 * connections (MEDIA-INT-01 / ADR-018; contract {@code
 * docs/api/integrations.md}).
 *
 * <ul>
 * <li>Teachers act only on their own connections: another teacher's
 * connection id is a 404, never a 403.</li>
 * <li>Tenant Admin actions need {@code INTEGRATIONS} (VIEW for reads,
 * CREATE_EDIT otherwise). An admin can disconnect a teacher's connection but
 * never create one or read its tokens.</li>
 * <li>Provider calls happen with no DB transaction open; outcomes are
 * persisted through {@link MediaConnectionStore}. Only pure reads and the
 * settings writes below are {@code @Transactional}.</li>
 * </ul>
 */
@Service
public class MediaConnectionService {

	private static final Logger log = LoggerFactory.getLogger(MediaConnectionService.class);

	private static final List<MediaProvider> TEACHER_PROVIDERS = List.of(MediaProvider.ZOOM, MediaProvider.YOUTUBE,
			MediaProvider.VIMEO);

	private static final int MAX_KEY_LENGTH = 512;

	private static final int MAX_REASON_LENGTH = 500;

	private static final String SETTINGS_TARGET = "tenant_media_integration_settings";

	/** settings_json of a MEDIA-INT-01A Bunny connection (legacy rows keep their library settings). */
	private static final String BUNNY_ACCOUNT_SETTINGS = "{\"keyType\":\"ACCOUNT\"}";

	private final MediaProviderConnectionRepository repository;

	private final MediaConnectionStore store;

	private final MediaConnectionViews views;

	private final MediaCredentialsCodec codec;

	private final MediaTokenService tokenService;

	private final OAuthConnectFlowService connectFlow;

	private final TenantMediaSettingsService settingsService;

	private final TenantVideoHostClient videoHostClient;

	private final BunnyAccountClient bunnyAccountClient;

	private final BunnyLibraryService bunnyLibraryService;

	private final Map<MediaProvider, OAuthProviderClient> oauthClients = new EnumMap<>(MediaProvider.class);

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TeacherLookupApi teacherLookupApi;

	private final ClassSessionLookupApi classSessionLookupApi;

	public MediaConnectionService(MediaProviderConnectionRepository repository, MediaConnectionStore store,
			MediaConnectionViews views, MediaCredentialsCodec codec, MediaTokenService tokenService,
			OAuthConnectFlowService connectFlow, TenantMediaSettingsService settingsService,
			TenantVideoHostClient videoHostClient, BunnyAccountClient bunnyAccountClient,
			BunnyLibraryService bunnyLibraryService, List<OAuthProviderClient> clients,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext,
			TeacherLookupApi teacherLookupApi, ClassSessionLookupApi classSessionLookupApi) {
		this.repository = repository;
		this.store = store;
		this.views = views;
		this.codec = codec;
		this.tokenService = tokenService;
		this.connectFlow = connectFlow;
		this.settingsService = settingsService;
		this.videoHostClient = videoHostClient;
		this.bunnyAccountClient = bunnyAccountClient;
		this.bunnyLibraryService = bunnyLibraryService;
		clients.forEach(client -> oauthClients.put(client.provider(), client));
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.teacherLookupApi = teacherLookupApi;
		this.classSessionLookupApi = classSessionLookupApi;
	}

	// ------------------------------------------------------------------
	// Teacher - own connections
	// ------------------------------------------------------------------

	/** Not transactional: the Bunny library status may call Bunny (no transaction across provider calls). */
	public TeacherIntegrationsView teacherOverview() {
		UUID teacherUserId = IntegrationActors.requireTeacher();
		TenantMediaIntegrationSettings settings = settingsService.current();
		List<TeacherProviderView> providers = TEACHER_PROVIDERS.stream()
			.map(provider -> new TeacherProviderView(provider.name(), settings.isEnabled(provider),
					connectFlow.isAvailable(provider),
					views.toView(repository.findCurrentForTeacher(teacherUserId, provider)
						.orElseGet(() -> repository.findAllForTeacher(teacherUserId, provider)
							.stream()
							.findFirst()
							.orElse(null)))))
			.toList();
		return new TeacherIntegrationsView(providers, bunnyLibraryService.isBunnyConnected(),
				bunnyLibraryService.ownLibrary(teacherUserId));
	}

	public ConnectionTestResult testOwn(UUID connectionId) {
		return testConnection(requireOwn(connectionId));
	}

	public ImpactView impactOwn(UUID connectionId) {
		return impact(requireOwn(connectionId));
	}

	public ConnectionView disconnectOwn(UUID connectionId, String reason) {
		MediaProviderConnection connection = requireOwn(connectionId);
		return disconnect(connection, ConnectionDisconnectedBy.OWNER, optionalReason(reason));
	}

	// ------------------------------------------------------------------
	// Tenant Admin
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public TenantIntegrationsView adminOverview() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		TenantMediaIntegrationSettings settings = settingsService.current();
		Map<UUID, String> teacherNames = teacherLookupApi.listTeachers()
			.stream()
			.collect(Collectors.toMap(TeacherLookupApi.TeacherSummary::userId,
					summary -> Objects.requireNonNullElse(summary.name(), ""), (a, b) -> a));
		List<TeacherConnectionRow> rows = repository.findCurrentTeacherConnections()
			.stream()
			.map(connection -> new TeacherConnectionRow(connection.getOwnerUserId(),
					teacherNames.getOrDefault(connection.getOwnerUserId(), ""), views.toView(connection)))
			.sorted(Comparator.comparing(TeacherConnectionRow::teacherName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(row -> row.connection().provider()))
			.toList();
		return new TenantIntegrationsView(teacherProvidersView(settings), settings.getVideoHost().name(),
				views.toView(currentOrLatestTenantConnection(MediaProvider.DYNTUBE)),
				views.toView(currentOrLatestTenantConnection(MediaProvider.BUNNY)), rows);
	}

	@Transactional
	public TeacherProvidersView updateTeacherProviders(Boolean zoom, Boolean youtube, Boolean vimeo) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		if (zoom == null || youtube == null || vimeo == null) {
			throw new FieldValidationException("Invalid teacher provider settings",
					List.of(new FieldError("zoomEnabled", "zoomEnabled, youtubeEnabled and vimeoEnabled are required")));
		}
		TenantMediaIntegrationSettings settings = settingsService.currentForUpdate();
		settings.setTeacherProviders(zoom, youtube, vimeo);
		TenantMediaIntegrationSettings saved = settingsService.save(settings);
		auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(),
				"media_integration.teacher_providers_updated", SETTINGS_TARGET, saved.getId(), null,
				Map.of("zoomEnabled", zoom, "youtubeEnabled", youtube, "vimeoEnabled", vimeo)));
		return teacherProvidersView(saved);
	}

	public ConnectionView saveDynTube(String apiKey) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		requireCodec();
		boolean newKey = hasText(apiKey);
		if (newKey && apiKey.trim().length() > MAX_KEY_LENGTH) {
			throw invalid("apiKey", "must be at most " + MAX_KEY_LENGTH + " characters");
		}
		MediaProviderConnection current = repository.findCurrentForTenant(MediaProvider.DYNTUBE).orElse(null);
		if (!newKey && current == null) {
			throw invalid("apiKey", "is required");
		}
		if (!newKey && current.isActive()) {
			return views.toView(current);
		}
		String key = newKey ? apiKey.trim() : codec.open(current).apiKey();
		ProviderIdentity identity;
		try {
			identity = videoHostClient.verifyDynTube(key);
		}
		catch (MediaProviderException ex) {
			throw credentialFailure(ex, "DynTube", "apiKey");
		}
		return views.toView(store.saveTenantConnection(tenantContext.getTenantId(), IntegrationActors.currentUserId(),
				MediaProvider.DYNTUBE, identity,
				codec.seal(StoredCredentials.apiKey(key, null), tenantCredentialContext(MediaProvider.DYNTUBE)), null,
				newKey));
	}

	/**
	 * MEDIA-INT-01A: the tenant's Bunny.net account, by account-level API key
	 * only. Library id / CDN hostname are no longer input - each teacher's
	 * library is created by {@link BunnyLibraryService}.
	 */
	public ConnectionView saveBunny(String apiKey) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		requireCodec();
		boolean newKey = hasText(apiKey);
		if (newKey && apiKey.trim().length() > MAX_KEY_LENGTH) {
			throw invalid("apiKey", "must be at most " + MAX_KEY_LENGTH + " characters");
		}
		MediaProviderConnection current = repository.findCurrentForTenant(MediaProvider.BUNNY).orElse(null);
		if (!newKey && current == null) {
			throw invalid("apiKey", "is required");
		}
		if (!newKey && current.isActive()) {
			return views.toView(current);
		}
		String key = newKey ? apiKey.trim() : codec.open(current).apiKey();
		ProviderIdentity identity;
		try {
			identity = bunnyAccountClient.verifyAccount(key);
		}
		catch (MediaProviderException ex) {
			if (ex.getKind() == MediaProviderException.Kind.AUTH_REJECTED) {
				throw new FieldValidationException(ApiErrorCodes.PROVIDER_CREDENTIALS_INVALID,
						"Bunny.net rejected the API key",
						List.of(new FieldError("apiKey",
								"was rejected by Bunny.net - use an account API key, not a video library key")));
			}
			throw credentialFailure(ex, "Bunny.net", "apiKey");
		}
		return views.toView(store.saveTenantConnection(tenantContext.getTenantId(), IntegrationActors.currentUserId(),
				MediaProvider.BUNNY, identity,
				codec.seal(StoredCredentials.apiKey(key, null), tenantCredentialContext(MediaProvider.BUNNY)),
				BUNNY_ACCOUNT_SETTINGS, newKey));
	}

	public ConnectionTestResult adminTest(UUID connectionId) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		return testConnection(store.require(connectionId));
	}

	public ImpactView adminImpact(UUID connectionId) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		return impact(store.require(connectionId));
	}

	public ConnectionView adminDisconnect(UUID connectionId, String reason) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		MediaProviderConnection connection = store.require(connectionId);
		String trimmed = optionalReason(reason);
		if (connection.getScope() == ConnectionScope.TEACHER && trimmed == null) {
			throw invalid("reason", "is required when disconnecting a teacher's account");
		}
		if (connection.getScope() == ConnectionScope.TENANT && !connection.isDisconnected()
				&& settingsService.current().getVideoHost().name().equals(connection.getProvider().name())) {
			throw new ConflictException("This account is the active video host. Set the video host to None first.");
		}
		return disconnect(connection, ConnectionDisconnectedBy.TENANT_ADMIN, trimmed);
	}

	@Transactional
	public VideoHostView setVideoHost(String videoHost) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		VideoHost host;
		try {
			host = VideoHost.valueOf(Objects.requireNonNull(videoHost));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw invalid("videoHost", "must be NONE, DYNTUBE or BUNNY");
		}
		if (host != VideoHost.NONE) {
			MediaProvider provider = MediaProvider.valueOf(host.name());
			boolean active = repository.findCurrentForTenant(provider).map(MediaProviderConnection::isActive)
				.orElse(false);
			if (!active) {
				throw new ProviderConflictException(ApiErrorCodes.PROVIDER_NOT_CONNECTED,
						MediaTokenService.displayName(provider) + " is not connected.");
			}
		}
		TenantMediaIntegrationSettings settings = settingsService.currentForUpdate();
		VideoHost previous = settings.getVideoHost();
		settings.setVideoHost(host);
		TenantMediaIntegrationSettings saved = settingsService.save(settings);
		auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(), "media_integration.video_host_changed",
				SETTINGS_TARGET, saved.getId(), null, Map.of("from", previous.name(), "to", host.name())));
		return new VideoHostView(host.name());
	}

	// ------------------------------------------------------------------
	// Shared
	// ------------------------------------------------------------------

	private MediaProviderConnection requireOwn(UUID connectionId) {
		UUID teacherUserId = IntegrationActors.requireTeacher();
		MediaProviderConnection connection = store.require(connectionId);
		if (!teacherUserId.equals(connection.getOwnerUserId())) {
			throw new NotFoundException("Connection not found");
		}
		return connection;
	}

	private ConnectionTestResult testConnection(MediaProviderConnection connection) {
		if (connection.isDisconnected()) {
			throw new ConflictException("This connection is disconnected");
		}
		UUID id = connection.getId();
		try {
			ProviderIdentity identity = (connection.getAuthType() == ConnectionAuthType.OAUTH)
					? oauthClient(connection.getProvider()).fetchIdentity(tokenService.validAccessToken(id))
					: verifyApiKeyConnection(connection);
			MediaProviderConnection verified = store.markVerified(id, identity);
			String label = (verified.getExternalAccountLabel() == null) ? "" : " as " + verified.getExternalAccountLabel();
			return new ConnectionTestResult(true, verified.getStatus().name(), verified.getLastVerifiedAt(),
					"Connected" + label);
		}
		catch (ProviderConflictException ex) {
			// Token refresh was rejected - already marked NEEDS_RECONNECT.
			return failedTest(id, ex.getMessage());
		}
		catch (MediaProviderException ex) {
			if (ex.getKind() == MediaProviderException.Kind.AUTH_REJECTED
					|| ex.getKind() == MediaProviderException.Kind.NOT_FOUND) {
				store.markNeedsReconnect(id, ex.getMessage());
			}
			else {
				store.recordError(id, ex.getMessage());
			}
			return failedTest(id, ex.getMessage());
		}
	}

	private ConnectionTestResult failedTest(UUID connectionId, String message) {
		MediaProviderConnection after = store.require(connectionId);
		return new ConnectionTestResult(false, after.getStatus().name(), after.getLastVerifiedAt(), message);
	}

	private ProviderIdentity verifyApiKeyConnection(MediaProviderConnection connection) {
		StoredCredentials credentials = codec.open(connection);
		if (connection.getProvider() == MediaProvider.DYNTUBE) {
			return videoHostClient.verifyDynTube(credentials.apiKey());
		}
		// A legacy (MEDIA-INT-01) library key is rejected by the account API -> NEEDS_RECONNECT.
		return bunnyAccountClient.verifyAccount(credentials.apiKey());
	}

	private ImpactView impact(MediaProviderConnection connection) {
		if (connection.getProvider() != MediaProvider.ZOOM) {
			return new ImpactView(0);
		}
		return new ImpactView(classSessionLookupApi.countUpcomingSessionsHostedBy(connection.getId()));
	}

	private ConnectionView disconnect(MediaProviderConnection connection, ConnectionDisconnectedBy by, String reason) {
		if (connection.isDisconnected()) {
			throw new ConflictException("This connection is already disconnected");
		}
		MediaProviderConnection saved = store.disconnect(connection.getId(), IntegrationActors.currentUserId(), by,
				reason);
		if (connection.getAuthType() == ConnectionAuthType.OAUTH) {
			revokeQuietly(connection);
		}
		return views.toView(saved);
	}

	/** Best-effort, after the local disconnect has committed; never fails the request. */
	private void revokeQuietly(MediaProviderConnection connection) {
		try {
			oauthClient(connection.getProvider()).revoke(tokenService.currentTokens(connection));
		}
		catch (RuntimeException ex) {
			log.atInfo()
				.setMessage("media_integration.revoke_failed")
				.addKeyValue("connectionId", connection.getId())
				.addKeyValue("provider", connection.getProvider())
				.log();
		}
	}

	private OAuthProviderClient oauthClient(MediaProvider provider) {
		OAuthProviderClient client = oauthClients.get(provider);
		if (client == null) {
			throw new IllegalArgumentException(provider + " has no OAuth client");
		}
		return client;
	}

	private MediaProviderConnection currentOrLatestTenantConnection(MediaProvider provider) {
		return repository.findCurrentForTenant(provider)
			.orElseGet(() -> repository.findAllForTenant(provider).stream().findFirst().orElse(null));
	}

	private TeacherProvidersView teacherProvidersView(TenantMediaIntegrationSettings settings) {
		Function<MediaProvider, ProviderToggleView> toggle = provider -> new ProviderToggleView(
				settings.isEnabled(provider), connectFlow.isAvailable(provider));
		return new TeacherProvidersView(toggle.apply(MediaProvider.ZOOM), toggle.apply(MediaProvider.YOUTUBE),
				toggle.apply(MediaProvider.VIMEO));
	}

	private CredentialContext tenantCredentialContext(MediaProvider provider) {
		return new CredentialContext(tenantContext.getTenantId(), provider, null);
	}

	private void requireCodec() {
		if (!codec.isConfigured()) {
			throw new ServiceUnavailableException("Integrations are not available right now.");
		}
	}

	private static RuntimeException credentialFailure(MediaProviderException ex, String providerName, String field) {
		return switch (ex.getKind()) {
			case AUTH_REJECTED -> new FieldValidationException(ApiErrorCodes.PROVIDER_CREDENTIALS_INVALID,
					providerName + " rejected the credentials",
					List.of(new FieldError(field, "was rejected by " + providerName)));
			case NOT_FOUND, REJECTED -> new FieldValidationException(ApiErrorCodes.PROVIDER_CREDENTIALS_INVALID,
					providerName + " refused these settings",
					List.of(new FieldError(field, "was refused by " + providerName)));
			default -> new ServiceUnavailableException(providerName + " could not be reached. Try again later.");
		};
	}

	private static String optionalReason(String reason) {
		if (reason == null || reason.isBlank()) {
			return null;
		}
		String trimmed = reason.trim();
		if (trimmed.length() > MAX_REASON_LENGTH) {
			throw invalid("reason", "must be at most " + MAX_REASON_LENGTH + " characters");
		}
		return trimmed;
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("Invalid integration settings", List.of(new FieldError(field, message)));
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}

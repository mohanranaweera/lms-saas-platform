package com.lms.integrationmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.gateway.media.OAuthProviderClient;
import com.lms.integrationmanagement.gateway.media.OAuthTokens;
import com.lms.integrationmanagement.gateway.media.ProviderIdentity;
import com.lms.integrationmanagement.service.MediaConnectionViews.AuthorizationUrlView;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.service.MediaConnectionViews.ConnectionView;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.CredentialContext;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.StoredCredentials;
import com.lms.integrationmanagement.service.OAuthStateStore.PendingConnection;
import com.lms.integrationmanagement.service.OAuthStateStore.PendingAuthorization;
import com.lms.tenantmanagement.api.TenantLookupApi;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The OAuth "Connect" flow for a teacher's own Zoom/YouTube/Vimeo account
 * (ADR-018 §3).
 *
 * <ol>
 * <li>{@link #beginConnect}: authenticated teacher request - issues a
 * single-use state bound (in Redis) to the tenant, the teacher and the PKCE
 * verifier, and returns the provider's consent URL.</li>
 * <li>{@link #completeCallback}: the public callback. The tenant and teacher
 * come ONLY from the consumed state - never from the request. The code is
 * exchanged and the account identity fetched with no DB transaction open.
 * Nothing is saved yet: the result is parked as a single-use PENDING
 * authorization and the browser is sent back to the tenant's own site
 * (built from the tenant's stored subdomain - no open redirect) with its
 * pending id.</li>
 * <li>{@link #confirmPending}: the signed-in teacher's own request. The
 * connection is saved only if that teacher (and tenant) is the one who
 * started it. This binds the result to the teacher's session, so a consent
 * link forwarded to someone else can never attach THEIR account to the
 * teacher's profile (security review S1); it also re-checks that the caller
 * is still an active teacher (S3).</li>
 * </ol>
 *
 * Deliberately NOT {@code @Transactional}.
 */
@Service
public class OAuthConnectFlowService {

	private static final Logger log = LoggerFactory.getLogger(OAuthConnectFlowService.class);

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private static final String CALLBACK_PATH = "/api/v1/integrations/oauth/{provider}/callback";

	private static final String RETURN_PATH = "/teacher/settings/integrations";

	private static final String DEFAULT_ERROR_PATH = "/integration-error";

	private final Map<MediaProvider, OAuthProviderClient> clients = new EnumMap<>(MediaProvider.class);

	private final OAuthStateStore stateStore;

	private final MediaCredentialsCodec codec;

	private final MediaConnectionStore store;

	private final TenantMediaSettingsService settingsService;

	private final MediaIntegrationProperties properties;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	private final MediaConnectionViews views;

	public OAuthConnectFlowService(List<OAuthProviderClient> oauthClients, OAuthStateStore stateStore,
			MediaCredentialsCodec codec, MediaConnectionStore store, TenantMediaSettingsService settingsService,
			MediaIntegrationProperties properties, TenantLookupApi tenantLookupApi, TenantContext tenantContext,
			MediaConnectionViews views) {
		oauthClients.forEach(client -> clients.put(client.provider(), client));
		this.stateStore = stateStore;
		this.codec = codec;
		this.store = store;
		this.settingsService = settingsService;
		this.properties = properties;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
		this.views = views;
	}

	/** Whether a teacher could connect this provider right now (app, key and URLs configured). */
	public boolean isAvailable(MediaProvider provider) {
		OAuthProviderClient client = clients.get(provider);
		return client != null && client.isConfigured() && codec.isConfigured()
				&& properties.isOAuthPlumbingConfigured();
	}

	public AuthorizationUrlView beginConnect(String providerSegment) {
		UUID teacherUserId = IntegrationActors.requireTeacher();
		MediaProvider provider = MediaProvider.fromPathSegment(providerSegment)
			.filter(MediaProvider::isTeacherScoped)
			.orElseThrow(() -> new NotFoundException("Integration not found"));
		if (!isAvailable(provider)) {
			throw new ServiceUnavailableException(
					MediaTokenService.displayName(provider) + " is not available right now.");
		}
		if (!settingsService.isEnabled(provider)) {
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_DISABLED_BY_TENANT,
					MediaTokenService.displayName(provider) + " is not enabled by your institute.");
		}
		OAuthProviderClient client = clients.get(provider);
		String verifier = client.supportsPkce() ? OAuthStateStore.randomToken() : null;
		String state = stateStore
			.issue(new PendingAuthorization(tenantContext.getTenantId(), teacherUserId, provider, verifier));
		return new AuthorizationUrlView(
				client.authorizationUrl(state, (verifier == null) ? null : s256(verifier), redirectUri(provider)));
	}

	/**
	 * Handles the provider's redirect back. Never throws: every outcome is a
	 * redirect (success, denied, failed, or the neutral platform error page
	 * for a state we cannot trust).
	 */
	public URI completeCallback(String providerSegment, String code, String error, String state) {
		Optional<PendingAuthorization> consumed = stateStore.consume(state);
		Optional<MediaProvider> provider = MediaProvider.fromPathSegment(providerSegment);
		if (consumed.isEmpty() || provider.isEmpty() || consumed.get().provider() != provider.get()) {
			securityLog.atWarn()
				.setMessage("media_integration.oauth_state_rejected")
				.addKeyValue("provider", providerSegment)
				.addKeyValue("stateKnown", consumed.isPresent())
				.log();
			return errorUri();
		}
		PendingAuthorization pending = consumed.get();
		Optional<String> returnBase = tenantReturnBase(pending.tenantId());
		if (returnBase.isEmpty()) {
			return errorUri();
		}
		String p = pending.provider().pathSegment();
		if (error != null || code == null || code.isBlank()) {
			String outcome = "access_denied".equals(error) ? "denied" : "failed";
			return returnUri(returnBase.get(), "error", outcome, p);
		}

		OAuthProviderClient client = clients.get(pending.provider());
		OAuthTokens tokens = null;
		try {
			tokens = client.exchangeCode(code, pending.codeVerifier(), redirectUri(pending.provider()));
			ProviderIdentity identity = client.fetchIdentity(tokens.accessToken());
			String ciphertext = codec.seal(StoredCredentials.oauth(tokens.accessToken(), tokens.refreshToken()),
					new CredentialContext(pending.tenantId(), pending.provider(), pending.userId()));
			String pendingId = stateStore.storePending(new PendingConnection(pending.tenantId(), pending.userId(),
					pending.provider(), identity.externalId(), identity.label(), identity.accountType(), ciphertext,
					tokens.expiresAt(), tokens.scope()));
			return returnUri(returnBase.get(), "pending", pendingId, p);
		}
		catch (MediaProviderException | ServiceUnavailableException | DataAccessException
				| IllegalStateException ex) {
			log.atWarn()
				.setMessage("media_integration.oauth_connect_failed")
				.addKeyValue("tenantId", pending.tenantId())
				.addKeyValue("provider", pending.provider())
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			if (tokens != null) {
				revokeQuietly(client, tokens);
			}
			return returnUri(returnBase.get(), "error", "failed", p);
		}
	}

	/**
	 * Saves a parked authorization for the signed-in teacher who started it.
	 * Anyone else - another teacher, another tenant, a replay - gets the same
	 * "expired" 404, the pending entry is consumed, and the provider tokens are
	 * revoked so a forwarded consent never leaves a usable grant behind.
	 */
	public ConnectionView confirmPending(String pendingId) {
		UUID teacherUserId = IntegrationActors.requireTeacher();
		PendingConnection pending = stateStore.consumePending(pendingId).orElseThrow(this::expiredRequest);
		boolean sameTenant = pending.tenantId().equals(tenantContext.getTenantId());
		if (!sameTenant || !pending.userId().equals(teacherUserId)) {
			securityLog.atWarn()
				.setMessage("media_integration.pending_confirm_rejected")
				.addKeyValue("provider", pending.provider())
				.addKeyValue("sameTenant", sameTenant)
				.log();
			revokePending(pending);
			throw expiredRequest();
		}
		if (!settingsService.isEnabled(pending.provider())) {
			revokePending(pending);
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_DISABLED_BY_TENANT,
					MediaTokenService.displayName(pending.provider()) + " is not enabled by your institute.");
		}
		MediaProviderConnection saved = store.saveTeacherConnection(teacherUserId, pending.provider(),
				new ProviderIdentity(pending.externalAccountId(), pending.accountLabel(), pending.accountType()),
				pending.credentialsCiphertext(), pending.accessTokenExpiresAt(), pending.grantedScopes());
		return views.toView(saved);
	}

	private NotFoundException expiredRequest() {
		return new NotFoundException("This connection request has expired or was already used. Connect again.");
	}

	private void revokePending(PendingConnection pending) {
		try {
			StoredCredentials credentials = codec.open(pending.credentialsCiphertext(),
					new CredentialContext(pending.tenantId(), pending.provider(), pending.userId()));
			revokeQuietly(clients.get(pending.provider()),
					new OAuthTokens(credentials.accessToken(), credentials.refreshToken(), null, null));
		}
		catch (RuntimeException ex) {
			log.atInfo().setMessage("media_integration.revoke_failed").addKeyValue("provider", pending.provider()).log();
		}
	}

	private void revokeQuietly(OAuthProviderClient client, OAuthTokens tokens) {
		try {
			client.revoke(tokens);
		}
		catch (RuntimeException ex) {
			log.atInfo().setMessage("media_integration.revoke_failed").addKeyValue("provider", client.provider()).log();
		}
	}

	String redirectUri(MediaProvider provider) {
		return stripTrailingSlash(properties.getCallbackBaseUrl())
				+ CALLBACK_PATH.replace("{provider}", provider.pathSegment());
	}

	private Optional<String> tenantReturnBase(UUID tenantId) {
		// TEN-DOM-01: the tenant's primary custom domain when it has one.
		return tenantLookupApi.tenantOrigin(tenantId, properties.getTenantUrlTemplate())
			.map(origin -> stripTrailingSlash(origin) + RETURN_PATH);
	}

	private static URI returnUri(String base, String key, String value, String provider) {
		UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base).queryParam(key, value);
		if (provider != null) {
			builder.queryParam("provider", provider);
		}
		return builder.encode().build().toUri();
	}

	private URI errorUri() {
		String configured = properties.getPlatformErrorUrl();
		return URI.create((configured == null || configured.isBlank()) ? DEFAULT_ERROR_PATH : configured);
	}

	private static String stripTrailingSlash(String value) {
		return (value != null && value.endsWith("/")) ? value.substring(0, value.length() - 1) : value;
	}

	/** RFC 7636 S256 code challenge. */
	static String s256(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

}

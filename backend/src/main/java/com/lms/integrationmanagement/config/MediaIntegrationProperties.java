package com.lms.integrationmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@code integrations.media.*} - MEDIA-INT-01 / ADR-018 configuration.
 *
 * <ul>
 * <li>{@code encryption-key}: AES-256 key (Base64, 32 bytes) for provider
 * credentials at rest ({@code MEDIA_CREDENTIALS_ENCRYPTION_KEY}).</li>
 * <li>{@code callback-base-url}: the public origin that serves {@code
 * /api/v1/integrations/oauth/{provider}/callback} - the single redirect URI
 * registered with every provider's OAuth app.</li>
 * <li>{@code tenant-url-template}: tenant site origin, {@code {subdomain}}
 * substituted from the tenant's own record (never from request input) -
 * where the teacher is sent back after connecting.</li>
 * <li>{@code platform-error-url}: neutral page for an unresolvable OAuth
 * state.</li>
 * <li>Per-provider OAuth client id/secret and API base URLs. Base URLs are
 * fixed configuration (never request input) so tests can point them at a
 * local stub server.</li>
 * </ul>
 *
 * Every secret defaults to empty: the affected provider reports
 * "unavailable" and connecting fails closed. No real secret is ever
 * committed.
 */
@Component
@ConfigurationProperties(prefix = "integrations.media")
public class MediaIntegrationProperties {

	private String encryptionKey = "";

	private String callbackBaseUrl = "";

	private String tenantUrlTemplate = "";

	private String platformErrorUrl = "";

	private final OAuthProvider zoom = new OAuthProvider("https://zoom.us", "https://zoom.us", "https://api.zoom.us/v2");

	private final OAuthProvider google = new OAuthProvider("https://accounts.google.com",
			"https://oauth2.googleapis.com", "https://www.googleapis.com");

	private final OAuthProvider vimeo = new OAuthProvider("https://api.vimeo.com", "https://api.vimeo.com",
			"https://api.vimeo.com");

	private final DynTubeProvider dyntube = new DynTubeProvider("https://api.dyntube.com/v1",
			"https://upload.dyntube.com/v1");

	private final BunnyProvider bunny = new BunnyProvider("https://video.bunnycdn.com", "https://api.bunny.net");

	/** Zoom app "Secret Token" used to sign webhook deliveries. */
	private String zoomWebhookSecretToken = "";

	public String getEncryptionKey() {
		return encryptionKey;
	}

	public void setEncryptionKey(String encryptionKey) {
		this.encryptionKey = encryptionKey;
	}

	public String getCallbackBaseUrl() {
		return callbackBaseUrl;
	}

	public void setCallbackBaseUrl(String callbackBaseUrl) {
		this.callbackBaseUrl = callbackBaseUrl;
	}

	public String getTenantUrlTemplate() {
		return tenantUrlTemplate;
	}

	public void setTenantUrlTemplate(String tenantUrlTemplate) {
		this.tenantUrlTemplate = tenantUrlTemplate;
	}

	public String getPlatformErrorUrl() {
		return platformErrorUrl;
	}

	public void setPlatformErrorUrl(String platformErrorUrl) {
		this.platformErrorUrl = platformErrorUrl;
	}

	public OAuthProvider getZoom() {
		return zoom;
	}

	public OAuthProvider getGoogle() {
		return google;
	}

	public OAuthProvider getVimeo() {
		return vimeo;
	}

	public DynTubeProvider getDyntube() {
		return dyntube;
	}

	public BunnyProvider getBunny() {
		return bunny;
	}

	public String getZoomWebhookSecretToken() {
		return zoomWebhookSecretToken;
	}

	public void setZoomWebhookSecretToken(String zoomWebhookSecretToken) {
		this.zoomWebhookSecretToken = zoomWebhookSecretToken;
	}

	/** True when the shared OAuth plumbing (callback URL + tenant return URL) is configured. */
	public boolean isOAuthPlumbingConfigured() {
		return StringUtils.hasText(callbackBaseUrl) && StringUtils.hasText(tenantUrlTemplate);
	}

	public static class OAuthProvider {

		private String clientId = "";

		private String clientSecret = "";

		/** Where the user's browser is sent to consent. */
		private String authBaseUrl;

		/** Server-to-server token exchange/refresh/revoke origin. */
		private String tokenBaseUrl;

		private String apiBaseUrl;

		OAuthProvider(String authBaseUrl, String tokenBaseUrl, String apiBaseUrl) {
			this.authBaseUrl = authBaseUrl;
			this.tokenBaseUrl = tokenBaseUrl;
			this.apiBaseUrl = apiBaseUrl;
		}

		public boolean isConfigured() {
			return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
		}

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public String getAuthBaseUrl() {
			return authBaseUrl;
		}

		public void setAuthBaseUrl(String authBaseUrl) {
			this.authBaseUrl = authBaseUrl;
		}

		public String getTokenBaseUrl() {
			return tokenBaseUrl;
		}

		public void setTokenBaseUrl(String tokenBaseUrl) {
			this.tokenBaseUrl = tokenBaseUrl;
		}

		public String getApiBaseUrl() {
			return apiBaseUrl;
		}

		public void setApiBaseUrl(String apiBaseUrl) {
			this.apiBaseUrl = apiBaseUrl;
		}

	}

	public static class ApiProvider {

		private String apiBaseUrl;

		ApiProvider(String apiBaseUrl) {
			this.apiBaseUrl = apiBaseUrl;
		}

		public String getApiBaseUrl() {
			return apiBaseUrl;
		}

		public void setApiBaseUrl(String apiBaseUrl) {
			this.apiBaseUrl = apiBaseUrl;
		}

	}

	/**
	 * DynTube (VIDEO Phase 13, ADR-026): {@code api-base-url} is the
	 * management API (also the host of tokenized playlists), {@code
	 * upload-base-url} the separate binary upload host.
	 */
	public static class DynTubeProvider extends ApiProvider {

		private String uploadBaseUrl;

		DynTubeProvider(String apiBaseUrl, String uploadBaseUrl) {
			super(apiBaseUrl);
			this.uploadBaseUrl = uploadBaseUrl;
		}

		public String getUploadBaseUrl() {
			return uploadBaseUrl;
		}

		public void setUploadBaseUrl(String uploadBaseUrl) {
			this.uploadBaseUrl = uploadBaseUrl;
		}

	}

	/**
	 * Bunny has two APIs (MEDIA-INT-01A): {@code api-base-url} is the Stream
	 * API (per-library key, videos), {@code account-api-base-url} is the
	 * account API (account key, video library management).
	 */
	public static class BunnyProvider extends ApiProvider {

		private String accountApiBaseUrl;

		BunnyProvider(String apiBaseUrl, String accountApiBaseUrl) {
			super(apiBaseUrl);
			this.accountApiBaseUrl = accountApiBaseUrl;
		}

		public String getAccountApiBaseUrl() {
			return accountApiBaseUrl;
		}

		public void setAccountApiBaseUrl(String accountApiBaseUrl) {
			this.accountApiBaseUrl = accountApiBaseUrl;
		}

	}

}

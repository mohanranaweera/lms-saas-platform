package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Credential checks for the tenant-level video hosts (ADR-018 §1). This
 * phase only verifies keys; uploading/playback is MEDIA-INT-03.
 *
 * <p>Only the fixed, configured API base URLs are ever called - a
 * tenant-supplied value (e.g. Bunny's CDN hostname) is never used as a
 * request target (SSRF).
 *
 * <p>DynTube's endpoint ({@code GET /projects}, bearer API key) could not be
 * verified against published docs during MEDIA-INT-01; the base URL is
 * configurable ({@code integrations.media.dyntube.api-base-url}) and this
 * check must be confirmed against a real DynTube account before go-live.
 */
@Component
public class TenantVideoHostClient {

	private final MediaIntegrationProperties properties;

	private final ProviderHttpClient http;

	public TenantVideoHostClient(MediaIntegrationProperties properties, ProviderHttpClient http) {
		this.properties = properties;
		this.http = http;
	}

	/** @throws MediaProviderException AUTH_REJECTED for a bad key */
	public ProviderIdentity verifyDynTube(String apiKey) {
		http.get(URI.create(properties.getDyntube().getApiBaseUrl() + "/projects"), ProviderHttpClient.bearer(apiKey));
		return new ProviderIdentity(null, "DynTube", null);
	}

	/**
	 * Lists at most one video in the library - proves both the Stream API key
	 * ({@code AccessKey} header) and the library id.
	 * @throws MediaProviderException AUTH_REJECTED for a bad key, NOT_FOUND for a bad library id
	 */
	public ProviderIdentity verifyBunny(String apiKey, String libraryId) {
		URI uri = UriComponentsBuilder
			.fromUriString(properties.getBunny().getApiBaseUrl() + "/library/{libraryId}/videos")
			.queryParam("page", 1)
			.queryParam("itemsPerPage", 1)
			.encode()
			.buildAndExpand(libraryId)
			.toUri();
		http.get(uri, headers -> headers.set("AccessKey", apiKey));
		return new ProviderIdentity(libraryId, "Bunny library " + libraryId, null);
	}

}

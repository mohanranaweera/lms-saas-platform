package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import org.springframework.stereotype.Component;

/**
 * DynTube credential check (ADR-018 §1). This phase only verifies keys;
 * uploading/playback is MEDIA-INT-03. Bunny.net uses the account API via
 * {@link BunnyAccountClient} (MEDIA-INT-01A).
 *
 * <p>Only the fixed, configured API base URL is ever called (SSRF).
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

}

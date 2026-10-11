package com.lms.integrationmanagement.gateway.media;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * DynTube account check (ADR-018 §1; contract verified against a real
 * account in VIDEO Phase 13, ADR-026): {@code GET /projects} with the bearer
 * API key. Bunny.net uses the account API via {@link BunnyAccountClient}
 * (MEDIA-INT-01A).
 *
 * <p>Only the fixed, configured API base URL is ever called (SSRF).
 */
@Component
public class TenantVideoHostClient {

	private final DynTubeClient dynTube;

	public TenantVideoHostClient(DynTubeClient dynTube) {
		this.dynTube = dynTube;
	}

	/** @throws MediaProviderException AUTH_REJECTED for a bad key */
	public ProviderIdentity verifyDynTube(String apiKey) {
		dynTube.listProjects(apiKey);
		return new ProviderIdentity(null, "DynTube", null);
	}

	/** The ids of the account's projects (also proves the key works). */
	public Set<String> dynTubeProjectIds(String apiKey) {
		return dynTube.listProjects(apiKey).stream().map(DynTubeClient.Project::id).collect(Collectors.toSet());
	}

}

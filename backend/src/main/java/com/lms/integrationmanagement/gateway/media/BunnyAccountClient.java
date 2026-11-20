package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * bunny.net account API calls (MEDIA-INT-01A / ADR-018 Amendment 1), made
 * with the tenant's account-level API key ({@code AccessKey} header).
 *
 * <p>A fixed allow-list: list and create video libraries - nothing else
 * (no DNS, billing, storage or delete calls). Only the configured
 * {@code integrations.media.bunny.account-api-base-url} is ever called; the
 * library id is a {@code long} and the search term is our own teacher tag,
 * never request input (SSRF).
 *
 * <p>Returned {@link BunnyLibrary} values never carry the library's own API
 * keys.
 */
@Component
public class BunnyAccountClient {

	/** A Bunny video library - id, name, pull zone. No secrets. */
	public record BunnyLibrary(long id, String name, Long pullZoneId) {

	}

	private static final int SEARCH_PAGE_SIZE = 100;

	private static final int LIST_PAGE_SIZE = 1000;

	static final int MAX_LIST_PAGES = 10;

	private final MediaIntegrationProperties properties;

	private final ProviderHttpClient http;

	public BunnyAccountClient(MediaIntegrationProperties properties, ProviderHttpClient http) {
		this.properties = properties;
		this.http = http;
	}

	/**
	 * Proves the key is an account key: lists (at most 5) video libraries. A
	 * library-only Stream key is rejected by Bunny here.
	 * @throws MediaProviderException AUTH_REJECTED for a bad or library-only key
	 */
	public ProviderIdentity verifyAccount(String accountApiKey) {
		http.get(libraries().queryParam("page", 1).queryParam("perPage", 5).build().toUri(), accessKey(accountApiKey));
		return new ProviderIdentity(null, "Bunny.net account", null);
	}

	/** The library whose name carries {@code [tag]}, if the account has one. */
	public Optional<BunnyLibrary> findLibraryByTag(String accountApiKey, String tag) {
		URI uri = libraries().queryParam("page", 1)
			.queryParam("perPage", SEARCH_PAGE_SIZE)
			.queryParam("search", tag)
			.encode()
			.build()
			.toUri();
		JsonNode response = http.get(uri, accessKey(accountApiKey));
		// page > 0 returns {Items: [...]}; tolerate a plain array too.
		JsonNode items = response.isArray() ? response : response.path("Items");
		String marker = "[" + tag + "]";
		for (JsonNode item : items) {
			BunnyLibrary library = toLibrary(item);
			if (library != null && library.name() != null && library.name().contains(marker)) {
				return Optional.of(library);
			}
		}
		return Optional.empty();
	}

	/**
	 * Ids of every library this key can see - one call per 1000 libraries
	 * (security review M-1: never one call per library), capped at
	 * {@link #MAX_LIST_PAGES} pages.
	 */
	public Set<Long> listLibraryIds(String accountApiKey) {
		Set<Long> ids = new HashSet<>();
		for (int page = 1; page <= MAX_LIST_PAGES; page++) {
			JsonNode response = http.get(
					libraries().queryParam("page", page).queryParam("perPage", LIST_PAGE_SIZE).build().toUri(),
					accessKey(accountApiKey));
			JsonNode items = response.isArray() ? response : response.path("Items");
			for (JsonNode item : items) {
				BunnyLibrary library = toLibrary(item);
				if (library != null) {
					ids.add(library.id());
				}
			}
			if (response.isArray() || !response.path("HasMoreItems").asBoolean(false)) {
				break;
			}
		}
		return ids;
	}

	/** @throws MediaProviderException on any failure; nothing is returned half-made */
	public BunnyLibrary createLibrary(String accountApiKey, String name) {
		JsonNode created = http.postJson(libraries().build().toUri(), Map.of("Name", name), accessKey(accountApiKey));
		return requireLibrary(toLibrary(created));
	}

	private UriComponentsBuilder libraries() {
		return UriComponentsBuilder.fromUriString(properties.getBunny().getAccountApiBaseUrl() + "/videolibrary");
	}

	private static Consumer<HttpHeaders> accessKey(String accountApiKey) {
		return headers -> headers.set("AccessKey", accountApiKey);
	}

	private static BunnyLibrary requireLibrary(BunnyLibrary library) {
		if (library == null) {
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"Bunny.net returned an unexpected response.");
		}
		return library;
	}

	private static BunnyLibrary toLibrary(JsonNode node) {
		if (node == null || !node.path("Id").canConvertToLong()) {
			return null;
		}
		JsonNode pullZone = node.path("PullZoneId");
		return new BunnyLibrary(node.path("Id").asLong(), ProviderHttpClient.text(node, "Name"),
				pullZone.canConvertToLong() ? pullZone.asLong() : null);
	}

}

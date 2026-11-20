package com.lms.integrationmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.integrationmanagement.api.ManagedStreamPlaybackApi;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.Complete;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.ProcessingState;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.ProviderVideoStatus;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure.Kind;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadMetadata;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.media.DynTubeClient;
import com.lms.integrationmanagement.gateway.media.DynTubeTokenSigner;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.StoredCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DynTube as a managed streaming host (VIDEO Phase 13, ADR-026): whole-file
 * upload, status sync and tokenized HLS playback for the tenant's DynTube
 * account. Reached by the upload worker / status sync through {@link
 * VideoProviderUploadRouter} and by playback through {@link
 * ManagedStreamPlaybackApi}.
 *
 * <p>Every call uses the tenant's <b>current</b> ACTIVE DynTube connection
 * (resolved through the tenant filter): replacing the keys continues
 * earlier uploads on the new row, disconnecting stops uploads, syncs and
 * playback. The API key and token security key are opened only in memory
 * and never logged or returned.
 */
@Service
public class DynTubeStreamService implements ManagedStreamPlaybackApi {

	static final String SETTING_PROJECT_ID = "projectId";

	static final String SETTING_TOKEN_KEY_CONFIGURED = "tokenSecurityKeyConfigured";

	/** Videos looked up per sync call (DynTube's API is rate-limited; the rest stay due). */
	static final int MAX_STATUS_LOOKUPS = 10;

	private static final String RECONNECT = "Update the DynTube account in Settings → Integrations.";

	private final MediaProviderConnectionRepository repository;

	private final MediaConnectionStore store;

	private final MediaCredentialsCodec codec;

	private final DynTubeClient client;

	private final MediaIntegrationProperties properties;

	private final ObjectMapper objectMapper;

	public DynTubeStreamService(MediaProviderConnectionRepository repository, MediaConnectionStore store,
			MediaCredentialsCodec codec, DynTubeClient client, MediaIntegrationProperties properties,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.store = store;
		this.codec = codec;
		this.client = client;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	// ---------------------------------------------------------------- playback

	@Override
	public Optional<String> signHlsUrl(MediaProvider provider, String playbackKey, Instant expiresAt) {
		if (provider != MediaProvider.DYNTUBE || playbackKey == null
				|| !DynTubeClient.ID_PATTERN.matcher(playbackKey).matches()) {
			return Optional.empty();
		}
		return repository.findCurrentForTenant(MediaProvider.DYNTUBE)
			.filter(MediaProviderConnection::isActive)
			.map(codec::open)
			.map(StoredCredentials::tokenAuthKey)
			.filter(StringUtils::hasText)
			.map(securityKey -> DynTubeTokenSigner.hlsUrl(properties.getDyntube().getApiBaseUrl(), securityKey,
					playbackKey, expiresAt));
	}

	@Override
	public boolean hasActiveAccount(MediaProvider provider) {
		return provider == MediaProvider.DYNTUBE
				&& repository.findCurrentForTenant(MediaProvider.DYNTUBE).filter(MediaProviderConnection::isActive)
					.isPresent();
	}

	// ------------------------------------------------------------------ upload

	/** Whole-file upload of a staged object into the account's configured project. */
	Complete uploadWhole(UUID connectionId, UploadMetadata metadata, String contentType,
			Supplier<InputStream> content) {
		Usable usable = requireUsable(connectionId);
		String projectId = projectId(usable.connection())
			.orElseThrow(() -> new UploadFailure(Kind.CHANNEL_UNAVAILABLE,
					"Choose a DynTube project in Settings → Integrations."));
		try (InputStream in = content.get()) {
			String videoId = client.upload(usable.credentials().apiKey(), projectId, metadata.title(),
					metadata.description(), metadata.title(), contentType, in);
			return new Complete(videoId);
		}
		catch (MediaProviderException ex) {
			throw translate(usable.connection(), ex);
		}
		catch (IOException | UncheckedIOException ex) {
			throw new UploadFailure(Kind.RETRYABLE, "The staged file could not be read. It will be retried.");
		}
	}

	/**
	 * Processing state of up to {@link #MAX_STATUS_LOOKUPS} videos (one
	 * {@code GET /videos/{id}} each); the others are left out of the map and
	 * stay due. A published video only counts as READY once DynTube has
	 * given its playback key.
	 */
	Map<String, ProviderVideoStatus> fetchStatuses(UUID connectionId, Collection<String> videoIds) {
		Usable usable = requireUsable(connectionId);
		Map<String, ProviderVideoStatus> result = new LinkedHashMap<>();
		for (String videoId : videoIds.stream().distinct().limit(MAX_STATUS_LOOKUPS).toList()) {
			try {
				result.put(videoId, toStatus(client.getVideo(usable.credentials().apiKey(), videoId)));
			}
			catch (MediaProviderException ex) {
				if (ex.getKind() == MediaProviderException.Kind.NOT_FOUND) {
					result.put(videoId, new ProviderVideoStatus(videoId, ProcessingState.NOT_FOUND,
							"The video no longer exists on DynTube.", null, null));
					continue;
				}
				throw translate(usable.connection(), ex);
			}
		}
		return result;
	}

	static ProviderVideoStatus toStatus(DynTubeClient.Video video) {
		ProcessingState state = switch (video.state()) {
			case PUBLISHED -> (video.key() == null) ? ProcessingState.PROCESSING : ProcessingState.READY;
			case FAILED -> ProcessingState.FAILED;
			case DELETED -> ProcessingState.NOT_FOUND;
			case NOT_AVAILABLE, UPLOADED, PROCESSING, UNKNOWN -> ProcessingState.PROCESSING;
		};
		String reason = switch (state) {
			case FAILED -> "DynTube could not process this video.";
			case NOT_FOUND -> "The video was deleted on DynTube.";
			default -> null;
		};
		String key = (state == ProcessingState.READY) ? video.key() : null;
		String embedKey = (state == ProcessingState.READY) ? video.embedKey() : null;
		return new ProviderVideoStatus(video.id(), state, reason, video.durationSeconds(), video.thumbnailUrl(), key,
				embedKey);
	}

	/** Whether {@code connectionId} is a tenant DynTube row (any status) of the current tenant. */
	boolean isDynTube(UUID connectionId) {
		return repository.findById(connectionId)
			.filter(c -> c.getProvider() == MediaProvider.DYNTUBE && c.getScope() == ConnectionScope.TENANT)
			.isPresent();
	}

	Optional<String> projectId(MediaProviderConnection connection) {
		return setting(connection.getSettingsJson(), SETTING_PROJECT_ID)
			.filter(id -> DynTubeClient.ID_PATTERN.matcher(id).matches());
	}

	boolean tokenSecurityKeyConfigured(MediaProviderConnection connection) {
		return setting(connection.getSettingsJson(), SETTING_TOKEN_KEY_CONFIGURED).map(Boolean::parseBoolean)
			.orElse(false);
	}

	private Optional<String> setting(String settingsJson, String field) {
		if (!StringUtils.hasText(settingsJson)) {
			return Optional.empty();
		}
		try {
			JsonNode node = objectMapper.readTree(settingsJson).path(field);
			return node.isValueNode() ? Optional.ofNullable(node.asString()).filter(StringUtils::hasText)
					: Optional.empty();
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
	}

	private record Usable(MediaProviderConnection connection, StoredCredentials credentials) {

	}

	/**
	 * The connection an upload or sync started on must be a DynTube row of
	 * this tenant; the work continues on the tenant's current ACTIVE row.
	 */
	private Usable requireUsable(UUID connectionId) {
		MediaProviderConnection original;
		try {
			original = store.require(connectionId);
		}
		catch (NotFoundException ex) {
			throw new UploadFailure(Kind.CHANNEL_UNAVAILABLE, "The DynTube account is no longer connected.");
		}
		if (original.getProvider() != MediaProvider.DYNTUBE || original.getScope() != ConnectionScope.TENANT) {
			throw new UploadFailure(Kind.CHANNEL_UNAVAILABLE, "The DynTube account is no longer connected.");
		}
		MediaProviderConnection current = repository.findCurrentForTenant(MediaProvider.DYNTUBE)
			.filter(MediaProviderConnection::isActive)
			.orElseThrow(() -> new UploadFailure(Kind.AUTH, RECONNECT));
		return new Usable(current, codec.open(current));
	}

	private UploadFailure translate(MediaProviderConnection connection, MediaProviderException ex) {
		return switch (ex.getKind()) {
			case AUTH_REJECTED -> {
				store.markNeedsReconnect(connection.getId(), "DynTube rejected the API key.");
				yield new UploadFailure(Kind.AUTH, RECONNECT);
			}
			case NOT_FOUND -> new UploadFailure(Kind.CHANNEL_UNAVAILABLE,
					"The DynTube project was not found. Choose a project in Settings → Integrations.");
			case REJECTED -> new UploadFailure(Kind.FATAL, "DynTube refused this video file.");
			case UNAVAILABLE -> new UploadFailure(Kind.RETRYABLE,
					"DynTube is not reachable right now. It will be retried.");
		};
	}

}

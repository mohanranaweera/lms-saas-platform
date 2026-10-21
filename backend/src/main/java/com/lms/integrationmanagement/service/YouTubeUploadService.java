package com.lms.integrationmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContext;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.api.VideoProviderUploadApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure.Kind;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.gateway.media.ProviderHttpClient;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient;
import com.lms.integrationmanagement.gateway.media.YouTubeUploadHttp;
import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * {@link VideoProviderUploadApi} for the tenant's own YouTube channel (VIDEO
 * Phase 4, ADR-018 Amendment 2). Only a TENANT-scoped, ACTIVE YouTube
 * connection that a Platform Admin has not switched off (TD-008) is usable;
 * the connection is always read through the tenant filter of the job being
 * processed.
 *
 * <p>The resumable session URI is sealed with the media credentials key and
 * an AAD bound to the tenant, so a handle copied into another tenant's job
 * does not open. Deliberately NOT {@code @Transactional}: the provider calls
 * run with no transaction open (backend.md).
 */
@Service
public class YouTubeUploadService implements VideoProviderUploadApi {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	static final int MAX_STATUS_IDS = 50;

	private final MediaConnectionStore store;

	private final MediaTokenService tokenService;

	private final YouTubeUploadHttp uploadHttp;

	private final ProviderHttpClient providerHttp;

	private final MediaCredentialCipher cipher;

	private final MediaIntegrationProperties.OAuthProvider google;

	private final TenantContext tenantContext;

	public YouTubeUploadService(MediaConnectionStore store, MediaTokenService tokenService,
			YouTubeUploadHttp uploadHttp, ProviderHttpClient providerHttp, MediaCredentialCipher cipher,
			MediaIntegrationProperties properties, TenantContext tenantContext) {
		this.store = store;
		this.tokenService = tokenService;
		this.uploadHttp = uploadHttp;
		this.providerHttp = providerHttp;
		this.cipher = cipher;
		this.google = properties.getGoogle();
		this.tenantContext = tenantContext;
	}

	@Override
	public String startSession(UUID connectionId, UploadMetadata metadata, long totalBytes, String contentType) {
		requireUsable(connectionId);
		String sessionUri = withToken(connectionId,
				token -> uploadHttp.initiate(token, metadata, totalBytes, contentType));
		return cipher.encrypt(sessionUri, sessionAad());
	}

	@Override
	public ChunkResult uploadChunk(UUID connectionId, String sessionHandle, long offset, byte[] buffer, int length,
			long totalBytes) {
		requireUsable(connectionId);
		String sessionUri = openSession(sessionHandle);
		return withToken(connectionId,
				token -> uploadHttp.putChunk(token, sessionUri, offset, buffer, length, totalBytes));
	}

	@Override
	public ChunkResult queryProgress(UUID connectionId, String sessionHandle, long totalBytes) {
		requireUsable(connectionId);
		String sessionUri = openSession(sessionHandle);
		return withToken(connectionId, token -> uploadHttp.query(token, sessionUri, totalBytes));
	}

	@Override
	public void cancelSession(UUID connectionId, String sessionHandle) {
		try {
			String sessionUri = openSession(sessionHandle);
			uploadHttp.cancel(tokenService.validAccessToken(connectionId), sessionUri);
		}
		catch (RuntimeException ex) {
			// Best effort: an unfinished YouTube session expires on its own.
		}
	}

	@Override
	public void updateMetadata(UUID connectionId, String providerVideoId, UploadMetadata metadata) {
		MediaProviderConnection connection = requireTenantChannel(connectionId);
		if (connection.isPlatformDisabled()) {
			throw new UploadFailure(Kind.CHANNEL_UNAVAILABLE,
					"Video changes were switched off by the platform for this institute.");
		}
		if (!connection.isActive()) {
			throw new UploadFailure(Kind.AUTH, "Reconnect YouTube in Settings → Video Providers.");
		}
		if (!YouTubeOAuthClient.grantsEditScope(connection.getGrantedScopes())) {
			throw new UploadFailure(Kind.SCOPE_MISSING, "Reconnect YouTube to allow editing videos.");
		}
		withToken(connectionId, token -> {
			uploadHttp.updateVideo(token, providerVideoId, metadata);
			return null;
		});
	}

	@Override
	public Map<String, ProviderVideoStatus> fetchStatuses(UUID connectionId, Collection<String> providerVideoIds) {
		if (providerVideoIds.isEmpty()) {
			return Map.of();
		}
		if (providerVideoIds.size() > MAX_STATUS_IDS) {
			throw new IllegalArgumentException("At most " + MAX_STATUS_IDS + " video ids per call");
		}
		requireUsableForRead(connectionId);
		URI uri = UriComponentsBuilder.fromUriString(google.getApiBaseUrl() + "/youtube/v3/videos")
			.queryParam("part", "status,processingDetails,contentDetails,snippet")
			.queryParam("id", String.join(",", providerVideoIds))
			.queryParam("maxResults", MAX_STATUS_IDS)
			.encode()
			.build()
			.toUri();
		JsonNode json;
		try {
			json = providerHttp.get(uri, ProviderHttpClient.bearer(token(connectionId, false)));
		}
		catch (MediaProviderException ex) {
			if (ex.getKind() == MediaProviderException.Kind.AUTH_REJECTED) {
				tokenService.markRejected(connectionId, MediaProvider.YOUTUBE);
				throw new UploadFailure(Kind.AUTH, "YouTube no longer accepts this channel's authorization.");
			}
			throw new UploadFailure(Kind.RETRYABLE, "YouTube is temporarily unavailable. It will be retried.");
		}
		Map<String, ProviderVideoStatus> result = new HashMap<>();
		for (JsonNode item : json.path("items")) {
			String id = ProviderHttpClient.text(item, "id");
			if (id != null) {
				result.put(id, toStatus(id, item));
			}
		}
		for (String id : providerVideoIds) {
			result.putIfAbsent(id, new ProviderVideoStatus(id, ProcessingState.NOT_FOUND,
					"The video is no longer available on YouTube.", null, null));
		}
		return result;
	}

	static ProviderVideoStatus toStatus(String id, JsonNode item) {
		String uploadStatus = lower(ProviderHttpClient.text(item.path("status"), "uploadStatus"));
		String processing = lower(ProviderHttpClient.text(item.path("processingDetails"), "processingStatus"));
		Integer duration = durationSeconds(ProviderHttpClient.text(item.path("contentDetails"), "duration"));
		String thumbnail = thumbnail(item.path("snippet").path("thumbnails"));
		if ("processed".equals(uploadStatus)) {
			return new ProviderVideoStatus(id, ProcessingState.READY, null, duration, thumbnail);
		}
		if ("failed".equals(uploadStatus) || "failed".equals(processing) || "terminated".equals(processing)) {
			return new ProviderVideoStatus(id, ProcessingState.FAILED, "YouTube could not process this video.",
					duration, thumbnail);
		}
		if ("rejected".equals(uploadStatus)) {
			return new ProviderVideoStatus(id, ProcessingState.FAILED, "YouTube rejected this video.", duration,
					thumbnail);
		}
		if ("deleted".equals(uploadStatus)) {
			return new ProviderVideoStatus(id, ProcessingState.FAILED, "The video was deleted on YouTube.", duration,
					thumbnail);
		}
		return new ProviderVideoStatus(id, ProcessingState.PROCESSING, null, duration, thumbnail);
	}

	/** ISO-8601 duration ({@code PT1H2M3S}) in whole seconds, or {@code null}. */
	static Integer durationSeconds(String isoDuration) {
		if (isoDuration == null) {
			return null;
		}
		try {
			long seconds = Duration.parse(isoDuration).getSeconds();
			return (seconds <= 0 || seconds > Integer.MAX_VALUE) ? null : (int) seconds;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static String thumbnail(JsonNode thumbnails) {
		for (String size : List.of("high", "medium", "default")) {
			String url = ProviderHttpClient.text(thumbnails.path(size), "url");
			if (url != null && url.startsWith("https://")) {
				return url;
			}
		}
		return null;
	}

	private static String lower(String value) {
		return (value == null) ? null : value.toLowerCase(Locale.ROOT);
	}

	/** Runs {@code call} with a valid token; on a 401, refreshes once and retries. */
	private <T> T withToken(UUID connectionId, Function<String, T> call) {
		try {
			return call.apply(token(connectionId, false));
		}
		catch (UploadFailure failure) {
			if (failure.getKind() != Kind.AUTH) {
				throw failure;
			}
		}
		try {
			return call.apply(token(connectionId, true));
		}
		catch (UploadFailure failure) {
			if (failure.getKind() == Kind.AUTH) {
				tokenService.markRejected(connectionId, MediaProvider.YOUTUBE);
				securityLog.atWarn()
					.setMessage("video_upload.authorization_rejected")
					.addKeyValue("tenantId", tenantContext.getTenantId())
					.addKeyValue("connectionId", connectionId)
					.log();
			}
			throw failure;
		}
	}

	private String token(UUID connectionId, boolean force) {
		try {
			return force ? tokenService.refreshedAccessToken(connectionId)
					: tokenService.validAccessToken(connectionId);
		}
		catch (ProviderConflictException ex) {
			throw new UploadFailure(Kind.AUTH, "Reconnect YouTube in Settings → Video Providers.");
		}
		catch (MediaProviderException ex) {
			if (ex.getKind() == MediaProviderException.Kind.AUTH_REJECTED) {
				throw new UploadFailure(Kind.AUTH, "Reconnect YouTube in Settings → Video Providers.");
			}
			throw new UploadFailure(Kind.RETRYABLE, "YouTube is temporarily unavailable. It will be retried.");
		}
		catch (ServiceUnavailableException ex) {
			throw new UploadFailure(Kind.RETRYABLE, "Uploads are temporarily unavailable. It will be retried.");
		}
	}

	/** Upload calls: an ACTIVE tenant YouTube channel the platform has not switched off. */
	private void requireUsable(UUID connectionId) {
		MediaProviderConnection connection = requireTenantChannel(connectionId);
		if (connection.isPlatformDisabled()) {
			throw new UploadFailure(Kind.CHANNEL_UNAVAILABLE,
					"Video uploads were switched off by the platform for this institute.");
		}
		if (!connection.isActive()) {
			throw new UploadFailure(Kind.AUTH, "Reconnect YouTube in Settings → Video Providers.");
		}
	}

	/**
	 * Status reads only need a live grant: a platform switch-off stops new
	 * uploads, not the processing status of videos already on YouTube.
	 */
	private void requireUsableForRead(UUID connectionId) {
		if (!requireTenantChannel(connectionId).isActive()) {
			throw new UploadFailure(Kind.AUTH, "Reconnect YouTube in Settings → Video Providers.");
		}
	}

	private MediaProviderConnection requireTenantChannel(UUID connectionId) {
		MediaProviderConnection connection;
		try {
			connection = store.require(connectionId);
		}
		catch (NotFoundException ex) {
			throw new UploadFailure(Kind.CHANNEL_UNAVAILABLE, "The YouTube channel is no longer connected.");
		}
		if (connection.getProvider() != MediaProvider.YOUTUBE || connection.getScope() != ConnectionScope.TENANT) {
			throw new UploadFailure(Kind.CHANNEL_UNAVAILABLE, "The YouTube channel is no longer connected.");
		}
		return connection;
	}

	private String openSession(String sessionHandle) {
		try {
			return cipher.decrypt(sessionHandle, sessionAad());
		}
		catch (ServiceUnavailableException ex) {
			throw new UploadFailure(Kind.RETRYABLE, "Uploads are temporarily unavailable. It will be retried.");
		}
		catch (RuntimeException ex) {
			// Wrong tenant, tampered or unreadable handle: never use it; start over.
			securityLog.atWarn()
				.setMessage("video_upload.session_handle_rejected")
				.log();
			throw new UploadFailure(Kind.SESSION_GONE, "The YouTube upload session is invalid. It will be restarted.");
		}
	}

	private String sessionAad() {
		return "video-upload-session:" + tenantContext.getTenantId() + ":" + MediaProvider.YOUTUBE.name();
	}

}

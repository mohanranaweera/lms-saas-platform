package com.lms.integrationmanagement.api;

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Uploading to, and reading processing status from, a tenant's video
 * provider channel (VIDEO Phase 4 - today YouTube's resumable upload
 * protocol). Used only by the background upload worker and status sync,
 * never on a request thread (VID-ADR-004).
 *
 * <p>Every call runs in the tenant context of the job being processed; the
 * connection id is resolved through the tenant filter, so a connection of
 * another tenant is never usable. Tokens are refreshed here (once on a 401);
 * a revoked grant marks the connection NEEDS_RECONNECT.
 *
 * <p>The resumable session URI is a bearer capability: callers only ever see
 * it as an opaque, AES-GCM sealed {@code sessionHandle} bound to the tenant.
 * It is never returned by an API or logged.
 */
public interface VideoProviderUploadApi {

	/** Opens a resumable upload session; returns the sealed session handle. */
	String startSession(UUID connectionId, UploadMetadata metadata, long totalBytes, String contentType);

	/** Sends bytes {@code [offset, offset + length)} of the file. */
	ChunkResult uploadChunk(UUID connectionId, String sessionHandle, long offset, byte[] buffer, int length,
			long totalBytes);

	/** Asks the provider how much of the file it already has (resume after an interruption). */
	ChunkResult queryProgress(UUID connectionId, String sessionHandle, long totalBytes);

	/** Best-effort cancellation of an unfinished session; never throws. */
	void cancelSession(UUID connectionId, String sessionHandle);

	/**
	 * VIDEO Phase 13: whether the connection's provider takes uploads in
	 * resumable chunks ({@link #startSession}/{@link #uploadChunk}) or only
	 * as one whole-file request ({@link #uploadWhole}).
	 */
	default boolean supportsResumableUpload(UUID connectionId) {
		return true;
	}

	/**
	 * VIDEO Phase 13 (ADR-026): sends the whole file in one streamed request
	 * - for providers without resumable uploads (DynTube). {@code content}
	 * opens a fresh stream over the staged object for each attempt and is
	 * read once, without buffering the file in memory.
	 *
	 * @throws UploadFailure on any provider failure; a retry sends the whole
	 * file again
	 */
	default Complete uploadWhole(UUID connectionId, UploadMetadata metadata, long totalBytes, String contentType,
			Supplier<InputStream> content) {
		throw new UploadFailure(UploadFailure.Kind.FATAL, "This channel does not support whole-file uploads.");
	}

	/**
	 * VIDEO Phase 5: changes the video's title / description / privacy on the
	 * provider (ADR-018 Amendment 3). Other provider-side settings are kept.
	 *
	 * @throws UploadFailure {@code SCOPE_MISSING} when the channel was connected
	 * without the edit scope, {@code FATAL} when the video no longer exists
	 */
	void updateMetadata(UUID connectionId, String providerVideoId, UploadMetadata metadata);

	/** Processing state of up to 50 provider video ids, keyed by id (unknown ids map to NOT_FOUND). */
	Map<String, ProviderVideoStatus> fetchStatuses(UUID connectionId, Collection<String> providerVideoIds);

	/** @param privacyStatus {@code PRIVATE} or {@code UNLISTED} */
	record UploadMetadata(String title, String description, String privacyStatus) {

	}

	/** The outcome of a chunk upload or a progress query. */
	sealed interface ChunkResult permits Progress, Complete {

	}

	/** The provider has bytes {@code [0, nextOffset)}; continue from {@code nextOffset}. */
	record Progress(long nextOffset) implements ChunkResult {

	}

	/** The provider has the whole file and assigned it this id. */
	record Complete(String providerVideoId) implements ChunkResult {

	}

	/**
	 * @param playbackKey VIDEO Phase 13: the provider's key used to sign
	 * playback URLs (DynTube {@code key}); {@code null} for providers that
	 * play through their own player (YouTube). Never returned by an API.
	 * @param embedKey VIDEO Phase 13c: the provider's player embed key
	 * (DynTube {@code channelKey}, ADR-026 Amendment 1); {@code null} otherwise
	 */
	record ProviderVideoStatus(String providerVideoId, ProcessingState state, String failureReason,
			Integer durationSeconds, String thumbnailUrl, String playbackKey, String embedKey) {

		public ProviderVideoStatus(String providerVideoId, ProcessingState state, String failureReason,
				Integer durationSeconds, String thumbnailUrl, String playbackKey) {
			this(providerVideoId, state, failureReason, durationSeconds, thumbnailUrl, playbackKey, null);
		}

		public ProviderVideoStatus(String providerVideoId, ProcessingState state, String failureReason,
				Integer durationSeconds, String thumbnailUrl) {
			this(providerVideoId, state, failureReason, durationSeconds, thumbnailUrl, null, null);
		}

		@Override
		public String toString() {
			return "ProviderVideoStatus[providerVideoId=" + providerVideoId + ", state=" + state + "]";
		}

	}

	enum ProcessingState {

		PROCESSING, READY, FAILED, NOT_FOUND

	}

	/**
	 * A provider call failed. {@link #getMessage()} is client-safe (it may be
	 * shown as the asset's failure reason); provider bodies are never included.
	 */
	final class UploadFailure extends RuntimeException {

		/** How the upload worker must react. */
		public enum Kind {

			/** Transient (5xx, timeout, rate limit) - back off and retry. */
			RETRYABLE,
			/** The provider's upload quota is exhausted - pause until it resets. */
			QUOTA,
			/** The grant was revoked/expired - park until the tenant reconnects. */
			AUTH,
			/** The channel is switched off by the platform or not usable - park. */
			CHANNEL_UNAVAILABLE,
			/** The resumable session is gone - start a new session. */
			SESSION_GONE,
			/** The provider refused the upload itself - retrying will not help. */
			FATAL,
			/** The channel's grant lacks a needed scope (e.g. editing before Amendment 3) - reconnect. */
			SCOPE_MISSING

		}

		private final Kind kind;

		public UploadFailure(Kind kind, String message) {
			super(message);
			this.kind = kind;
		}

		public Kind getKind() {
			return kind;
		}

	}

}

package com.lms.integrationmanagement.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

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

	record ProviderVideoStatus(String providerVideoId, ProcessingState state, String failureReason,
			Integer durationSeconds, String thumbnailUrl) {

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
			FATAL

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

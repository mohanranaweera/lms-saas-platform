package com.lms.integrationmanagement.service;

import com.lms.integrationmanagement.api.VideoProviderUploadApi;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * The {@link VideoProviderUploadApi} other modules get (VIDEO Phase 13):
 * routes each call by the connection's provider - DynTube connections to
 * {@link DynTubeStreamService} (whole-file uploads), everything else to
 * {@link YouTubeUploadService} unchanged.
 */
@Primary
@Service
public class VideoProviderUploadRouter implements VideoProviderUploadApi {

	private final YouTubeUploadService youTube;

	private final DynTubeStreamService dynTube;

	public VideoProviderUploadRouter(YouTubeUploadService youTube, DynTubeStreamService dynTube) {
		this.youTube = youTube;
		this.dynTube = dynTube;
	}

	@Override
	public boolean supportsResumableUpload(UUID connectionId) {
		return !dynTube.isDynTube(connectionId);
	}

	@Override
	public Complete uploadWhole(UUID connectionId, UploadMetadata metadata, long totalBytes, String contentType,
			Supplier<InputStream> content) {
		if (!dynTube.isDynTube(connectionId)) {
			throw new UploadFailure(UploadFailure.Kind.FATAL, "This channel takes resumable uploads only.");
		}
		return dynTube.uploadWhole(connectionId, metadata, contentType, content);
	}

	@Override
	public String startSession(UUID connectionId, UploadMetadata metadata, long totalBytes, String contentType) {
		requireResumable(connectionId);
		return youTube.startSession(connectionId, metadata, totalBytes, contentType);
	}

	@Override
	public ChunkResult uploadChunk(UUID connectionId, String sessionHandle, long offset, byte[] buffer, int length,
			long totalBytes) {
		requireResumable(connectionId);
		return youTube.uploadChunk(connectionId, sessionHandle, offset, buffer, length, totalBytes);
	}

	@Override
	public ChunkResult queryProgress(UUID connectionId, String sessionHandle, long totalBytes) {
		requireResumable(connectionId);
		return youTube.queryProgress(connectionId, sessionHandle, totalBytes);
	}

	@Override
	public void cancelSession(UUID connectionId, String sessionHandle) {
		if (!dynTube.isDynTube(connectionId)) {
			youTube.cancelSession(connectionId, sessionHandle);
		}
	}

	/** DynTube titles/descriptions are edited in the LMS only (ADR-026 Decision 5). */
	@Override
	public void updateMetadata(UUID connectionId, String providerVideoId, UploadMetadata metadata) {
		if (!dynTube.isDynTube(connectionId)) {
			youTube.updateMetadata(connectionId, providerVideoId, metadata);
		}
	}

	@Override
	public Map<String, ProviderVideoStatus> fetchStatuses(UUID connectionId, Collection<String> providerVideoIds) {
		if (dynTube.isDynTube(connectionId)) {
			return dynTube.fetchStatuses(connectionId, providerVideoIds);
		}
		return youTube.fetchStatuses(connectionId, providerVideoIds);
	}

	private void requireResumable(UUID connectionId) {
		if (dynTube.isDynTube(connectionId)) {
			throw new UploadFailure(UploadFailure.Kind.FATAL, "DynTube takes whole-file uploads only.");
		}
	}

}

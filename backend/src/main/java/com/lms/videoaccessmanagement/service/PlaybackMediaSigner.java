package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ConflictException;
import com.lms.common.error.ProviderConflictException;
import com.lms.integrationmanagement.api.ManagedStreamPlaybackApi;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.videoaccessmanagement.config.PlayerProperties;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The short-lived media URL our own players load, for every asset that is
 * not played by its provider's player (VIDEO Phase 13): platform storage
 * (INTERNAL) gets a presigned object URL ({@link MediaFormat#FILE}). DynTube
 * plays in DynTube's own player through our bridge page ({@link
 * MediaFormat#EMBED}, ADR-026 Amendment 1): DynTube serves its AES keys only to
 * its own player, so the tokenized HLS playlist can't be decrypted by ours.
 * No provider is called on the play path.
 *
 * <p>Callers have already done the playability, entitlement, device and
 * watch-time checks; this only decides <i>how</i> to sign. A managed-stream
 * asset is refused while {@code video.features.managed-stream-playback} is
 * off or the institute's account cannot sign.
 */
@Component
public class PlaybackMediaSigner {

	/** How the player must load the URL. */
	public enum MediaFormat {

		/** A single progressive file (HTML5 {@code <video src>}). */
		FILE,
		/** An HLS master playlist (hls.js / native HLS). */
		HLS,
		/** The host's own player inside our bridge page; no URL, see {@link SignedMedia#embed}. */
		EMBED

	}

	/** @param embed only for {@link MediaFormat#EMBED}, where {@code url} is {@code null} */
	public record SignedMedia(String url, Instant expiresAt, MediaFormat format, PlaybackEmbed embed) {

		public SignedMedia(String url, Instant expiresAt, MediaFormat format) {
			this(url, expiresAt, format, null);
		}

		@Override
		public String toString() {
			return "SignedMedia[format=" + format + ", expiresAt=" + expiresAt + "]";
		}

	}

	private final ObjectStorageApi objectStorageApi;

	private final ManagedStreamPlaybackApi managedStream;

	private final VideoFeatureFlags featureFlags;

	private final PlayerProperties playerProperties;

	private final Clock clock;

	public PlaybackMediaSigner(ObjectStorageApi objectStorageApi, ManagedStreamPlaybackApi managedStream,
			VideoFeatureFlags featureFlags, PlayerProperties playerProperties, Clock clock) {
		this.objectStorageApi = objectStorageApi;
		this.managedStream = managedStream;
		this.featureFlags = featureFlags;
		this.playerProperties = playerProperties;
		this.clock = clock;
	}

	/** Whether this asset plays from a URL we sign (as opposed to a provider player such as YouTube). */
	public static boolean signsFor(VideoAsset asset) {
		return switch (asset.getProvider()) {
			case INTERNAL, DYNTUBE -> true;
			case YOUTUBE -> false;
		};
	}

	/** @throws ConflictException for a provider played by its own player */
	public SignedMedia sign(VideoAsset asset, Duration ttl) {
		return switch (asset.getProvider()) {
			case INTERNAL -> {
				SignedDownloadUrl signed = objectStorageApi.generateSignedDownloadUrl(asset.getStorageObjectKey(), ttl);
				yield new SignedMedia(signed.url(), signed.expiresAt(), MediaFormat.FILE);
			}
			case DYNTUBE -> embedDynTube(asset, ttl);
			case YOUTUBE -> throw new ConflictException("This video plays through the lesson player.");
		};
	}

	private SignedMedia embedDynTube(VideoAsset asset, Duration ttl) {
		if (!featureFlags.isPlaybackModeEnabled(PlaybackMode.MANAGED_STREAM)
				|| !managedStream.hasActiveAccount(MediaProvider.DYNTUBE)) {
			throw unavailable();
		}
		String key = asset.getProviderEmbedKey();
		String bridgeUrl = playerProperties.getDyntubeBridgeUrl();
		// The embed key arrives with the next status sync (V83); until then the video can't play.
		if (key == null || bridgeUrl == null || !bridgeUrl.matches("^https?://\\S+$")) {
			throw unavailable();
		}
		return new SignedMedia(null, clock.instant().plus(ttl), MediaFormat.EMBED,
				new PlaybackEmbed(MediaProvider.DYNTUBE.name(), key, bridgeUrl));
	}

	private static ProviderConflictException unavailable() {
		return new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_NOT_CONNECTED,
				"This video can't be played right now. Please try again later.");
	}

}

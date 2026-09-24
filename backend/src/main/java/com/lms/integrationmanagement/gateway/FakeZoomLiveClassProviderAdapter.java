package com.lms.integrationmanagement.gateway;

import com.lms.integrationmanagement.api.LiveClassProviderApi;
import com.lms.integrationmanagement.api.MeetingCreationResult;
import com.lms.integrationmanagement.api.ShortLivedJoinLink;
import com.lms.integrationmanagement.api.ShortLivedPlaybackLink;
import com.lms.integrationmanagement.config.LiveClassProviderProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic, in-process placeholder implementation of {@link
 * LiveClassProviderApi} - no real network call, no Zoom SDK, no real Zoom
 * credentials anywhere (none exist in this environment - root {@code
 * CLAUDE.md} Safety: never fabricate real secrets), mirroring {@link
 * FakePaymentGatewayAdapter}'s exact structure. {@link #createMeeting} makes
 * no outbound call at all; it exists so {@code live-class-management}'s
 * two-phase transaction-boundary shape (persist PENDING, call the provider
 * outside any open transaction, persist the returned reference/failure) is
 * exercised end-to-end in tests even with nothing real to call.
 *
 * <p>The signature-verification half ({@link #verifySignature}) is real -
 * see {@link WebhookSignatureVerifier}'s javadoc - reusing the identical
 * HMAC-SHA256 mechanism {@code payment-management}'s webhook already relies
 * on, keyed by a distinct {@link LiveClassProviderProperties#getWebhookSecret()}.
 */
@Component
public class FakeZoomLiveClassProviderAdapter implements LiveClassProviderApi {

	private static final String REFERENCE_PREFIX = "FAKE-ZOOM-";

	/** Matches {@code MaterialDownloadUrlResponse}'s/{@code SignedDownloadUrl}'s short-lived-link convention. */
	private static final long LINK_TTL_MINUTES = 10;

	private final LiveClassProviderProperties properties;

	public FakeZoomLiveClassProviderAdapter(LiveClassProviderProperties properties) {
		this.properties = properties;
	}

	@Override
	public MeetingCreationResult createMeeting(UUID tenantId, UUID sessionId, String title, Instant scheduledStart,
			Instant scheduledEnd) {
		String reference = REFERENCE_PREFIX + UUID.randomUUID();
		return new MeetingCreationResult(reference);
	}

	@Override
	public ShortLivedJoinLink getJoinUrl(String providerReference, UUID tenantId, String participantRole,
			String displayName) {
		String token = UUID.randomUUID().toString();
		String url = "https://live-class-provider.test/join/" + token;
		return new ShortLivedJoinLink(url, Instant.now().plus(LINK_TTL_MINUTES, ChronoUnit.MINUTES));
	}

	@Override
	public ShortLivedPlaybackLink getRecordingPlaybackUrl(String providerRecordingReference, UUID tenantId) {
		String token = UUID.randomUUID().toString();
		String url = "https://live-class-provider.test/recordings/" + token;
		return new ShortLivedPlaybackLink(url, Instant.now().plus(LINK_TTL_MINUTES, ChronoUnit.MINUTES));
	}

	@Override
	public void cancelMeeting(String providerReference) {
		// No-op: no real network call exists to make (see class javadoc). A
		// real adapter would call the provider's delete-meeting endpoint here,
		// idempotently.
	}

	@Override
	public boolean verifySignature(String rawBody, String signatureHeader) {
		return WebhookSignatureVerifier.verify(rawBody, signatureHeader, properties.getWebhookSecret());
	}

}

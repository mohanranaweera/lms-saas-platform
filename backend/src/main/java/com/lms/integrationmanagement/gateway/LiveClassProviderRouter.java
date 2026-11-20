package com.lms.integrationmanagement.gateway;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.LiveClassProviderApi;
import com.lms.integrationmanagement.api.MeetingCreationResult;
import com.lms.integrationmanagement.api.MeetingParticipant;
import com.lms.integrationmanagement.api.ShortLivedJoinLink;
import com.lms.integrationmanagement.api.ShortLivedPlaybackLink;
import com.lms.integrationmanagement.config.LiveClassProviderProperties;
import com.lms.integrationmanagement.gateway.media.ZoomOAuthClient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link LiveClassProviderApi} other domains get injected (MEDIA-INT-01).
 *
 * <ul>
 * <li>Platform Zoom OAuth app configured: new meetings are real Zoom
 * meetings on the teacher's own account ({@link
 * ZoomLiveClassProviderAdapter}).</li>
 * <li>Not configured, and {@code live-class.provider.fake-fallback-enabled}
 * (local/test only): the fake provider.</li>
 * <li>Otherwise live classes are unavailable (503) - never silently fake.</li>
 * </ul>
 *
 * Existing sessions route by what hosted them: a session with a
 * {@code connectionId} is a real Zoom meeting; one without is a fake-provider
 * meeting.
 */
@Primary
@Component
public class LiveClassProviderRouter implements LiveClassProviderApi {

	private final ZoomOAuthClient zoomOAuthClient;

	private final ZoomLiveClassProviderAdapter zoom;

	private final FakeZoomLiveClassProviderAdapter fake;

	private final LiveClassProviderProperties properties;

	public LiveClassProviderRouter(ZoomOAuthClient zoomOAuthClient, ZoomLiveClassProviderAdapter zoom,
			FakeZoomLiveClassProviderAdapter fake, LiveClassProviderProperties properties) {
		this.zoomOAuthClient = zoomOAuthClient;
		this.zoom = zoom;
		this.fake = fake;
		this.properties = properties;
	}

	@Override
	public void checkHostReady(UUID hostTeacherUserId) {
		if (zoomOAuthClient.isConfigured()) {
			zoom.checkHostReady(hostTeacherUserId);
			return;
		}
		requireFakeFallback();
	}

	@Override
	public MeetingCreationResult createMeeting(UUID tenantId, UUID sessionId, UUID hostTeacherUserId, String title,
			Instant scheduledStart, Instant scheduledEnd) {
		if (zoomOAuthClient.isConfigured()) {
			return zoom.createMeeting(hostTeacherUserId, title, scheduledStart, scheduledEnd);
		}
		requireFakeFallback();
		return fake.createMeeting(tenantId, sessionId, hostTeacherUserId, title, scheduledStart, scheduledEnd);
	}

	@Override
	public ShortLivedJoinLink getJoinUrl(UUID connectionId, String providerReference, MeetingParticipant participant) {
		if (connectionId != null) {
			return zoom.getJoinUrl(connectionId, providerReference, participant);
		}
		if (!properties.isFakeFallbackEnabled()) {
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_NOT_CONNECTED,
					"This class has no connected meeting host. Recreate the meeting.");
		}
		return fake.getJoinUrl(null, providerReference, participant);
	}

	@Override
	public ShortLivedPlaybackLink getRecordingPlaybackUrl(String providerRecordingReference, UUID tenantId) {
		return fake.getRecordingPlaybackUrl(providerRecordingReference, tenantId);
	}

	@Override
	public void cancelMeeting(UUID connectionId, String providerReference) {
		if (connectionId != null) {
			zoom.cancelMeeting(connectionId, providerReference);
			return;
		}
		fake.cancelMeeting(null, providerReference);
	}

	@Override
	public boolean verifySignature(String rawBody, String signatureHeader) {
		return fake.verifySignature(rawBody, signatureHeader);
	}

	private void requireFakeFallback() {
		if (!properties.isFakeFallbackEnabled()) {
			throw new ServiceUnavailableException("Live classes are not available: Zoom is not configured.");
		}
	}

}

package com.lms.integrationmanagement.gateway;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.MediaConnectionApi.TeacherConnectionSummary;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.api.MeetingCreationResult;
import com.lms.integrationmanagement.api.MeetingParticipant;
import com.lms.integrationmanagement.api.ShortLivedJoinLink;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.gateway.media.ZoomMeetingClient;
import com.lms.integrationmanagement.gateway.media.ZoomOAuthClient;
import com.lms.integrationmanagement.service.MediaTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real Zoom live classes on the session teacher's OWN Zoom account
 * (MEDIA-INT-01 / ADR-018 §4). Reached only through {@link
 * LiveClassProviderRouter}; every call runs with no DB transaction open.
 *
 * <ul>
 * <li>Scheduling requires Zoom to be enabled for the tenant and the teacher
 * to have an ACTIVE, <b>licensed</b> Zoom connection (licensed hosts are
 * required for meeting registration).</li>
 * <li>The owning teacher gets a freshly fetched host start URL; everyone
 * else is registered and gets a personal join URL. Neither is stored.</li>
 * <li>A 401 from Zoom means the teacher revoked our access: the connection is
 * marked NEEDS_RECONNECT.</li>
 * </ul>
 */
@Component
public class ZoomLiveClassProviderAdapter {

	private static final Logger log = LoggerFactory.getLogger(ZoomLiveClassProviderAdapter.class);

	/** Zoom documents a ~2 hour lifetime for a host start URL; used for both link kinds (advisory). */
	private static final Duration LINK_LIFETIME = Duration.ofHours(2);

	private final MediaConnectionApi connectionApi;

	private final MediaTokenService tokenService;

	private final ZoomMeetingClient meetingClient;

	public ZoomLiveClassProviderAdapter(MediaConnectionApi connectionApi, MediaTokenService tokenService,
			ZoomMeetingClient meetingClient) {
		this.connectionApi = connectionApi;
		this.tokenService = tokenService;
		this.meetingClient = meetingClient;
	}

	/** @throws ProviderConflictException with the specific PROVIDER_* code when the teacher cannot host */
	public TeacherConnectionSummary checkHostReady(UUID hostTeacherUserId) {
		if (!connectionApi.isTeacherProviderEnabled(MediaProvider.ZOOM)) {
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_DISABLED_BY_TENANT,
					"Zoom is not enabled for your institute.");
		}
		TeacherConnectionSummary connection = connectionApi.findTeacherConnection(hostTeacherUserId, MediaProvider.ZOOM)
			.orElseThrow(() -> new ProviderConflictException(ApiErrorCodes.PROVIDER_NOT_CONNECTED,
					"The teacher has not connected a Zoom account. Connect it in Settings > Integrations."));
		if (connection.status() != MediaConnectionStatus.ACTIVE) {
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_RECONNECT_REQUIRED,
					"The teacher's Zoom account needs to be reconnected.");
		}
		if (!ZoomOAuthClient.ACCOUNT_TYPE_LICENSED.equals(connection.accountType())) {
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_LICENSE_REQUIRED,
					"A licensed (paid) Zoom account is required to host live classes.");
		}
		return connection;
	}

	public MeetingCreationResult createMeeting(UUID hostTeacherUserId, String title, Instant scheduledStart,
			Instant scheduledEnd) {
		TeacherConnectionSummary connection = checkHostReady(hostTeacherUserId);
		String accessToken = tokenService.validAccessToken(connection.connectionId());
		try {
			String meetingId = meetingClient.createMeeting(accessToken, title, scheduledStart, scheduledEnd);
			return new MeetingCreationResult(meetingId, connection.connectionId());
		}
		catch (MediaProviderException ex) {
			throw translate(ex, connection.connectionId());
		}
	}

	public ShortLivedJoinLink getJoinUrl(UUID connectionId, String meetingId, MeetingParticipant participant) {
		MediaConnectionStatus status = connectionApi.connectionStatuses(List.of(connectionId)).get(connectionId);
		if (status != MediaConnectionStatus.ACTIVE) {
			throw new ProviderConflictException(ApiErrorCodes.PROVIDER_NOT_CONNECTED,
					"The teacher's Zoom account is disconnected.");
		}
		String accessToken = tokenService.validAccessToken(connectionId);
		try {
			String url = participant.host() ? meetingClient.fetchStartUrl(accessToken, meetingId)
					: meetingClient.addRegistrant(accessToken, meetingId, participant.email(), participant.firstName(),
							participant.lastName());
			return new ShortLivedJoinLink(url, Instant.now().plus(LINK_LIFETIME));
		}
		catch (MediaProviderException ex) {
			throw translate(ex, connectionId);
		}
	}

	/** Best-effort: never throws. */
	public void cancelMeeting(UUID connectionId, String meetingId) {
		try {
			MediaConnectionStatus status = connectionApi.connectionStatuses(List.of(connectionId)).get(connectionId);
			if (status != MediaConnectionStatus.ACTIVE) {
				return;
			}
			meetingClient.deleteMeeting(tokenService.validAccessToken(connectionId), meetingId);
		}
		catch (RuntimeException ex) {
			log.atInfo()
				.setMessage("live_class.zoom_cancel_failed")
				.addKeyValue("connectionId", connectionId)
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
		}
	}

	private RuntimeException translate(MediaProviderException ex, UUID connectionId) {
		return switch (ex.getKind()) {
			case AUTH_REJECTED -> tokenService.markRejected(connectionId, MediaProvider.ZOOM);
			case NOT_FOUND -> new ProviderConflictException(ApiErrorCodes.PROVIDER_NOT_CONNECTED,
					"The Zoom meeting no longer exists. Recreate the meeting.");
			case REJECTED -> new ProviderConflictException(ApiErrorCodes.CONFLICT, "Zoom refused the request.");
			case UNAVAILABLE -> new ServiceUnavailableException(ex.getMessage());
		};
	}

}

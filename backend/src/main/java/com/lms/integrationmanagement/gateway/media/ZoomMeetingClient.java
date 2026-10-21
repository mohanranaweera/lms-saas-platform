package com.lms.integrationmanagement.gateway.media;

import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Zoom Meetings API calls made on a teacher's own account (ADR-018 §4),
 * always with that teacher's (already refreshed) access token.
 *
 * <p>Meetings are created with registration required and automatic
 * approval, so every student gets a personal join link ({@link
 * #addRegistrant}); Zoom's own registrant emails are switched off - the
 * platform hands out the link only after its own entitlement checks. The
 * host's {@code start_url} is fetched fresh on demand and never stored.
 */
@Component
public class ZoomMeetingClient {

	/** Zoom meeting type 2 = scheduled meeting (required for registration). */
	private static final int SCHEDULED_MEETING = 2;

	private final MediaIntegrationProperties.OAuthProvider config;

	private final ProviderHttpClient http;

	public ZoomMeetingClient(MediaIntegrationProperties properties, ProviderHttpClient http) {
		this.config = properties.getZoom();
		this.http = http;
	}

	/** @return the Zoom meeting id (the opaque {@code class_session.provider_reference}) */
	public String createMeeting(String accessToken, String topic, Instant start, Instant end) {
		long minutes = Math.max(1, Duration.between(start, end).toMinutes());
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("approval_type", 0);
		settings.put("registration_type", 1);
		settings.put("registrants_email_notification", false);
		settings.put("registrants_confirmation_email", false);
		settings.put("join_before_host", false);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", truncate(topic, 200));
		body.put("type", SCHEDULED_MEETING);
		body.put("start_time", DateTimeFormatter.ISO_INSTANT.format(start.truncatedTo(ChronoUnit.SECONDS)));
		body.put("duration", minutes);
		body.put("timezone", "UTC");
		body.put("settings", settings);
		JsonNode created = http.postJson(api("/users/me/meetings"), body, ProviderHttpClient.bearer(accessToken));
		String id = ProviderHttpClient.text(created, "id");
		if (id == null) {
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"Zoom returned an unexpected response.");
		}
		return id;
	}

	/** Fresh host start URL (Zoom documents a ~2 hour lifetime). */
	public String fetchStartUrl(String accessToken, String meetingId) {
		JsonNode meeting = http.get(api("/meetings/{id}", meetingId), ProviderHttpClient.bearer(accessToken));
		return requireUrl(ProviderHttpClient.text(meeting, "start_url"));
	}

	/**
	 * Registers one attendee and returns their personal join URL. Zoom
	 * returns the existing registration for an email that is already
	 * registered, so repeated joins are idempotent.
	 */
	public String addRegistrant(String accessToken, String meetingId, String email, String firstName,
			String lastName) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("first_name", truncate(firstName, 64));
		body.put("last_name", truncate(lastName, 64));
		JsonNode registrant = http.postJson(api("/meetings/{id}/registrants", meetingId), body,
				ProviderHttpClient.bearer(accessToken));
		return requireUrl(ProviderHttpClient.text(registrant, "join_url"));
	}

	/** Idempotent: an already-deleted meeting is treated as success. */
	public void deleteMeeting(String accessToken, String meetingId) {
		try {
			http.delete(api("/meetings/{id}", meetingId), ProviderHttpClient.bearer(accessToken));
		}
		catch (MediaProviderException ex) {
			if (ex.getKind() != MediaProviderException.Kind.NOT_FOUND) {
				throw ex;
			}
		}
	}

	private URI api(String path, Object... vars) {
		return UriComponentsBuilder.fromUriString(config.getApiBaseUrl() + path).encode().buildAndExpand(vars).toUri();
	}

	private static String requireUrl(String url) {
		if (url == null || !url.startsWith("https://")) {
			throw new MediaProviderException(MediaProviderException.Kind.UNAVAILABLE,
					"Zoom returned an unexpected response.");
		}
		return url;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return (value.length() <= max) ? value : value.substring(0, max);
	}

}

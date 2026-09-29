package com.lms.integrationmanagement.service;

import com.lms.integrationmanagement.domain.MediaProviderConnection;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Response shapes for the integrations endpoints ({@code
 * docs/api/integrations.md}) and the one place a connection entity is
 * turned into one. No shape here can carry a token or API key.
 */
@Component
public class MediaConnectionViews {

	private static final TypeReference<Map<String, Object>> SETTINGS_TYPE = new TypeReference<>() {
	};

	/** {@code ConnectionView} - never contains a secret. */
	public record ConnectionView(UUID id, String provider, String scope, String status, String accountLabel,
			String externalAccountIdMasked, String accountType, List<String> grantedScopes,
			Map<String, Object> settings, boolean secretConfigured, Instant connectedAt, Instant lastVerifiedAt,
			String lastError, String disconnectedBy, Instant disconnectedAt, String disconnectedReason) {

	}

	public record ConnectionTestResult(boolean ok, String status, Instant lastVerifiedAt, String message) {

	}

	public record TeacherProviderView(String provider, boolean enabledByTenant, boolean available,
			ConnectionView connection) {

	}

	public record TeacherIntegrationsView(List<TeacherProviderView> providers) {

	}

	public record ProviderToggleView(boolean enabled, boolean available) {

	}

	public record TeacherProvidersView(ProviderToggleView zoom, ProviderToggleView youtube, ProviderToggleView vimeo) {

	}

	public record TeacherConnectionRow(UUID teacherId, String teacherName, ConnectionView connection) {

	}

	public record TenantIntegrationsView(TeacherProvidersView teacherProviders, String videoHost,
			ConnectionView dyntube, ConnectionView bunny, List<TeacherConnectionRow> teacherConnections) {

	}

	public record ImpactView(long upcomingSessionCount) {

	}

	public record AuthorizationUrlView(String authorizationUrl) {

	}

	public record VideoHostView(String videoHost) {

	}

	private final ObjectMapper objectMapper;

	public MediaConnectionViews(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ConnectionView toView(MediaProviderConnection connection) {
		if (connection == null) {
			return null;
		}
		return new ConnectionView(connection.getId(), connection.getProvider().name(), connection.getScope().name(),
				connection.getStatus().name(), connection.getExternalAccountLabel(),
				mask(connection.getExternalAccountId()), connection.getExternalAccountType(),
				scopes(connection.getGrantedScopes()), settings(connection.getSettingsJson()),
				!connection.isDisconnected(),
				connection.getCreatedAt(), connection.getLastVerifiedAt(), connection.getLastError(),
				(connection.getDisconnectedBy() == null) ? null : connection.getDisconnectedBy().name(),
				connection.getDisconnectedAt(), connection.getDisconnectedReason());
	}

	/** Last 4 characters only; {@code null} stays {@code null}. */
	static String mask(String value) {
		if (value == null) {
			return null;
		}
		if (value.length() <= 4) {
			return "****";
		}
		return "****" + value.substring(value.length() - 4);
	}

	private static List<String> scopes(String granted) {
		if (granted == null || granted.isBlank()) {
			return List.of();
		}
		return Arrays.stream(granted.trim().split("[\\s,]+")).filter(s -> !s.isBlank()).toList();
	}

	private Map<String, Object> settings(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(json, SETTINGS_TYPE);
		}
		catch (JacksonException ex) {
			return null;
		}
	}

}

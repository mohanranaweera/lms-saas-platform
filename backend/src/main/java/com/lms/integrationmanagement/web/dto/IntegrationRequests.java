package com.lms.integrationmanagement.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the integrations endpoints ({@code
 * docs/api/integrations.md}). None carries a tenant id, owner id or role -
 * those come from the authenticated context. Secret fields are write-only.
 */
public final class IntegrationRequests {

	private static final String NO_LONGER_ACCEPTED = "is no longer accepted - Bunny.net needs only an account API key";

	private IntegrationRequests() {
	}

	public record DisconnectRequest(@Size(max = 500) String reason) {

	}

	public record TeacherProvidersRequest(@NotNull Boolean zoomEnabled, @NotNull Boolean youtubeEnabled,
			@NotNull Boolean vimeoEnabled) {

	}

	public record DynTubeAccountRequest(@Size(max = 512) String apiKey) {

		@Override
		public String toString() {
			return "DynTubeAccountRequest[apiKey=redacted]";
		}

	}

	/**
	 * MEDIA-INT-01A: account API key only. The MEDIA-INT-01 fields are still
	 * recognised so an outdated client gets a 400 naming them instead of
	 * silently saving a partial setup.
	 */
	public record BunnyAccountRequest(@Size(max = 512) String apiKey,
			@Null(message = NO_LONGER_ACCEPTED) String libraryId,
			@Null(message = NO_LONGER_ACCEPTED) String cdnHostname,
			@Null(message = NO_LONGER_ACCEPTED) String tokenAuthKey) {

		@Override
		public String toString() {
			return "BunnyAccountRequest[keys=redacted]";
		}

	}

	public record VideoHostRequest(@NotNull String videoHost) {

	}

}

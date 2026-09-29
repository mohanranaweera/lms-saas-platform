package com.lms.integrationmanagement.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request bodies for the integrations endpoints ({@code
 * docs/api/integrations.md}). None carries a tenant id, owner id or role -
 * those come from the authenticated context. Secret fields are write-only.
 */
public final class IntegrationRequests {

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

	public record BunnyAccountRequest(@Size(max = 512) String apiKey, @Size(max = 20) String libraryId,
			@Size(max = 253) String cdnHostname, @Size(max = 512) String tokenAuthKey) {

		@Override
		public String toString() {
			return "BunnyAccountRequest[libraryId=" + libraryId + ", cdnHostname=" + cdnHostname + ", keys=redacted]";
		}

	}

	public record VideoHostRequest(@NotNull String videoHost) {

	}

}

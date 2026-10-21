package com.lms.integrationmanagement.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only view of media provider connections for other domains
 * (MEDIA-INT-01) - e.g. {@code live-class-management} showing whether a
 * session's host Zoom account is still connected. Tenant comes from the
 * request's {@code TenantContext}; no method accepts a tenant id. Never
 * exposes credentials.
 */
public interface MediaConnectionApi {

	/** The teacher's current (non-DISCONNECTED) connection for a provider, if any. */
	Optional<TeacherConnectionSummary> findTeacherConnection(UUID teacherUserId, MediaProvider provider);

	/** Whether the tenant lets teachers use this teacher-level provider. */
	boolean isTeacherProviderEnabled(MediaProvider provider);

	/** Status of each given connection in the current tenant; unknown ids are omitted. */
	Map<UUID, MediaConnectionStatus> connectionStatuses(Collection<UUID> connectionIds);

	/**
	 * VIDEO Phase 5d: the institute's YouTube integrations (cards), live and
	 * deleted, oldest first - the Media Library's tabs. Tenant-scoped.
	 */
	List<VideoIntegrationSummary> listVideoIntegrations();

	/** VIDEO Phase 5d: one of the institute's integrations (any status); empty for another tenant's id. */
	Optional<VideoIntegrationSummary> findVideoIntegration(UUID integrationId);

	/** VIDEO Phase 5d: whether this teacher may upload to the integration (no list = every teacher). */
	boolean isTeacherAllowedOnIntegration(UUID integrationId, UUID teacherUserId);

	/**
	 * VIDEO Phase 4: the current ACTIVE connection of the SAME integration to
	 * the SAME external channel as {@code connectionId} (a reconnect creates a
	 * new row, so an upload or status sync started on an older row continues
	 * on this one). Empty if the channel is no longer connected, was replaced
	 * by a different channel, or needs reconnecting. Does not consider the
	 * platform switch-off - see {@link VideoIntegrationSummary#usable()}.
	 */
	Optional<UUID> currentConnectionForSameChannel(UUID connectionId);

	/**
	 * VIDEO Phase 13 (ADR-026): the institute's managed streaming account
	 * (DynTube today) - its current (non-DISCONNECTED) connection, if any.
	 * Empty for other providers.
	 */
	Optional<ManagedHostSummary> findManagedHost(MediaProvider provider);

	/**
	 * @param selectedAsVideoHost the institute chose this provider as its video host
	 * @param projectConfigured a target project is set
	 * @param tokenKeyConfigured the token security key is stored (signed playback possible)
	 */
	record ManagedHostSummary(UUID connectionId, MediaConnectionStatus status, boolean selectedAsVideoHost,
			boolean projectConfigured, boolean tokenKeyConfigured) {

		/** Whether uploads and playback may use this account now. */
		public boolean usable() {
			return status == MediaConnectionStatus.ACTIVE && selectedAsVideoHost && projectConfigured
					&& tokenKeyConfigured;
		}

	}

	/** @param accountType Zoom {@code BASIC}/{@code LICENSED}; {@code null} for other providers */
	record TeacherConnectionSummary(UUID connectionId, MediaConnectionStatus status, String accountType) {

	}

	/**
	 * VIDEO Phase 5d: one institute YouTube integration.
	 *
	 * @param active false once deleted (its videos are archived)
	 * @param connectionId the live (non-DISCONNECTED) connection, or null
	 * @param platformDisabled the latest connection was switched off by a Platform Admin (TD-008)
	 * @param editScopeGranted the live grant can edit videos (ADR-018 Amendment 3)
	 */
	record VideoIntegrationSummary(UUID integrationId, String name, boolean active, UUID connectionId,
			MediaConnectionStatus status, boolean platformDisabled, boolean editScopeGranted) {

		/** Whether uploads may use this integration now. */
		public boolean usable() {
			return active && connectionId != null && status == MediaConnectionStatus.ACTIVE && !platformDisabled;
		}

		}

}

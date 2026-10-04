package com.lms.integrationmanagement.api;

import java.util.Collection;
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
	 * VIDEO Phase 4: the tenant's own live (non-DISCONNECTED) video channel,
	 * if any - the channel uploads go to.
	 */
	Optional<TenantChannelSummary> findTenantVideoChannel(MediaProvider provider);

	/**
	 * VIDEO Phase 4: the tenant's current ACTIVE connection to the SAME
	 * external channel as {@code connectionId} (a reconnect creates a new
	 * row, so an upload or status sync started on an older row continues on
	 * this one). Empty if the channel is no longer connected, was replaced by
	 * a different channel, or needs reconnecting. Does not consider the
	 * platform switch-off - see {@link TenantChannelSummary#usable()}.
	 */
	Optional<UUID> currentConnectionForSameChannel(UUID connectionId);

	/** @param accountType Zoom {@code BASIC}/{@code LICENSED}; {@code null} for other providers */
	record TeacherConnectionSummary(UUID connectionId, MediaConnectionStatus status, String accountType) {

	}

	/**
	 * @param platformDisabled switched off by a Platform Admin (TD-008)
	 * @param editScopeGranted the grant can edit videos (ADR-018 Amendment 3);
	 * channels connected before it must reconnect to edit
	 */
	record TenantChannelSummary(UUID connectionId, MediaConnectionStatus status, boolean platformDisabled,
			boolean editScopeGranted) {

		/** Whether uploads may use this channel now. */
		public boolean usable() {
			return status == MediaConnectionStatus.ACTIVE && !platformDisabled;
		}

	}

}

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

	/** @param accountType Zoom {@code BASIC}/{@code LICENSED}; {@code null} for other providers */
	record TeacherConnectionSummary(UUID connectionId, MediaConnectionStatus status, String accountType) {

	}

}

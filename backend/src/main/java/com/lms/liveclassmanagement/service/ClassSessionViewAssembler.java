package com.lms.liveclassmanagement.service;

import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.liveclassmanagement.domain.ClassSession;
import com.lms.liveclassmanagement.support.LiveClassAccessGuard;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds {@link ClassSessionView}s with {@code hostConnectionStatus}
 * (MEDIA-INT-01): whether the teacher's Zoom account hosting each session is
 * still connected, so the teacher/staff UI can offer "Recreate meeting". One
 * batched lookup per call. Students never see it ({@code null}).
 */
@Component
public class ClassSessionViewAssembler {

	private final MediaConnectionApi mediaConnectionApi;

	private final LiveClassAccessGuard accessGuard;

	public ClassSessionViewAssembler(MediaConnectionApi mediaConnectionApi, LiveClassAccessGuard accessGuard) {
		this.mediaConnectionApi = mediaConnectionApi;
		this.accessGuard = accessGuard;
	}

	public ClassSessionView toView(ClassSession session) {
		return toViews(List.of(session)).get(0);
	}

	public List<ClassSessionView> toViews(List<ClassSession> sessions) {
		if (accessGuard.isCurrentPrincipalStudent()) {
			return sessions.stream().map(session -> ClassSessionView.from(session, null)).toList();
		}
		Set<UUID> connectionIds = sessions.stream()
			.map(ClassSession::getProviderConnectionId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, MediaConnectionStatus> statuses = connectionIds.isEmpty() ? Map.of()
				: mediaConnectionApi.connectionStatuses(connectionIds);
		return sessions.stream().map(session -> {
			UUID connectionId = session.getProviderConnectionId();
			MediaConnectionStatus status = (connectionId == null) ? null
					: statuses.getOrDefault(connectionId, MediaConnectionStatus.DISCONNECTED);
			return ClassSessionView.from(session, (status == null) ? null : status.name());
		}).toList();
	}

	/** Whether a provisioned session's host connection is gone (needs "Recreate meeting"). */
	public boolean hostConnectionLost(ClassSession session) {
		UUID connectionId = session.getProviderConnectionId();
		if (connectionId == null) {
			return false;
		}
		return mediaConnectionApi.connectionStatuses(List.of(connectionId))
			.getOrDefault(connectionId, MediaConnectionStatus.DISCONNECTED) != MediaConnectionStatus.ACTIVE;
	}

}

package com.lms.integrationmanagement.service;

import com.lms.integrationmanagement.domain.ZoomCapability;
import com.lms.integrationmanagement.domain.ZoomExternalUserStatus;
import com.lms.integrationmanagement.domain.ZoomHostStrategy;
import com.lms.integrationmanagement.domain.ZoomIntegrationStatus;
import com.lms.integrationmanagement.domain.ZoomMappingStatus;
import com.lms.integrationmanagement.domain.ZoomReadiness;
import com.lms.integrationmanagement.domain.ZoomTeacherPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ZoomIntegrationViews {

	public record TeacherView(UUID userId, String name, String email) {
	}

	public record ExternalUserView(UUID id, String zoomUserId, String email, String displayName,
			ZoomExternalUserStatus status, Integer zoomUserType, ZoomCapability hostCapability,
			ZoomCapability webinarCapability, Instant lastSyncedAt, Instant capabilityCheckedAt) {
	}

	public record MappingView(UUID id, UUID teacherUserId, TeacherView teacher, UUID externalUserId,
			ExternalUserView externalUser, ZoomMappingStatus status, Instant mappedAt, Instant revokedAt) {
	}

	public record IntegrationView(UUID id, String name, String description, ZoomIntegrationStatus status,
			ZoomTeacherPolicy teacherPolicy, ZoomHostStrategy hostStrategy, ZoomReadiness readiness,
			String zoomAccountLabel, Instant connectedAt, Instant lastVerifiedAt, String lastError,
			List<TeacherView> allowedTeachers, List<MappingView> mappings) {
	}

}

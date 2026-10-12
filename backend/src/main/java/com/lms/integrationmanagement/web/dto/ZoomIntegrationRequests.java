package com.lms.integrationmanagement.web.dto;

import com.lms.integrationmanagement.domain.ZoomTeacherPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class ZoomIntegrationRequests {

	private ZoomIntegrationRequests() {
	}

	public record SaveIntegrationRequest(@NotBlank @Size(max = 100) String name,
			@Size(max = 500) String description, @NotNull ZoomTeacherPolicy teacherPolicy,
			List<UUID> allowedTeacherIds) {
	}

	public record MapTeacherRequest(@NotNull UUID externalUserId) {
	}

}

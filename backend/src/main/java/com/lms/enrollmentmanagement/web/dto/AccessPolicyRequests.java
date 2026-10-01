package com.lms.enrollmentmanagement.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Wave 10 access-policy and extension request bodies. Bounds mirror the V65 CHECK constraints. */
public final class AccessPolicyRequests {

	private AccessPolicyRequests() {
	}

	public record TenantAccessPolicyRequest(@NotNull @Min(0) @Max(90) Integer gracePeriodDays) {
	}

	/** Both null removes the course override. */
	public record CourseAccessPolicyRequest(@Min(0) @Max(90) Integer gracePeriodDays,
			@Min(1) @Max(20) Integer maxDevices) {
	}

	public record AccessExtensionRequest(@NotNull Instant newExpiresAt, @NotBlank @Size(max = 500) String reason) {
	}

}

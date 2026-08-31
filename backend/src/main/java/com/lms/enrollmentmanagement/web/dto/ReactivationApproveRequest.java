package com.lms.enrollmentmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/reactivation-requests/{id}/approve} request body -
 * {@code note} is optional/nullable, mirroring {@code SlipApproveRequest}'s
 * shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReactivationApproveRequest(@Size(max = 1000) String note) {

	public ReactivationApproveRequest() {
		this(null);
	}

}

package com.lms.identityaccessservice.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code PUT /api/v1/staff/permission-matrix} body (STAFF-MGMT-02 §2.5). No tenant id is accepted. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PermissionMatrixUpdateRequest(@NotNull @PositiveOrZero Long expectedVersion,
		@NotNull @Size(max = 1000) List<@Valid @NotNull Change> changes) {

	public record Change(@NotBlank @Size(max = 50) String roleCode, @NotBlank @Size(max = 100) String capabilityKey,
			@NotNull Boolean granted) {

	}

}

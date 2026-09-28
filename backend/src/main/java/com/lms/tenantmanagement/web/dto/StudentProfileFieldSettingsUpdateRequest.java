package com.lms.tenantmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code PUT /api/v1/tenant-config/student-profile-fields} request body
 * (STU-PROF-01). Fields not listed keep their current settings. Key
 * validity, locked keys and flag combinations are checked by {@code
 * StudentProfileFieldConfigService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentProfileFieldSettingsUpdateRequest(@NotNull @Size(max = 100) List<@Valid @NotNull Item> fields) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(@NotBlank @Size(max = 64) String key, @NotNull Boolean enabled, @NotNull Boolean required,
			@NotNull Boolean collectAtRegistration) {

	}

}

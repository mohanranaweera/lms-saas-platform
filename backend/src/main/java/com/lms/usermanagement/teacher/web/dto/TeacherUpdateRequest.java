package com.lms.usermanagement.teacher.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Profile-only teacher update. Credentials are intentionally absent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeacherUpdateRequest(
		@NotBlank @Size(max = 120) String firstName,
		@NotBlank @Size(max = 120) String lastName,
		@NotBlank @Size(max = 40) String contactNumber,
		@DecimalMin(value = "0.00") @DecimalMax(value = "100.00") @Digits(integer = 3, fraction = 2) BigDecimal instituteCommissionPercent,
		@Size(max = 2000) String qualifications,
		@Size(max = 5000) String description,
		UUID profileMediaFileId,
			Boolean publicProfileDisabled) {
}

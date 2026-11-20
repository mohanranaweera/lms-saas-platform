package com.lms.usermanagement.teacher.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Create request for a tenant-scoped teacher profile and credential. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeacherCreateRequest(
		@NotBlank @Size(max = 255) String name,
		@Size(max = 255) String firstName,
		@Size(max = 255) String lastName,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(min = 8, max = 255) String password,
		@Size(max = 40) String contactNumber,
		@DecimalMin(value = "0.00") @DecimalMax(value = "100.00") @Digits(integer = 3, fraction = 2) BigDecimal instituteCommissionPercent,
		@Size(max = 2000) String qualifications,
		@Size(max = 5000) String description,
		UUID profileMediaFileId,
			Boolean publicProfileDisabled) {

	/** Backwards-compatible constructor used by existing callers/tests. */
	public TeacherCreateRequest(String name, String email, String password) {
		this(name, name, "Legacy", email, password, "unknown", BigDecimal.ZERO, null, null, null, false);
	}
}

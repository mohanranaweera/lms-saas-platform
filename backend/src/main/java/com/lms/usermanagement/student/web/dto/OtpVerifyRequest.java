package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/students/register/otp/verify} (Wave 3). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OtpVerifyRequest(@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(max = 20) String otp) {

}

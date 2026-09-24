package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/students/register/otp/send} (Wave 3, email-only). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OtpSendRequest(@NotBlank @Email @Size(max = 255) String email) {

}

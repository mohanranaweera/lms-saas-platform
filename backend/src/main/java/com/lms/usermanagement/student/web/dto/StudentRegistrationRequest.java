package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public student self-registration request body ({@code POST
 * /api/v1/students/register}, Wave 3/PAR-03-01). {@code
 * guardianName}/{@code guardianPhone}/{@code school}/{@code grade}/{@code
 * stream}/{@code mobile} are all optional at the DTO/validation level - their
 * actual required-ness is resolved server-side against the calling tenant's
 * {@code ConfigDomain.STUDENT} configuration (never a client claim), so a
 * missing-but-tenant-required field is rejected by {@code
 * StudentRegistrationService}, not by Bean Validation here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentRegistrationRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Email @Size(max = 255) String email,

		@NotBlank @Size(min = 8, max = 255) String password,

		@Size(max = 255) String guardianName,

		@Size(max = 50) String guardianPhone,

		@Size(max = 255) String school,

		@Size(max = 50) String grade,

		@Size(max = 50) String stream,

		@Size(max = 50) String mobile) {

}

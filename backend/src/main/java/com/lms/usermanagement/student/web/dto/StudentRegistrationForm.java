package com.lms.usermanagement.student.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * STU-MGMT-02 create/edit form (spec section 3), allowlisted: there is no tenantId,
 * status, role, index number or QR field, and unknown properties are ignored (never
 * read). {@code password} is used on create only (blank = server-generated temporary
 * password); edit never touches the password. Phone numbers are strings so leading
 * zeros are preserved. {@code dateOfBirth} is {@code yyyy-MM-dd} or blank (unknown).
 * Required-ness of first name / email / mobile / courses is enforced by the service so
 * create and edit can share this DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StudentRegistrationForm(

		@Email @Size(max = 255) String email,

		@Size(max = 128) String password,

		@Size(max = 120) String firstName,

		@Size(max = 120) String lastName,

		@Size(max = 50) @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String whatsappNumber,

		@Pattern(regexp = "^\\s*$|^\\d{4}-\\d{2}-\\d{2}$", message = "Use the format YYYY-MM-DD") String dateOfBirth,

		@Size(max = 50) @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String mobile,

		@JsonAlias("addressLine1") @Size(max = 255) String addressLine,

		@Size(max = 255) String addressLine2,

		@Size(max = 100) String city,

		@Size(max = 20) String postalCode,

		@Size(max = 255) String guardianName,

		@Size(max = 50) @Pattern(regexp = PHONE, message = PHONE_MESSAGE) String guardianPhone,

		@Size(max = 20) String guardianNic,

		@JsonAlias({ "homePhone", "homeTelephone" }) @Size(max = 50) @Pattern(regexp = PHONE,
				message = PHONE_MESSAGE) String fixedPhone,

		@Size(max = 255) String school,

		@Min(2000) @Max(2100) Integer alYear,

		@JsonAlias("alStream") @Size(max = 50) String stream,

		@Size(max = 100) List<UUID> courseIds) {

	public static final String PHONE = "^[0-9+ ()\\-]*$";

	public static final String PHONE_MESSAGE = "Use digits, spaces, +, - and brackets only";

}

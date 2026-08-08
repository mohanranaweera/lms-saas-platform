package com.lms.tenantmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public tenant self-registration request body ({@code TEN-1}).
 *
 * <p>Deliberately has no {@code id}, {@code status}, {@code tenantId}, or
 * any approval-related field - this is the primary defense against a
 * client-supplied status/id. Spring Boot's autoconfigured Jackson
 * {@code ObjectMapper} already disables {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * by default (unlike vanilla Jackson), so extra fields in a raw JSON body
 * (e.g. a client attempting to smuggle {@code "status": "active"}) are
 * silently dropped rather than bound or rejected with a schema-leaking 400.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} is added explicitly
 * below anyway, so this behavior does not silently depend on that default
 * never changing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantRegistrationRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Size(max = 63) @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
				message = "Subdomain must be lowercase alphanumeric with optional hyphens (DNS-label safe)") String subdomain,

		@NotBlank @Size(max = 100) String requestedPlan,

		@NotBlank @Size(max = 255) String contactName,

		@NotBlank @Email @Size(max = 255) String contactEmail,

		@Size(max = 50) String contactPhone) {

}

package com.lms.tenantmanagement.web.dto;

/**
 * One field the public registration form must render (STU-PROF-01): only
 * fields the tenant marked "ask at registration", locked fields excluded
 * (the form always has name/email/password). Carries display metadata only -
 * never the tenant's full field configuration.
 */
public record PublicRegistrationFieldResponse(String key, String label, String group, String type, int maxLength,
		boolean required) {

}

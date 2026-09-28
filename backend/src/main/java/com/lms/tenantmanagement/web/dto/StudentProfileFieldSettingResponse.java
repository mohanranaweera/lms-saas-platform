package com.lms.tenantmanagement.web.dto;

/**
 * One row of the Tenant Admin student profile field settings table
 * (STU-PROF-01). {@code locked} rows (Email, Full name) are display-only.
 */
public record StudentProfileFieldSettingResponse(String key, String label, String group, String type, int maxLength,
		boolean sensitive, boolean locked, boolean enabled, boolean required, boolean collectAtRegistration) {

}

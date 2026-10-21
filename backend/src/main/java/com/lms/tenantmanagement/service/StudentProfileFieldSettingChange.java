package com.lms.tenantmanagement.service;

/** One requested change to a tenant's student profile field settings (STU-PROF-01). */
public record StudentProfileFieldSettingChange(String key, boolean enabled, boolean required,
		boolean collectAtRegistration) {

}

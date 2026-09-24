package com.lms.tenantmanagement.web.dto;

/**
 * Response shape for the anonymous {@code GET
 * /api/v1/public/tenant-config/student-registration-policy} read (Wave 3
 * gap-fill). Deliberately an explicit, narrow record - NOT a pass-through of
 * {@link com.lms.tenantmanagement.api.ConfigDomain#STUDENT}'s generic
 * property list - so any future property added to that domain's registry
 * (e.g. a later wave's sensitive setting) is never accidentally exposed to
 * an anonymous visitor just by being registered; only these eight named,
 * known-safe booleans are ever returned. See {@code
 * PublicStudentRegistrationPolicyController} for why this route bypasses
 * {@code TenantConfigController}'s {@code BRANDING_SETTINGS} permission
 * gate, mirroring {@code PublicBrandingResponse}'s precedent for {@code
 * ConfigDomain#BRANDING}.
 */
public record PublicStudentRegistrationPolicyResponse(boolean publicRegistrationEnabled, boolean approvalRequired,
		boolean otpRequired, boolean requireGuardianInfo, boolean requireSchool, boolean requireGrade,
		boolean requireStream, boolean requireMobile) {

}

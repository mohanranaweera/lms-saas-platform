package com.lms.tenantmanagement.web.dto;

/** Response shape for the anonymous {@code GET /api/v1/public/tenant-config/branding} read. */
public record PublicBrandingResponse(String primaryColor, String secondaryColor, String logoUrl, String faviconUrl,
		String instituteName) {

}

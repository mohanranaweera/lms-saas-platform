package com.lms.tenantmanagement.web.dto;

/** Response shape for anonymous tenant branding reads. */
public record PublicBrandingResponse(String primaryColor, String secondaryColor, String logoUrl, String faviconUrl,
		String instituteName) {

}

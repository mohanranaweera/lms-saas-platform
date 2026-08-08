package com.lms.tenantmanagement.service;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Set;

/**
 * Denylist of platform-reserved subdomains (routing/operational names that
 * would collide with or impersonate platform infrastructure, e.g. the
 * platform's own {@code www}/{@code api}/{@code admin} surfaces). A DB
 * {@code CHECK} constraint cannot express this - see
 * {@code V2__create_tenant_table.sql} - so it is enforced here,
 * application-side.
 */
public class NotReservedSubdomainValidator implements ConstraintValidator<NotReservedSubdomain, String> {

	static final Set<String> RESERVED_SUBDOMAINS = Set.of("www", "api", "admin", "app", "platform", "assets",
			"static", "mail", "ftp", "cdn", "docs", "support", "help", "status", "blog", "dev", "staging", "test");

	@Override
	public boolean isValid(String subdomain, ConstraintValidatorContext context) {
		if (subdomain == null) {
			// Absence/format is enforced separately (@NotBlank/@Pattern on the
			// request DTO); this validator only judges reserved-word matches.
			return true;
		}
		return !RESERVED_SUBDOMAINS.contains(subdomain.toLowerCase(java.util.Locale.ROOT));
	}

}

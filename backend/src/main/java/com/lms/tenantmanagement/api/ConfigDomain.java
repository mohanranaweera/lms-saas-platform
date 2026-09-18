package com.lms.tenantmanagement.api;

/**
 * The 17 typed tenant-configuration domains (Wave 1 - Tenant Admin IA +
 * Configuration Framework), verbatim from {@code
 * docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md} §7. Every future business
 * domain reads its own settings by name from this fixed set via {@link
 * TenantConfigApi} - only {@code GENERAL}/{@code BRANDING} have registered
 * properties as of this wave (see {@code
 * com.lms.tenantmanagement.config.ConfigPropertyRegistry}); every other
 * value is a placeholder a later wave will populate.
 */
public enum ConfigDomain {

	GENERAL, BRANDING, ACADEMIC, STUDENT, TEACHER, COURSE, PAYMENT, FINANCE, ATTENDANCE, EXAM, CONTENT, VIDEO,
	NOTIFICATION, SECURITY, DEVICE, DOMAIN, INTEGRATION

}

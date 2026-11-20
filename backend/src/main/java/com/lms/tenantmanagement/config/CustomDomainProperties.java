package com.lms.tenantmanagement.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * TEN-DOM-01 / ADR-021 settings (all non-secret, ADR-019 Decision 3):
 * <ul>
 * <li>{@code platform-domain} ({@code PLATFORM_DOMAIN}): the domain whose
 * one-label subdomains are tenant subdomains, e.g. {@code lms.test} locally.
 * Empty = legacy resolution (first label of any host is the subdomain) and
 * custom domains never resolve - a host can't be told apart from a subdomain
 * without it.</li>
 * <li>{@code cname-target} ({@code CUSTOM_DOMAIN_CNAME_TARGET}): the name
 * tenants point their CNAME at. Empty = custom domains unavailable.</li>
 * <li>{@code verification-mode}: {@code dns} (default) or {@code accept-all}.
 * {@code accept-all} skips DNS and is for the local profile only, like the
 * fake payment gateway fallback.</li>
 * <li>{@code max-per-tenant}: domains a tenant may hold (not counting removed).</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.custom-domains")
public class CustomDomainProperties {

	public enum VerificationMode {
		DNS, ACCEPT_ALL
	}

	private String platformDomain = "";

	private String cnameTarget = "";

	private VerificationMode verificationMode = VerificationMode.DNS;

	private int maxPerTenant = 3;

	private int dnsTimeoutMillis = 3000;

	/** Verify clicks per tenant per hour (each one is a pair of DNS lookups). */
	private int verifyAttemptsPerHour = 20;

	/** Normalised: lower case, no leading/trailing dots. */
	public String getPlatformDomain() {
		return normalise(platformDomain);
	}

	public void setPlatformDomain(String platformDomain) {
		this.platformDomain = platformDomain;
	}

	public String getCnameTarget() {
		return normalise(cnameTarget);
	}

	public void setCnameTarget(String cnameTarget) {
		this.cnameTarget = cnameTarget;
	}

	public VerificationMode getVerificationMode() {
		return verificationMode;
	}

	public void setVerificationMode(VerificationMode verificationMode) {
		this.verificationMode = verificationMode;
	}

	public int getMaxPerTenant() {
		return maxPerTenant;
	}

	public void setMaxPerTenant(int maxPerTenant) {
		this.maxPerTenant = maxPerTenant;
	}

	public int getDnsTimeoutMillis() {
		return dnsTimeoutMillis;
	}

	public void setDnsTimeoutMillis(int dnsTimeoutMillis) {
		this.dnsTimeoutMillis = dnsTimeoutMillis;
	}

	public int getVerifyAttemptsPerHour() {
		return verifyAttemptsPerHour;
	}

	public void setVerifyAttemptsPerHour(int verifyAttemptsPerHour) {
		this.verifyAttemptsPerHour = verifyAttemptsPerHour;
	}

	/** Custom domains can be added and resolved only with both names configured. */
	public boolean isAvailable() {
		return StringUtils.hasText(getPlatformDomain()) && StringUtils.hasText(getCnameTarget());
	}

	private static String normalise(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim().toLowerCase(Locale.ROOT);
		while (trimmed.startsWith(".")) {
			trimmed = trimmed.substring(1);
		}
		while (trimmed.endsWith(".")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}

}

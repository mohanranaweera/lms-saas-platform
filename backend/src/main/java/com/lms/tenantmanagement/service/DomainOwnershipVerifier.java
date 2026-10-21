package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.config.CustomDomainProperties;
import com.lms.tenantmanagement.config.CustomDomainProperties.VerificationMode;
import com.lms.tenantmanagement.service.DnsRecordLookup.DnsLookupException;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * TEN-DOM-01 / ADR-021 decision 2: proves a tenant controls a hostname. Both
 * must be present:
 * <ul>
 * <li>{@code CNAME <hostname> -> <cname-target>} (traffic reaches the platform);</li>
 * <li>{@code TXT _lms-verify.<hostname> = <verification code>} (this tenant, not
 * just anyone pointing a name at us).</li>
 * </ul>
 * A lookup that can't complete is {@link Result#INCONCLUSIVE}, never a
 * failure, so a DNS hiccup can't break a working domain.
 *
 * <p>{@code accept-all} mode skips DNS for local testing and is refused at
 * startup under the {@code prod} profile.
 */
@Component
public class DomainOwnershipVerifier {

	static final String TXT_PREFIX = "_lms-verify.";

	public enum Result {
		VERIFIED, NOT_VERIFIED, INCONCLUSIVE
	}

	public record Outcome(Result result, String message) {

	}

	private final DnsRecordLookup dns;

	private final CustomDomainProperties properties;

	public DomainOwnershipVerifier(DnsRecordLookup dns, CustomDomainProperties properties, Environment environment) {
		this.dns = dns;
		this.properties = properties;
		if (properties.getVerificationMode() == VerificationMode.ACCEPT_ALL
				&& environment.acceptsProfiles(Profiles.of("prod"))) {
			throw new IllegalStateException(
					"app.custom-domains.verification-mode=accept-all is for local development only, never prod");
		}
	}

	public static String txtRecordName(String hostname) {
		return TXT_PREFIX + hostname;
	}

	public Outcome verify(String hostname, String verificationCode) {
		if (properties.getVerificationMode() == VerificationMode.ACCEPT_ALL) {
			return new Outcome(Result.VERIFIED, null);
		}
		String target = properties.getCnameTarget();
		if (target.isEmpty()) {
			return new Outcome(Result.INCONCLUSIVE, "Custom domains are not configured on this platform yet.");
		}
		try {
			List<String> cnames = dns.lookup(hostname, "CNAME").stream().map(DomainOwnershipVerifier::name).toList();
			if (cnames.isEmpty()) {
				return new Outcome(Result.NOT_VERIFIED,
						"No CNAME record found for " + hostname + ". Point it to " + target + ".");
			}
			if (!cnames.contains(target)) {
				return new Outcome(Result.NOT_VERIFIED, "The CNAME record for " + hostname + " points to "
						+ String.join(", ", cnames) + ", not " + target + ".");
			}
			String txtName = txtRecordName(hostname);
			List<String> texts = dns.lookup(txtName, "TXT").stream().map(DomainOwnershipVerifier::text).toList();
			if (texts.isEmpty()) {
				return new Outcome(Result.NOT_VERIFIED, "No TXT record found at " + txtName + ".");
			}
			if (!texts.contains(verificationCode)) {
				return new Outcome(Result.NOT_VERIFIED,
						"The TXT record at " + txtName + " doesn't contain this domain's verification code.");
			}
			return new Outcome(Result.VERIFIED, null);
		}
		catch (DnsLookupException ex) {
			return new Outcome(Result.INCONCLUSIVE, "The DNS lookup didn't complete. Try again in a few minutes.");
		}
	}

	/** A CNAME value as a comparable name: lower case, no trailing dot. */
	private static String name(String value) {
		return Hostnames.normalise(value);
	}

	/** A TXT value without its quoting; multi-string records are joined. */
	private static String text(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("\"")) {
			trimmed = trimmed.replace("\" \"", "").replace("\"", "");
		}
		return trimmed.trim().toLowerCase(Locale.ROOT);
	}

}

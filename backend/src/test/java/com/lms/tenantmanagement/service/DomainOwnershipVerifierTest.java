package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.tenantmanagement.config.CustomDomainProperties;
import com.lms.tenantmanagement.config.CustomDomainProperties.VerificationMode;
import com.lms.tenantmanagement.service.DnsRecordLookup.DnsLookupException;
import com.lms.tenantmanagement.service.DomainOwnershipVerifier.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** TEN-DOM-01 / ADR-021 decision 2: CNAME + TXT ownership proof. */
class DomainOwnershipVerifierTest {

	private static final String HOST = "learn.abc.lk";

	private static final String CODE = "0123456789abcdef0123456789abcdef";

	private final Map<String, List<String>> records = new HashMap<>();

	private boolean failLookups;

	private CustomDomainProperties properties;

	private DomainOwnershipVerifier verifier;

	@BeforeEach
	void setUp() {
		properties = new CustomDomainProperties();
		properties.setPlatformDomain("lms.test");
		properties.setCnameTarget("tenants.lms.test");
		DnsRecordLookup dns = (name, type) -> {
			if (failLookups) {
				throw new DnsLookupException("timeout", null);
			}
			return records.getOrDefault(type + " " + name, List.of());
		};
		verifier = new DomainOwnershipVerifier(dns, properties, new MockEnvironment());
	}

	@Test
	void verifiedWhenBothRecordsMatch() {
		records.put("CNAME " + HOST, List.of("Tenants.LMS.test."));
		records.put("TXT _lms-verify." + HOST, List.of("something-else", CODE));

		assertThat(verifier.verify(HOST, CODE).result()).isEqualTo(Result.VERIFIED);
	}

	@Test
	void quotedTxtValuesAreUnquoted() {
		records.put("CNAME " + HOST, List.of("tenants.lms.test"));
		records.put("TXT _lms-verify." + HOST, List.of("\"" + CODE.substring(0, 10) + "\" \"" + CODE.substring(10) + "\""));

		assertThat(verifier.verify(HOST, CODE).result()).isEqualTo(Result.VERIFIED);
	}

	@Test
	void missingCnameIsNotVerified() {
		records.put("TXT _lms-verify." + HOST, List.of(CODE));

		DomainOwnershipVerifier.Outcome outcome = verifier.verify(HOST, CODE);

		assertThat(outcome.result()).isEqualTo(Result.NOT_VERIFIED);
		assertThat(outcome.message()).contains("No CNAME record").contains("tenants.lms.test");
	}

	@Test
	void cnameToSomewhereElseIsNotVerified() {
		records.put("CNAME " + HOST, List.of("elsewhere.example.com."));
		records.put("TXT _lms-verify." + HOST, List.of(CODE));

		DomainOwnershipVerifier.Outcome outcome = verifier.verify(HOST, CODE);

		assertThat(outcome.result()).isEqualTo(Result.NOT_VERIFIED);
		assertThat(outcome.message()).contains("elsewhere.example.com");
	}

	@Test
	void missingOrWrongTxtIsNotVerified() {
		records.put("CNAME " + HOST, List.of("tenants.lms.test"));
		assertThat(verifier.verify(HOST, CODE).message()).contains("No TXT record");

		records.put("TXT _lms-verify." + HOST, List.of("another-tenants-code"));
		DomainOwnershipVerifier.Outcome outcome = verifier.verify(HOST, CODE);
		assertThat(outcome.result()).isEqualTo(Result.NOT_VERIFIED);
		assertThat(outcome.message()).contains("verification code");
	}

	@Test
	void lookupFailureIsInconclusiveNotAFailure() {
		failLookups = true;

		assertThat(verifier.verify(HOST, CODE).result()).isEqualTo(Result.INCONCLUSIVE);
	}

	@Test
	void noConfiguredTargetIsInconclusive() {
		properties.setCnameTarget("");

		assertThat(verifier.verify(HOST, CODE).result()).isEqualTo(Result.INCONCLUSIVE);
	}

	@Test
	void acceptAllSkipsDns() {
		properties.setVerificationMode(VerificationMode.ACCEPT_ALL);
		failLookups = true;

		assertThat(verifier.verify(HOST, CODE).result()).isEqualTo(Result.VERIFIED);
	}

	@Test
	void acceptAllIsRefusedUnderProd() {
		properties.setVerificationMode(VerificationMode.ACCEPT_ALL);
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> new DomainOwnershipVerifier((name, type) -> List.of(), properties, prod))
			.isInstanceOf(IllegalStateException.class);
	}

}

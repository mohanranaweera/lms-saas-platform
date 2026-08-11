package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit coverage of {@link NotReservedSubdomainValidator}'s denylist - the
 * application-layer enforcement of the reserved-subdomain rule a DB
 * {@code CHECK} constraint cannot express (see
 * {@code V2__create_tenant_table.sql}).
 *
 * <p>{@link #EXPECTED_RESERVED_SUBDOMAINS} is a literal, independently
 * authored copy of the denylist this security control is supposed to
 * enforce - deliberately <strong>not</strong> derived from
 * {@link NotReservedSubdomainValidator#RESERVED_SUBDOMAINS}. If a word were
 * ever accidentally removed from the production denylist, a test sourced
 * from that same field would silently stop generating the case and stay
 * green; asserting the two sets are equal, and driving the per-word checks
 * from this independent literal, means such a regression fails the suite
 * instead.
 */
class NotReservedSubdomainValidatorTest {

	private static final Set<String> EXPECTED_RESERVED_SUBDOMAINS = Set.of("www", "api", "admin", "app", "platform",
			"assets", "static", "mail", "ftp", "cdn", "docs", "support", "help", "status", "blog", "dev", "staging",
			"test");

	private final NotReservedSubdomainValidator validator = new NotReservedSubdomainValidator();

	static Stream<String> reservedSubdomains() {
		return EXPECTED_RESERVED_SUBDOMAINS.stream();
	}

	@Test
	void denylistMatchesTheIndependentlyMaintainedExpectedSet() {
		assertThat(NotReservedSubdomainValidator.RESERVED_SUBDOMAINS).isEqualTo(EXPECTED_RESERVED_SUBDOMAINS);
	}

	@ParameterizedTest
	@MethodSource("reservedSubdomains")
	void rejectsEveryReservedSubdomainInTheExpectedDenylist(String reserved) {
		assertThat(validator.isValid(reserved, null)).isFalse();
	}

	@ParameterizedTest
	@MethodSource("reservedSubdomains")
	void rejectsReservedSubdomainsRegardlessOfCase(String reserved) {
		assertThat(validator.isValid(reserved.toUpperCase(Locale.ROOT), null)).isFalse();
	}

	@Test
	void acceptsANormalNonReservedSubdomain() {
		assertThat(validator.isValid("acme-institute", null)).isTrue();
		assertThat(validator.isValid("greenwood-college", null)).isTrue();
	}

	@Test
	void treatsNullAsValidBecauseAbsenceFormatIsEnforcedByOtherAnnotations() {
		assertThat(validator.isValid(null, null)).isTrue();
	}

}

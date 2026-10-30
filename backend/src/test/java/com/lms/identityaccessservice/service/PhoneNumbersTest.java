package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-031: sign-in mobile normalisation (same rules as the V108 backfill). */
class PhoneNumbersTest {

	@ParameterizedTest
	@ValueSource(strings = { "0771234567", "771234567", "077 123 4567", "077-123-4567", "+94771234567",
			"+94 77 123 4567", "0094771234567", " 0771234567 " })
	void sriLankanMobileFormsAllNormaliseToTheSameNumber(String raw) {
		assertThat(PhoneNumbers.toE164(raw, "LK")).contains("+94771234567");
	}

	@Test
	void aNumberWithItsOwnCountryCodeKeepsIt() {
		assertThat(PhoneNumbers.toE164("+44 7400 123456", "LK")).contains("+447400123456");
	}

	@Test
	void localNumbersFollowTheTenantRegion() {
		assertThat(PhoneNumbers.toE164("07400 123456", "GB")).contains("+447400123456");
		assertThat(PhoneNumbers.toE164("07400 123456", "LK")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "12", "abc", "0771234", "+94 11 2345678", "1234567890123456789012345" })
	void invalidOrNonMobileNumbersAreRejected(String raw) {
		// +94 11 ... is a Colombo landline - not a mobile, so not a sign-in number.
		assertThat(PhoneNumbers.toE164(raw, "LK")).isEmpty();
	}

	@Test
	void nullAndOverlongInputAreRejected() {
		assertThat(PhoneNumbers.toE164(null, "LK")).isEmpty();
		assertThat(PhoneNumbers.toE164("0".repeat(41), "LK")).isEmpty();
	}

	@Test
	void anUnknownRegionFallsBackToSriLanka() {
		assertThat(PhoneNumbers.toE164("0771234567", "XX")).contains("+94771234567");
		assertThat(PhoneNumbers.toE164("0771234567", null)).contains("+94771234567");
		assertThat(PhoneNumbers.toE164("0771234567", "lk")).contains("+94771234567");
	}

	@Test
	void maskedKeepsOnlyTheLastThreeDigits() {
		assertThat(PhoneNumbers.masked("+94771234567")).isEqualTo("***567");
		assertThat(PhoneNumbers.masked(null)).isNull();
	}

}

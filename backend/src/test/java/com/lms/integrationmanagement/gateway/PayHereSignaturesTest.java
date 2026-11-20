package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * PayHere hash/md5sig (PAY-GW-01). Expected values were computed outside the
 * JVM (Python hashlib) from PayHere's documented formulas, so this test does
 * not merely re-run the implementation against itself.
 */
class PayHereSignaturesTest {

	private static final String MERCHANT_ID = "1211149";

	private static final String SECRET = "secret123";

	@Test
	void checkoutHashMatchesPayHeresDocumentedFormula() {
		assertThat(PayHereSignatures.checkoutHash(MERCHANT_ID, "ORDER_1", new BigDecimal("1000"), "LKR", SECRET))
			.isEqualTo("9D45DA0612673C33B31DC910B775957C");
	}

	@Test
	void amountIsAlwaysFormattedWithTwoDecimals() {
		assertThat(PayHereSignatures.formatAmount(new BigDecimal("4500"))).isEqualTo("4500.00");
		assertThat(PayHereSignatures.formatAmount(new BigDecimal("4500.5"))).isEqualTo("4500.50");
		assertThat(PayHereSignatures.checkoutHash(MERCHANT_ID, "PH-x", new BigDecimal("4500.00"), "LKR", SECRET))
			.isEqualTo("05C9D2339760D3A9BE3192CEA772DE61");
	}

	@Test
	void notificationSignatureMatchesPayHeresDocumentedFormula() {
		assertThat(PayHereSignatures.notificationSignature(MERCHANT_ID, "ORDER_1", "1000.00", "LKR", "2", SECRET))
			.isEqualTo("230B18F023D5171737C4484E328A5FCF");
	}

	@Test
	void matchesIsCaseInsensitiveAndRejectsMissingOrWrongValues() {
		String expected = "230B18F023D5171737C4484E328A5FCF";
		assertThat(PayHereSignatures.matches(expected, expected.toLowerCase())).isTrue();
		assertThat(PayHereSignatures.matches(expected, null)).isFalse();
		assertThat(PayHereSignatures.matches(expected, "00000000000000000000000000000000")).isFalse();
	}

}

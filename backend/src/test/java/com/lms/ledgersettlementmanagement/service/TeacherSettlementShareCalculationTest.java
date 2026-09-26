package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Wave 7 - share = net x percent / 100, HALF_UP to 2 dp, sign preserved. */
class TeacherSettlementShareCalculationTest {

	@ParameterizedTest
	@CsvSource({ "240.00, 40.00, 96.00", "10.01, 33.33, 3.34", "0.05, 50.00, 0.03", "-30.00, 40.00, -12.00",
			"-0.05, 50.00, -0.03", "1234.56, 0.00, 0.00", "1234.56, 100.00, 1234.56", "99.99, 12.50, 12.50" })
	void shareIsRoundedHalfUpToCents(String net, String percent, String expected) {
		BigDecimal share = TeacherSettlementService.shareOf(new BigDecimal(net), new BigDecimal(percent));
		assertThat(share).isEqualByComparingTo(expected);
		assertThat(share.scale()).isEqualTo(2);
	}

}

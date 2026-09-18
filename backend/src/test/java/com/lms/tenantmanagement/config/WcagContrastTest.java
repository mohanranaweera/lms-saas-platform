package com.lms.tenantmanagement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class WcagContrastTest {

	@Test
	void blackOnWhiteIsTheMaximumTwentyOneToOneRatio() {
		assertThat(WcagContrast.ratio("#000000", "#FFFFFF")).isCloseTo(21.0, within(0.01));
	}

	@Test
	void ratioIsSymmetricRegardlessOfArgumentOrder() {
		assertThat(WcagContrast.ratio("#FFFFFF", "#000000")).isCloseTo(21.0, within(0.01));
	}

	@Test
	void identicalColorsHaveTheMinimumOneToOneRatio() {
		assertThat(WcagContrast.ratio("#FFFFFF", "#FFFFFF")).isCloseTo(1.0, within(0.001));
	}

	@Test
	void twoLightGreysFailTheAaMinimumOfFourPointFive() {
		assertThat(WcagContrast.ratio("#FFFFFF", "#EEEEEE")).isLessThan(4.5);
	}

	@Test
	void blackOnTheKnownSevenSevenSevenGreyPassesTheAaMinimum() {
		// #767676 on white is a commonly-cited AA boundary pair (~4.54:1) -
		// here checked against black, which is comfortably higher, to prove a
		// realistic passing pair rather than only the black/white extreme.
		assertThat(WcagContrast.ratio("#000000", "#767676")).isGreaterThanOrEqualTo(4.5);
	}

}

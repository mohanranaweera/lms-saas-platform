package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Wave 10: the single access-window formula (expiry, newest extension, grace). */
class AccessWindowCalculatorTest {

	private static final Instant EXPIRY = Instant.parse("2026-09-30T00:00:00Z");

	@Test
	void lifetimeAccessHasNoWindowAndIsAlwaysOpen() {
		AccessWindowCalculator.AccessWindow window = AccessWindowCalculator.window(null, null, 5);
		assertThat(window.accessEndsAt()).isNull();
		assertThat(window.isOpen(Instant.MAX)).isTrue();
		assertThat(window.inGracePeriod(Instant.now())).isFalse();
	}

	@Test
	void withoutExtensionOrGraceAccessEndsExactlyAtExpiry() {
		AccessWindowCalculator.AccessWindow window = AccessWindowCalculator.window(EXPIRY, null, 0);
		assertThat(window.isOpen(EXPIRY.minusSeconds(1))).isTrue();
		assertThat(window.isOpen(EXPIRY)).isFalse();
		assertThat(window.extended()).isFalse();
	}

	@Test
	void aLaterExtensionMovesTheEffectiveExpiry() {
		Instant extension = EXPIRY.plus(Duration.ofDays(7));
		AccessWindowCalculator.AccessWindow window = AccessWindowCalculator.window(EXPIRY, extension, 0);
		assertThat(window.effectiveExpiresAt()).isEqualTo(extension);
		assertThat(window.extended()).isTrue();
		assertThat(window.isOpen(EXPIRY.plus(Duration.ofDays(3)))).isTrue();
	}

	@Test
	void anExtensionEarlierThanTheBaseExpiryNeverShortensAccess() {
		AccessWindowCalculator.AccessWindow window = AccessWindowCalculator.window(EXPIRY,
				EXPIRY.minus(Duration.ofDays(1)), 0);
		assertThat(window.effectiveExpiresAt()).isEqualTo(EXPIRY);
		assertThat(window.extended()).isFalse();
	}

	@Test
	void theGracePeriodKeepsAccessOpenAfterTheEffectiveExpiry() {
		AccessWindowCalculator.AccessWindow window = AccessWindowCalculator.window(EXPIRY, null, 3);
		assertThat(window.accessEndsAt()).isEqualTo(EXPIRY.plus(Duration.ofDays(3)));
		Instant inGrace = EXPIRY.plus(Duration.ofDays(1));
		assertThat(window.isOpen(inGrace)).isTrue();
		assertThat(window.inGracePeriod(inGrace)).isTrue();
		assertThat(window.inGracePeriod(EXPIRY.minusSeconds(1))).isFalse();
		assertThat(window.isOpen(EXPIRY.plus(Duration.ofDays(3)))).isFalse();
	}

}

package com.lms.identityaccessservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Plain unit tests (no Spring context - {@link Argon2Properties} is
 * constructed directly) proving the configured bean is genuinely
 * {@code Argon2PasswordEncoder} and that its parameters are config-driven,
 * not hardcoded: two different {@link Argon2Properties} profiles must
 * produce encoded hashes whose {@code m=,t=,p=} parameter segment differs
 * accordingly, not just "some Argon2id hash".
 */
class Argon2PasswordEncoderConfigTest {

	private final Argon2PasswordEncoderConfig config = new Argon2PasswordEncoderConfig();

	@Test
	void beanIsArgon2PasswordEncoderNotBcryptOrPlaintext() {
		PasswordEncoder encoder = config.passwordEncoder(defaultProperties());

		assertThat(encoder).isInstanceOf(Argon2PasswordEncoder.class);

		String encoded = encoder.encode("some-raw-password");
		assertThat(encoded).startsWith("$argon2id$");
	}

	@Test
	void differentConfiguredParametersProduceDifferentEncodedParamSegments() {
		Argon2Properties lowCost = defaultProperties();
		lowCost.setMemoryKib(8192);
		lowCost.setIterations(1);
		lowCost.setParallelism(1);

		Argon2Properties highCost = defaultProperties();
		highCost.setMemoryKib(32768);
		highCost.setIterations(4);
		highCost.setParallelism(2);

		PasswordEncoder lowCostEncoder = config.passwordEncoder(lowCost);
		PasswordEncoder highCostEncoder = config.passwordEncoder(highCost);

		String lowCostHash = lowCostEncoder.encode("same-raw-password");
		String highCostHash = highCostEncoder.encode("same-raw-password");

		String lowCostParams = paramSegment(lowCostHash);
		String highCostParams = paramSegment(highCostHash);

		assertThat(lowCostParams).isEqualTo("m=8192,t=1,p=1");
		assertThat(highCostParams).isEqualTo("m=32768,t=4,p=2");
		assertThat(lowCostParams).isNotEqualTo(highCostParams);
	}

	@Test
	void hashLengthAndSaltLengthAreAlsoConfigDriven() {
		Argon2Properties shortHash = defaultProperties();
		shortHash.setHashLength(16);
		shortHash.setSaltLength(8);

		Argon2Properties longHash = defaultProperties();
		longHash.setHashLength(64);
		longHash.setSaltLength(32);

		PasswordEncoder shortEncoder = config.passwordEncoder(shortHash);
		PasswordEncoder longEncoder = config.passwordEncoder(longHash);

		String shortEncoded = shortEncoder.encode("same-raw-password");
		String longEncoded = longEncoder.encode("same-raw-password");

		// Longer salt+hash length -> longer base64 segments -> longer overall encoded string.
		assertThat(longEncoded.length()).isGreaterThan(shortEncoded.length());
	}

	private static Argon2Properties defaultProperties() {
		return new Argon2Properties();
	}

	/** Extracts the {@code m=...,t=...,p=...} segment from {@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}. */
	private static String paramSegment(String encodedHash) {
		String[] parts = encodedHash.split("\\$");
		// parts[0] = "", [1] = "argon2id", [2] = "v=19", [3] = "m=...,t=...,p=...", [4] = salt, [5] = hash
		return parts[3];
	}

}

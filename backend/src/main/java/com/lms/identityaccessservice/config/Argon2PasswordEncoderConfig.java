package com.lms.identityaccessservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Credential verification is identity-access-service's exclusive concern
 * (AUTH-3) - this bean lives here, not in {@code com.lms.common}. Parameters
 * are entirely config-driven via {@link Argon2Properties}; no bcrypt/
 * plaintext fallback path is registered anywhere in this module.
 */
@Configuration
public class Argon2PasswordEncoderConfig {

	@Bean
	public PasswordEncoder passwordEncoder(Argon2Properties properties) {
		return new Argon2PasswordEncoder(properties.getSaltLength(), properties.getHashLength(),
				properties.getParallelism(), properties.getMemoryKib(), properties.getIterations());
	}

}

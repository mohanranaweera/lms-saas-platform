package com.lms.identityaccessservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Argon2id parameters for {@code Argon2PasswordEncoder}, bound from
 * configuration rather than hardcoded (AUTH-3). Defaults match Spring
 * Security's own OWASP-baseline {@code defaultsForSpringSecurity_v5_8()}
 * profile (16-byte salt, 32-byte hash, parallelism 1, 19 MiB memory, 2
 * iterations) - overridable per environment via env vars in {@code
 * application-local.yml}.
 */
@Component
@ConfigurationProperties(prefix = "app.security.argon2")
public class Argon2Properties {

	private int saltLength = 16;

	private int hashLength = 32;

	private int parallelism = 1;

	private int memoryKib = 19456;

	private int iterations = 2;

	public int getSaltLength() {
		return saltLength;
	}

	public void setSaltLength(int saltLength) {
		this.saltLength = saltLength;
	}

	public int getHashLength() {
		return hashLength;
	}

	public void setHashLength(int hashLength) {
		this.hashLength = hashLength;
	}

	public int getParallelism() {
		return parallelism;
	}

	public void setParallelism(int parallelism) {
		this.parallelism = parallelism;
	}

	public int getMemoryKib() {
		return memoryKib;
	}

	public void setMemoryKib(int memoryKib) {
		this.memoryKib = memoryKib;
	}

	public int getIterations() {
		return iterations;
	}

	public void setIterations(int iterations) {
		this.iterations = iterations;
	}

}

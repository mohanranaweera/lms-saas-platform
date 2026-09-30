package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** TEN-DOM-01: hostname parsing, validation and origin building. */
class HostnamesTest {

	@Test
	void requestHostDropsPortCaseAndTrailingDot() {
		assertThat(Hostnames.requestHost("Learn.ABC.lk:8443")).isEqualTo("learn.abc.lk");
		assertThat(Hostnames.requestHost("demo.lms.test.")).isEqualTo("demo.lms.test");
		assertThat(Hostnames.requestHost("localhost")).isEqualTo("localhost");
	}

	@Test
	void requestHostIsNullForBlankOrIpv6Literal() {
		assertThat(Hostnames.requestHost(null)).isNull();
		assertThat(Hostnames.requestHost("  ")).isNull();
		assertThat(Hostnames.requestHost("[::1]:8080")).isNull();
	}

	@Test
	void acceptsOrdinaryDomains() {
		assertThat(Hostnames.problem("learn.abcinstitute.lk", "lms.test")).isEmpty();
		assertThat(Hostnames.problem("abcinstitute.com", "lms.test")).isEmpty();
		assertThat(Hostnames.problem("xn--bcher-kva.example", "lms.test")).isEmpty();
	}

	@Test
	void rejectsNamesThatAreNotPlainDomains() {
		assertThat(Hostnames.problem("", "lms.test")).isPresent();
		assertThat(Hostnames.problem("intranet", "lms.test")).isPresent();
		assertThat(Hostnames.problem(Hostnames.normalise("https://learn.abc.lk"), "lms.test")).isPresent();
		assertThat(Hostnames.problem("learn.abc.lk/path", "lms.test")).isPresent();
		assertThat(Hostnames.problem("-bad.abc.lk", "lms.test")).isPresent();
		assertThat(Hostnames.problem("under_score.abc.lk", "lms.test")).isPresent();
		assertThat(Hostnames.problem("127.0.0.1", "lms.test")).isPresent();
		assertThat(Hostnames.problem("a..b.lk", "lms.test")).isPresent();
		assertThat(Hostnames.problem("x".repeat(64) + ".lk", "lms.test")).isPresent();
	}

	@Test
	void rejectsLocalhostAndThePlatformsOwnDomain() {
		assertThat(Hostnames.problem("localhost", "lms.test")).isPresent();
		assertThat(Hostnames.problem("app.localhost", "lms.test")).isPresent();
		assertThat(Hostnames.problem("lms.test", "lms.test")).isPresent();
		assertThat(Hostnames.problem("demo.lms.test", "lms.test")).isPresent();
		assertThat(Hostnames.problem("a.demo.lms.test", "lms.test")).isPresent();
		// Only a real suffix match counts.
		assertThat(Hostnames.problem("notlms.test", "lms.test")).isEmpty();
	}

	@Test
	void normaliseTrimsLowercasesAndDropsTrailingDot() {
		assertThat(Hostnames.normalise("  Learn.ABC.lk. ")).isEqualTo("learn.abc.lk");
		assertThat(Hostnames.normalise(null)).isEmpty();
	}

	@Test
	void withHostKeepsSchemePortAndPath() {
		assertThat(Hostnames.withHost("http://{subdomain}.lms.test:3000", "learn.abc.test"))
			.isEqualTo("http://learn.abc.test:3000");
		assertThat(Hostnames.withHost("https://{subdomain}.example.com", "learn.abc.lk"))
			.isEqualTo("https://learn.abc.lk");
		assertThat(Hostnames.withHost("https://{subdomain}.example.com/app", "learn.abc.lk"))
			.isEqualTo("https://learn.abc.lk/app");
	}

}

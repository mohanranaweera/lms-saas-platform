package com.lms.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class CorrelationIdFilterIntegrationTest extends AbstractIntegrationTest {

	@Test
	void generatesACorrelationIdWhenClientSendsNone() {
		ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

		assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
	}

	@Test
	void echoesBackAClientSuppliedCorrelationId() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Correlation-Id", "test-correlation-id-123");

		ResponseEntity<String> response = restTemplate.exchange("/actuator/health", HttpMethod.GET,
				new HttpEntity<>(headers), String.class);

		assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("test-correlation-id-123");
	}

}

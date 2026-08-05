package com.lms.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OpenApiIntegrationTest extends AbstractIntegrationTest {

	@Test
	void apiDocsEndpointIsReachableAndValid() {
		ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"openapi\"").contains("LMS SaaS Platform API");
	}

}

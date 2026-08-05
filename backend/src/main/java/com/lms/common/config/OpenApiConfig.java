package com.lms.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi auto-exposes /v3/api-docs and /swagger-ui.html once this
 * dependency is on the classpath - this class only supplies metadata. This
 * is a supplement to, not a replacement for, the hand-written
 * docs/api/&lt;domain&gt;.md contract files (see docs/api/README.md), which
 * remain the contract of record. Whether this stays enabled outside the
 * local/dev profile is an open decision to make before the first real
 * domain endpoint ships (see security review, Application Foundation plan).
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI lmsOpenApi() {
		return new OpenAPI().info(new Info().title("LMS SaaS Platform API")
			.description("Multi-tenant SaaS LMS and Institute Management System API")
			.version("v1"));
	}

}

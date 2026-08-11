package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.common.AbstractIntegrationTest;
import com.lms.tenantmanagement.web.dto.TenantRegistrationRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Testcontainers-backed coverage of {@code TEN-1}'s public registration
 * endpoint, {@code POST /api/v1/tenant-registrations}, through the real
 * Spring MVC + Spring Security + {@code GlobalExceptionHandler} stack
 * ({@code SecurityConfig} permits this one endpoint anonymously - see that
 * class's own doc comment).
 *
 * <p>Not {@code @Transactional}: each request goes through the embedded HTTP
 * server on a connection/thread separate from the test method's own thread,
 * so a test-level transaction would not roll back what the server committed.
 * Every test therefore uses its own randomly-suffixed subdomain instead of
 * relying on rollback for isolation between test methods.
 */
class TenantRegistrationIntegrationTest extends AbstractIntegrationTest {

	private static final String REGISTRATION_PATH = "/api/v1/tenant-registrations";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void registeringATenantCreatesAPendingApprovalRowWithSubmittedProfileData() throws Exception {
		String subdomain = uniqueSubdomain("reg");

		ResponseEntity<String> response = restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
				validRequestEntity(subdomain), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		JsonNode body = MAPPER.readTree(response.getBody());
		assertThat(body.path("success").asBoolean()).isTrue();
		assertThat(body.path("data").path("subdomain").asText()).isEqualTo(subdomain);
		assertThat(body.path("data").path("status").asText()).isEqualTo("pending_approval");
		UUID id = UUID.fromString(body.path("data").path("id").asText());

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT name, subdomain, status, requested_plan, "
				+ "contact_name, contact_email, contact_phone FROM tenant WHERE id = ?", id);
		assertThat(row.get("name")).isEqualTo("Test Institute " + subdomain);
		assertThat(row.get("subdomain")).isEqualTo(subdomain);
		assertThat(row.get("status")).isEqualTo("pending_approval");
		assertThat(row.get("requested_plan")).isEqualTo("starter");
		assertThat(row.get("contact_name")).isEqualTo("Jane Doe");
		assertThat(row.get("contact_email")).isEqualTo("contact-" + subdomain + "@example.test");
		assertThat(row.get("contact_phone")).isEqualTo("+1-555-0100");
	}

	@Test
	void duplicateRegistrationViaApiIsRejectedNotDuplicated() throws Exception {
		String subdomain = uniqueSubdomain("dup");

		ResponseEntity<String> first = restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
				validRequestEntity(subdomain), String.class);
		assertThat(first.getStatusCode().value()).isEqualTo(201);

		ResponseEntity<String> second = restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
				validRequestEntity(subdomain), String.class);
		assertThat(second.getStatusCode().value()).isEqualTo(409);
		JsonNode body = MAPPER.readTree(second.getBody());
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("error").path("code").asText()).isEqualTo("CONFLICT");

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant WHERE subdomain = ?", Long.class,
				subdomain);
		assertThat(count).isEqualTo(1L);
	}

	/**
	 * Mandatory (plan SS15 item 2 / SS18): subdomain uniqueness must be
	 * closed at the DB constraint level, not just a check-then-insert
	 * application check - a pre-check-then-insert without the constraint is
	 * a TOCTOU race. Proven here with two genuinely concurrent registrations
	 * racing for the same subdomain through the real endpoint: exactly one
	 * must succeed (201) and the other must fail cleanly (409), never both
	 * succeeding and never a 500.
	 */
	@Test
	void concurrentRegistrationsForTheSameSubdomainInsertExactlyOneRow() throws Exception {
		String subdomain = uniqueSubdomain("race");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String contactEmail = "racer-" + i + "-" + subdomain + "@example.test";
				futures.add(executor.submit(() -> {
					barrier.await();
					return restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
							validRequestEntity(subdomain, contactEmail), String.class);
				}));
			}

			List<ResponseEntity<String>> responses = new ArrayList<>();
			for (Future<ResponseEntity<String>> future : futures) {
				responses.add(future.get(15, TimeUnit.SECONDS));
			}

			responses.forEach(r -> assertThat(r.getStatusCode().value()).isNotEqualTo(500));
			long created = responses.stream().filter(r -> r.getStatusCode().value() == 201).count();
			long conflicted = responses.stream().filter(r -> r.getStatusCode().value() == 409).count();
			assertThat(created).isEqualTo(1);
			assertThat(conflicted).isEqualTo(1);

			Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant WHERE subdomain = ?", Long.class,
					subdomain);
			assertThat(count).isEqualTo(1L);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void registrationEndpointIgnoresClientInjectedIdStatusAndTenantIdFields() throws Exception {
		String subdomain = uniqueSubdomain("inj");
		String injectedId = UUID.randomUUID().toString();
		String rawBody = """
				{
				  "id": "%s",
				  "tenantId": "%s",
				  "status": "active",
				  "approved": true,
				  "name": "Injected Institute",
				  "subdomain": "%s",
				  "requestedPlan": "starter",
				  "contactName": "Jane Doe",
				  "contactEmail": "inject-%s@example.test",
				  "contactPhone": "+1-555-0100"
				}
				""".formatted(injectedId, injectedId, subdomain, subdomain);

		ResponseEntity<String> response = restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
				rawJsonEntity(rawBody), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		JsonNode body = MAPPER.readTree(response.getBody());
		String serverGeneratedId = body.path("data").path("id").asText();
		assertThat(serverGeneratedId).isNotBlank();
		assertThat(serverGeneratedId).isNotEqualTo(injectedId);
		assertThat(body.path("data").path("status").asText()).isEqualTo("pending_approval");

		String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				UUID.fromString(serverGeneratedId));
		assertThat(dbStatus).isEqualTo("pending_approval");
	}

	@Test
	void invalidRegistrationPayloadReturns400WithFieldLevelValidationErrors() throws Exception {
		// Blank name, malformed subdomain, and a missing contact email in a
		// single payload - each must surface as its own field-level error.
		String rawBody = """
				{
				  "name": "",
				  "subdomain": "Not Valid Subdomain!",
				  "requestedPlan": "starter",
				  "contactName": "Jane Doe",
				  "contactPhone": "+1-555-0100"
				}
				""";

		ResponseEntity<String> response = restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
				rawJsonEntity(rawBody), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		JsonNode body = MAPPER.readTree(response.getBody());
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");

		List<String> fieldsWithErrors = new ArrayList<>();
		body.path("error").path("fieldErrors").forEach(fe -> fieldsWithErrors.add(fe.path("field").asText()));
		assertThat(fieldsWithErrors).contains("name", "subdomain", "contactEmail");
	}

	@Test
	void reservedSubdomainIsRejectedAtTheRealEndpoint() throws Exception {
		ResponseEntity<String> response = restTemplate.exchange(REGISTRATION_PATH, HttpMethod.POST,
				validRequestEntity("admin"), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		JsonNode body = MAPPER.readTree(response.getBody());
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("error").path("code").asText()).isEqualTo("VALIDATION_ERROR");

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant WHERE subdomain = ?", Long.class,
				"admin");
		assertThat(count).isZero();
	}

	private HttpEntity<TenantRegistrationRequest> validRequestEntity(String subdomain) {
		return validRequestEntity(subdomain, "contact-" + subdomain + "@example.test");
	}

	private HttpEntity<TenantRegistrationRequest> validRequestEntity(String subdomain, String contactEmail) {
		TenantRegistrationRequest requestBody = new TenantRegistrationRequest("Test Institute " + subdomain,
				subdomain, "starter", "Jane Doe", contactEmail, "+1-555-0100");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(requestBody, headers);
	}

	private HttpEntity<String> rawJsonEntity(String rawBody) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(rawBody, headers);
	}

	private static String uniqueSubdomain(String prefix) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		return (prefix + "-" + suffix).toLowerCase(Locale.ROOT);
	}

}

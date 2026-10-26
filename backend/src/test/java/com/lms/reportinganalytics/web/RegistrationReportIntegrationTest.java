package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.identityaccessservice.domain.TenantUser;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Registration date, tenant isolation and snapshot coverage for RPT-07. */
@Tag("cross-tenant")
class RegistrationReportIntegrationTest extends FinanceTestSupport {

	private static final String BASE = "/api/v1/reports/registrations";

	@Test
	void allCoursesUsesTenantProfileRegistrationTimeAndIncludesNoEnrollmentOrPayment() throws Exception {
		FinanceTenant tenant = seedFinanceTenant("registration-dates");
		UUID tenantId = tenant.tenant().getId();
		MvcResult timezone = perform(authenticated(put("/api/v1/tenant-config/GENERAL")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("default_timezone", "Asia/Colombo"))), tenant.host(), tenant.adminToken()));
		assertThat(timezone.getResponse().getStatus()).isEqualTo(200);
		TenantUser before = seedActiveStudent(tenantId, "before@example.test");
		TenantUser first = seedActiveStudent(tenantId, "first@example.test");
		TenantUser last = seedActiveStudent(tenantId, "last@example.test");
		TenantUser after = seedActiveStudent(tenantId, "after@example.test");
		UUID actor = jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'finance@example.test'", UUID.class, tenantId);
		insertProfile(tenantId, before, "Before", "2026-09-30T18:29:59Z", actor);
		UUID firstProfile = insertProfile(tenantId, first, "Same Name", "2026-09-30T18:30:00Z", actor);
		UUID lastProfile = insertProfile(tenantId, last, "Same Name", "2026-10-01T18:29:59Z", actor);
		insertProfile(tenantId, after, "After", "2026-10-01T18:30:00Z", actor);

		JsonNode report = create(tenant, body("2026-10-01", "2026-10-01"));
		assertThat(report.path("totalRegistrations").asInt()).isEqualTo(2);
		assertThat(report.path("filters").path("selectedDayCount").asInt()).isEqualTo(1);
		assertThat(report.path("filters").path("courses")).isEmpty();
		JsonNode rows = rows(tenant, report.path("runId").asText());
		assertThat(rows.path("totalRows").asInt()).isEqualTo(2);
		assertThat(rows.path("rows")).extracting(node -> node.path("studentProfileId").asText())
			.containsExactly(firstProfile.toString(), lastProfile.toString());
		assertThat(rows.path("rows")).allSatisfy(row -> {
			assertThat(row.path("courses")).isEmpty();
			assertThat(row.path("lastPayment").isNull()).isTrue();
			assertThat(row.path("studentName").asText()).isEqualTo("Same Name");
		});
	}

	@Test
	void pdfContainsVisibleReportTitle() throws Exception {
		FinanceTenant tenant = seedFinanceTenant("registration-pdf-title");
		JsonNode report = create(tenant, body("2026-10-01", "2026-10-31"));
		MvcResult response = perform(authenticated(get(BASE + "/runs/" + report.path("runId").asText() + "/pdf"), tenant.host(), tenant.financeToken()));
		assertThat(response.getResponse().getStatus()).isEqualTo(200);
		PdfReader reader = new PdfReader(response.getResponse().getContentAsByteArray());
		try {
			assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains("Registration Report");
		}
		finally {
			reader.close();
		}
	}

	@Test
	void runIsInvisibleAcrossTenantsAndOptionsExposeRealMethodLabelsSourceValues() throws Exception {
		FinanceTenant a = seedFinanceTenant("registration-a");
		FinanceTenant b = seedFinanceTenant("registration-b");
		JsonNode report = create(a, body("2026-10-01", "2026-10-01"));
		String runId = report.path("runId").asText();
		assertThat(status(b, get(BASE + "/runs/" + runId))).isEqualTo(404);
		JsonNode options = json(a, get(BASE + "/options"));
		assertThat(options.path("registrationMethods").toString())
			.isEqualTo("[\"SELF_REGISTERED\",\"ADMIN_CREATED\"]");
	}

	private UUID insertProfile(UUID tenantId, TenantUser user, String name, String createdAt, UUID actor) {
		UUID profileId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at, created_by, registration_status) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'ADMIN_CREATED')", profileId, tenantId, user.getId(), name,
				Timestamp.from(Instant.parse(createdAt)), Timestamp.from(Instant.parse(createdAt)), actor);
		return profileId;
	}

	private JsonNode create(FinanceTenant tenant, Map<String, Object> body) throws Exception {
		MvcResult result = perform(authenticated(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), tenant.host(), tenant.financeToken()));
		assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
	}

	private JsonNode rows(FinanceTenant tenant, String runId) throws Exception {
		MvcResult result = perform(authenticated(get(BASE + "/runs/" + runId + "/rows?page=0&size=50"), tenant.host(), tenant.financeToken()));
		assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
	}

	private JsonNode json(FinanceTenant tenant, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = perform(authenticated(request, tenant.host(), tenant.financeToken()));
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
	}

	private int status(FinanceTenant tenant, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		return perform(authenticated(request, tenant.host(), tenant.financeToken())).getResponse().getStatus();
	}

	private static Map<String, Object> body(String startDate, String endDate) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseIds", java.util.List.of());
		body.put("startDate", startDate);
		body.put("endDate", endDate);
		return body;
	}
}

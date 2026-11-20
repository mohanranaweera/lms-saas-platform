package com.lms.reportinganalytics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.reportinganalytics.config.UnpaidStudentsReportProperties;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP security, owner-bound snapshot and immutable-read checks for RPT-06. */
@Tag("cross-tenant")
class UnpaidStudentsReportIntegrationTest extends FinanceTestSupport {

	private static final String BASE = "/api/v1/reports/unpaid-students";

	@Autowired
	private UnpaidStudentsReportProperties properties;

	@Test
	void crossTenantAndSameTenantOtherUserCannotReadRunOrExport() throws Exception {
		FinanceTenant a = seedFinanceTenant("unpaid-a");
		FinanceTenant b = seedFinanceTenant("unpaid-b");
		UUID course = createPricedCourse(a, "unpaid-course", a.teacher().getId(), "1000.00").id();
		MvcResult created = call(a.host(), a.financeToken(), post(BASE + "/runs"), body(course));
		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		String runId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("runId").asString();
		for (String path : new String[] { BASE + "/runs/" + runId, BASE + "/runs/" + runId + "/rows", BASE + "/runs/" + runId + "/pdf" }) {
			assertThat(status(b.host(), b.financeToken(), get(path))).as(path).isEqualTo(404);
			assertThat(status(a.host(), a.auditorToken(), get(path))).as(path).isEqualTo(404);
		}
		assertThat(status(b.host(), b.financeToken(), post(BASE + "/runs"), body(course))).isEqualTo(422);
	}

	@Test
	void permissionsAreRequiredBeforeValidationAndResponsesAreNoStore() throws Exception {
		FinanceTenant tenant = seedFinanceTenant("unpaid-perm");
		UUID course = createPricedCourse(tenant, "unpaid-course", tenant.teacher().getId(), "1000.00").id();
		for (String token : new String[] { tenant.contentToken(), tenant.studentToken() }) {
			assertThat(status(tenant.host(), token, get(BASE + "/options"))).isEqualTo(403);
			assertThat(status(tenant.host(), token, post(BASE + "/runs"), Map.of("billingMonth", "bad"))).isEqualTo(403);
			assertThat(status(tenant.host(), token, get(BASE + "/runs/not-a-uuid/pdf"))).isEqualTo(403);
		}
		MvcResult options = perform(authenticated(get(BASE + "/options"), tenant.host(), tenant.financeToken()));
		assertThat(options.getResponse().getStatus()).isEqualTo(200);
		assertThat(options.getResponse().getHeader("Cache-Control")).contains("no-store");
		MvcResult created = call(tenant.host(), tenant.financeToken(), post(BASE + "/runs"), body(course));
		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

	@Test
	void executionLeavesBusinessTablesUnchanged() throws Exception {
		FinanceTenant tenant = seedFinanceTenant("unpaid-readonly");
		UUID course = createPricedCourse(tenant, "unpaid-course", tenant.teacher().getId(), "1000.00").id();
		String[] tables = { "payment", "ledger_entry", "student_order", "student_order_line", "course_participation", "content_entitlement", "enrollment", "attendance_record", "free_card" };
		long[] before = new long[tables.length];
		for (int i = 0; i < tables.length; i++) before[i] = count(tables[i], tenant.tenant().getId());
		MvcResult created = call(tenant.host(), tenant.financeToken(), post(BASE + "/runs"), body(course));
		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		String runId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("runId").asString();
		assertThat(status(tenant.host(), tenant.financeToken(), get(BASE + "/runs/" + runId + "/rows"))).isEqualTo(200);
		assertThat(status(tenant.host(), tenant.financeToken(), get(BASE + "/runs/" + runId + "/pdf"))).isEqualTo(200);
		for (int i = 0; i < tables.length; i++) assertThat(count(tables[i], tenant.tenant().getId())).isEqualTo(before[i]);
	}

	private MvcResult call(String host, String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			Map<String, Object> body) throws Exception {
		return perform(authenticated(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), host, token));
	}

	private int status(String host, String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
		return perform(authenticated(request, host, token)).getResponse().getStatus();
	}

	private int status(String host, String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			Map<String, Object> body) throws Exception {
		return call(host, token, request, body).getResponse().getStatus();
	}

	private static Map<String, Object> body(UUID courseId) {
		return Map.of("courseId", courseId, "billingMonth", "2026-10", "atLeastOneAttended", false);
	}

	private long count(String table, UUID tenantId) {
		Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenantId);
		return value == null ? 0 : value;
	}

}

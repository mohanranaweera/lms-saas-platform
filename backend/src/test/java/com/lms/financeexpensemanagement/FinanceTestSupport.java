package com.lms.financeexpensemanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.service.ExpenseCategoryService.CategoryView;
import com.lms.financeexpensemanagement.service.ExpenseService.ExpenseView;
import com.lms.financeexpensemanagement.web.dto.AttachmentUrlResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.SlipTestSupport;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Wave 7 shared fixtures for finance/expense/report/settlement tests. Extends
 * {@link SlipTestSupport} for its multipart plumbing, PDF/PNG byte helpers and
 * the {@code @Primary} in-memory object store. Real, FK-satisfying ledger rows
 * are produced the same way every other ledger test does (order -> initiate ->
 * signed webhook); {@link #backdateConfirmedEntry}/{@link #backdateRefundEntry}
 * then move a row's {@code created_at} into a fixed, closed past period so
 * report/settlement assertions are deterministic (test-only fixture step -
 * no production code path updates a ledger row).
 */
public abstract class FinanceTestSupport extends SlipTestSupport {

	protected FinanceTenant seedFinanceTenant(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		seedTenantUser(tenant.getId(), "content@example.test", RAW_PASSWORD, Role.CONTENT_MANAGER);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacher2 = seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		return new FinanceTenant(tenant, host, loginAndGetToken(host, "admin@example.test"),
				loginAndGetToken(host, "finance@example.test"), loginAndGetToken(host, "auditor@example.test"),
				loginAndGetToken(host, "content@example.test"), loginAndGetToken(host, "teacher@example.test"),
				loginAndGetToken(host, "student@example.test"), teacher, teacher2);
	}

	protected record FinanceTenant(Tenant tenant, String host, String adminToken, String financeToken,
			String auditorToken, String contentToken, String teacherToken, String studentToken, TenantUser teacher,
			TenantUser teacher2) {
	}

	// ------------------------------------------------------------------
	// Ledger fixtures.
	// ------------------------------------------------------------------

	protected CourseResponse createPricedCourse(FinanceTenant t, String prefix, UUID teacherId, String price) {
		String slug = uniqueSlug(prefix);
		return createCourseOrFail(t.host(), t.adminToken(), new CourseCreateRequest("Course " + slug, slug,
				"Mathematics", null, null, null, null, null, new BigDecimal(price), null, null, CourseStatus.PUBLIC,
				teacherId));
	}

	/** A fresh student buys {@code courseId}; returns the confirmed payment id. */
	protected UUID confirmedPayment(FinanceTenant t, UUID courseId) {
		String email = "buyer-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		seedActiveStudent(t.tenant().getId(), email);
		String token = loginAndGetToken(t.host(), email);
		OrderResponse order = createOrderOrFail(t.host(), token, courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(t.host(), token, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (!webhook.getStatusCode().is2xxSuccessful()) {
			throw new IllegalStateException("Webhook failed: " + webhook.getStatusCode());
		}
		return initiation.paymentId();
	}

	protected void refund(FinanceTenant t, UUID paymentId, String amount) {
		var result = createRefund(t.host(), t.adminToken(), paymentId, new BigDecimal(amount), "Fixture refund");
		if (!result.getStatusCode().is2xxSuccessful()) {
			throw new IllegalStateException("Refund failed: " + result.getStatusCode() + " " + result.getBody());
		}
	}

	protected void backdateConfirmedEntry(UUID paymentId, Instant at) {
		jdbcTemplate.update(
				"UPDATE ledger_entry SET created_at = ? WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				Timestamp.from(at), paymentId);
	}

	protected void backdateRefundEntry(UUID paymentId, Instant at) {
		jdbcTemplate.update("UPDATE ledger_entry SET created_at = ? WHERE payment_id = ? AND entry_type = 'REFUND'",
				Timestamp.from(at), paymentId);
	}

	protected static Instant utc(String isoDateTime) {
		return Instant.parse(isoDateTime);
	}

	// ------------------------------------------------------------------
	// Category/expense endpoints.
	// ------------------------------------------------------------------

	protected HttpResult<CategoryView> createCategory(String host, String token, String name) {
		MockHttpServletRequestBuilder builder = post("/api/v1/finance/expense-categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", name)));
		return parseSingle(perform(authenticated(builder, host, token)), CategoryView.class);
	}

	protected CategoryView createCategoryOrFail(String host, String token, String name) {
		HttpResult<CategoryView> result = createCategory(host, token, name);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Category creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	protected HttpResult<List<CategoryView>> listCategories(String host, String token, boolean includeArchived) {
		MockHttpServletRequestBuilder builder = get("/api/v1/finance/expense-categories")
			.param("includeArchived", String.valueOf(includeArchived));
		return parseList(perform(authenticated(builder, host, token)), CategoryView.class);
	}

	protected HttpResult<CategoryView> archiveCategory(String host, String token, UUID categoryId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/finance/expense-categories/{id}/archive", categoryId);
		return parseSingle(perform(authenticated(builder, host, token)), CategoryView.class);
	}

	protected HttpResult<ExpenseView> createExpense(String host, String token, UUID categoryId, LocalDate date,
			String amount, String method, MockMultipartFile attachment) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/api/v1/finance/expenses");
		if (attachment != null) {
			builder.file(attachment);
		}
		if (categoryId != null) {
			builder.param("categoryId", categoryId.toString());
		}
		if (date != null) {
			builder.param("expenseDate", date.toString());
		}
		if (amount != null) {
			builder.param("amount", amount);
		}
		if (method != null) {
			builder.param("method", method);
		}
		builder.param("description", "Fixture expense").param("reference", "REF-1");
		return parseSingle(performMultipart(authenticatedMultipart(builder, host, token)), ExpenseView.class);
	}

	protected ExpenseView createExpenseOrFail(String host, String token, UUID categoryId, LocalDate date,
			String amount) {
		HttpResult<ExpenseView> result = createExpense(host, token, categoryId, date, amount, "CASH", null);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Expense creation failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	protected static MockMultipartFile receipt(byte[] bytes) {
		return new MockMultipartFile("attachment", "receipt.pdf", "application/pdf", bytes);
	}

	protected HttpResult<ExpenseView> getExpense(String host, String token, UUID expenseId) {
		return parseSingle(perform(authenticated(get("/api/v1/finance/expenses/{id}", expenseId), host, token)),
				ExpenseView.class);
	}

	protected HttpResult<PageResponse<ExpenseView>> listExpenses(String host, String token, String query) {
		MockHttpServletRequestBuilder builder = get("/api/v1/finance/expenses" + (query == null ? "" : "?" + query));
		return parsePage(perform(authenticated(builder, host, token)), ExpenseView.class);
	}

	protected HttpResult<ExpenseView> voidExpense(String host, String token, UUID expenseId, String reason) {
		MockHttpServletRequestBuilder builder = post("/api/v1/finance/expenses/{id}/void", expenseId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(reason == null ? "{}" : objectMapper.writeValueAsString(Map.of("reason", reason)));
		return parseSingle(perform(authenticated(builder, host, token)), ExpenseView.class);
	}

	protected HttpResult<AttachmentUrlResponse> attachmentUrl(String host, String token, UUID expenseId) {
		return parseSingle(perform(authenticated(get("/api/v1/finance/expenses/{id}/attachment-url", expenseId),
				host, token)), AttachmentUrlResponse.class);
	}

	protected <T> HttpResult<T> getReport(String host, String token, String path, String query, Class<T> type) {
		MockHttpServletRequestBuilder builder = get("/api/v1/finance/reports/" + path + "?" + query);
		return parseSingle(perform(authenticated(builder, host, token)), type);
	}

}

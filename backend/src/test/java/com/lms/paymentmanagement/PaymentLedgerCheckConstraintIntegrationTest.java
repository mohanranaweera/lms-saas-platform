package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Closes plan §18 Testcontainers item 7: {@code ck_payment_status} and {@code
 * ck_ledger_entry_type} (V19) are schema-enforced, not just validated by the
 * Java-layer enums - a raw, out-of-enum insert is rejected by Postgres
 * itself, bypassing the JPA/Hibernate/enum layer entirely.
 */
class PaymentLedgerCheckConstraintIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void aRawInsertWithAnOutOfEnumPaymentStatusIsRejectedByTheDatabase() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-check-status"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-check-status"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		UUID bogusPaymentId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, created_at, updated_at) "
						+ "VALUES (?, ?, ?, ?, 'USD', 'NOT_A_REAL_STATUS', now(), now())",
				bogusPaymentId, tenant.getId(), order.id(), order.amount()))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE id = ?", Long.class,
				bogusPaymentId);
		org.assertj.core.api.Assertions.assertThat(count).isEqualTo(0L);
	}

	@Test
	void aRawInsertWithAPaymentAmountOfZeroIsRejectedByTheDatabase() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-check-amount"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-check-amount"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		UUID bogusPaymentId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, created_at, updated_at) "
						+ "VALUES (?, ?, ?, 0, 'USD', 'PENDING', now(), now())",
				bogusPaymentId, tenant.getId(), order.id())).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aRawInsertWithAnOutOfEnumLedgerEntryTypeIsRejectedByTheDatabase() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-check-entrytype"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-check-entrytype"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);
		UUID bogusEntryId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO ledger_entry (id, tenant_id, order_id, payment_id, entry_type, amount, created_at) "
						+ "VALUES (?, ?, ?, ?, 'NOT_A_REAL_ENTRY_TYPE', 10.00, now())",
				bogusEntryId, tenant.getId(), order.id(), initiation.paymentId()))
			.isInstanceOf(DataIntegrityViolationException.class);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE id = ?", Long.class,
				bogusEntryId);
		org.assertj.core.api.Assertions.assertThat(count).isEqualTo(0L);
	}

	@Test
	void aRawInsertOfALedgerEntryWithNoOrderIdIsRejectedByTheDatabase() {
		// Ledger orphan-prevention (plan §18 item 8): order_id is NOT NULL
		// with an FK to student_order - an entry with no order/payment
		// traceability must be rejected at the schema level.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-check-orphan"));
		UUID bogusEntryId = UUID.randomUUID();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO ledger_entry (id, tenant_id, order_id, entry_type, amount, created_at) "
						+ "VALUES (?, ?, NULL, 'PAYMENT_CONFIRMED', 10.00, now())",
				bogusEntryId, tenant.getId())).isInstanceOf(DataIntegrityViolationException.class);
	}

}

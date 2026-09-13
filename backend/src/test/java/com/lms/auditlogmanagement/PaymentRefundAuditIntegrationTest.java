package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.RefundResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;

/**
 * Proves {@link com.lms.auditlogmanagement.service.AuditLogEventListener
 * #onPaymentRefunded} writes exactly one {@code audit_log} row per refund,
 * with the fields plan §7/§9 require. Mirrors {@code
 * RefundImmutabilityIntegrationTest}'s exact course/order/payment/webhook
 * setup.
 */
class PaymentRefundAuditIntegrationTest extends AuditLogManagementTestSupport {

	@Test
	void refundingAPaymentWritesExactlyOneAuditLogRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-refund"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("audit-refund"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		HttpResult<RefundResponse> refundResult = createRefund(host, adminToken, initiation.paymentId(),
				new BigDecimal("15.00"), "Student requested a partial refund");
		assertThat(refundResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		RefundResponse refund = refundResult.getBody().data();

		var rows = jdbcTemplate.queryForList(
				"SELECT tenant_id, actor_id, action, target_entity, target_id, reason, occurred_at, "
						+ "metadata::text AS metadata FROM audit_log "
						+ "WHERE action = 'payment.refunded' AND target_id = ?",
				refund.id());
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);
		assertThat(row.get("tenant_id")).isEqualTo(tenant.getId());
		assertThat(row.get("actor_id")).isEqualTo(admin.getId());
		assertThat(row.get("action")).isEqualTo("payment.refunded");
		assertThat(row.get("target_entity")).isEqualTo("payment_refund");
		assertThat(row.get("target_id")).isEqualTo(refund.id());
		assertThat(row.get("occurred_at")).isNotNull();

		// payment.refunded is the one listener that DOES carry a reason
		// (the refund's own reason, verbatim) alongside the paymentId/amount
		// metadata payload (plan §7/§9's actual point).
		assertThat(row.get("reason")).isEqualTo("Student requested a partial refund");
		Map<String, Object> metadata = objectMapper.readValue((String) row.get("metadata"),
				new TypeReference<Map<String, Object>>() {
				});
		assertThat(metadata.get("paymentId")).isEqualTo(initiation.paymentId().toString());
		assertThat(new BigDecimal(metadata.get("amount").toString())).isEqualByComparingTo(new BigDecimal("15.00"));
	}

}

package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Mirrors {@code PaymentConfirmationRollbackIntegrationTest}'s exact
 * technique: a real Spring context/transaction manager/Testcontainers
 * Postgres, with only {@link AuditLogApi} replaced by a throwing mock.
 * {@link com.lms.auditlogmanagement.service.AuditLogEventListener
 * #onCoursePriceChanged} is a plain (non-transactional-event) {@code
 * @EventListener}, so it runs synchronously inside {@code
 * CourseService#changePrice}'s own transaction - forcing it to throw must
 * roll back the price change too, per that class's own javadoc.
 */
class CoursePriceChangeAuditListenerRollbackIntegrationTest extends AuditLogManagementTestSupport {

	@MockitoBean
	private AuditLogApi auditLogApi;

	@Test
	void aFailureInTheAuditWriteRollsBackTheCoursePriceChange() {
		doThrow(new RuntimeException("Simulated audit log write failure")).when(auditLogApi).record(any());

		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-price-rollback"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("audit-price-rollback"), teacher.getId(), CourseStatus.PUBLIC));
		BigDecimal originalPrice = course.price();

		var result = changePrice(host, adminToken, course.id(), new BigDecimal("149.99"));

		// 500 is the expected fallback here, not an unhandled-error smell to
		// "fix" later: the simulated RuntimeException is thrown deep inside
		// the listener, and no handler exists for a raw RuntimeException -
		// GlobalExceptionHandler#handleUnexpected catches it generically.
		// The behavior under test is the transaction rollback asserted below,
		// not the specific status code.
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		BigDecimal persistedPrice = jdbcTemplate.queryForObject("SELECT price FROM course WHERE id = ?",
				BigDecimal.class, course.id());
		assertThat(persistedPrice).isEqualByComparingTo(originalPrice);

		Long auditRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'course.price_changed' AND target_id = ?", Long.class,
				course.id());
		assertThat(auditRowCount).isEqualTo(0L);
	}

}

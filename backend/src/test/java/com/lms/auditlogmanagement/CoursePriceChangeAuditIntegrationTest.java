package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;

/**
 * Proves {@link com.lms.auditlogmanagement.service.AuditLogEventListener
 * #onCoursePriceChanged} writes exactly one {@code audit_log} row per
 * price-change call, with the fields plan §7/§9 require.
 */
class CoursePriceChangeAuditIntegrationTest extends AuditLogManagementTestSupport {

	@Test
	void changingACoursePriceWritesExactlyOneAuditLogRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-price"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("audit-price"), teacher.getId(), CourseStatus.PUBLIC));
		BigDecimal originalPrice = course.price();

		var result = changePrice(host, adminToken, course.id(), new BigDecimal("149.99"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

		var rows = jdbcTemplate.queryForList(
				"SELECT tenant_id, actor_id, action, target_entity, target_id, reason, occurred_at, "
						+ "metadata::text AS metadata FROM audit_log "
						+ "WHERE action = 'course.price_changed' AND target_id = ?",
				course.id());
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);
		assertThat(row.get("tenant_id")).isEqualTo(tenant.getId());
		assertThat(row.get("actor_id")).isEqualTo(admin.getId());
		assertThat(row.get("action")).isEqualTo("course.price_changed");
		assertThat(row.get("target_entity")).isEqualTo("course");
		assertThat(row.get("target_id")).isEqualTo(course.id());
		assertThat(row.get("occurred_at")).isNotNull();

		// course.price_changed carries no reason - only the listener's
		// before/after metadata payload (plan §7/§9's actual point).
		assertThat(row.get("reason")).isNull();
		Map<String, Object> metadata = objectMapper.readValue((String) row.get("metadata"),
				new TypeReference<Map<String, Object>>() {
				});
		assertThat(new BigDecimal(metadata.get("previousPrice").toString())).isEqualByComparingTo(originalPrice);
		assertThat(new BigDecimal(metadata.get("newPrice").toString())).isEqualByComparingTo(new BigDecimal("149.99"));
	}

}

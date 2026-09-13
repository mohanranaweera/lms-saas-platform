package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;

/**
 * Proves {@link com.lms.auditlogmanagement.service.AuditLogEventListener
 * #onMaterialDeleted} writes exactly one {@code audit_log} row per material
 * deletion, with the fields plan §7/§9 require.
 */
class MaterialDeletionAuditIntegrationTest extends AuditLogManagementTestSupport {

	@Test
	void deletingAMaterialWritesExactlyOneAuditLogRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-material"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("audit-material"), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, adminToken, course.id(), module.id(), lesson.id(),
				"Lecture Notes", textNotesFile("notes.txt"));

		var result = deleteMaterial(host, adminToken, course.id(), module.id(), lesson.id(), material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

		var rows = jdbcTemplate.queryForList(
				"SELECT tenant_id, actor_id, action, target_entity, target_id, reason, occurred_at, "
						+ "metadata::text AS metadata FROM audit_log "
						+ "WHERE action = 'material.deleted' AND target_id = ?",
				material.id());
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);
		assertThat(row.get("tenant_id")).isEqualTo(tenant.getId());
		assertThat(row.get("actor_id")).isEqualTo(admin.getId());
		assertThat(row.get("action")).isEqualTo("material.deleted");
		assertThat(row.get("target_entity")).isEqualTo("material");
		assertThat(row.get("target_id")).isEqualTo(material.id());
		assertThat(row.get("occurred_at")).isNotNull();

		// material.deleted carries no reason - only the listener's
		// title/courseId metadata payload (plan §7/§9's actual point).
		assertThat(row.get("reason")).isNull();
		Map<String, Object> metadata = objectMapper.readValue((String) row.get("metadata"),
				new TypeReference<Map<String, Object>>() {
				});
		assertThat(metadata.get("title")).isEqualTo("Lecture Notes");
		assertThat(metadata.get("courseId")).isEqualTo(course.id().toString());
	}

}

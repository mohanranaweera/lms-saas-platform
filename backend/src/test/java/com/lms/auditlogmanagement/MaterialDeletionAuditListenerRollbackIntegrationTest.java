package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Mirrors {@code PaymentConfirmationRollbackIntegrationTest}'s exact
 * technique: a real Spring context/transaction manager/Testcontainers
 * Postgres, with only {@link AuditLogApi} replaced by a throwing mock.
 * {@link com.lms.auditlogmanagement.service.AuditLogEventListener
 * #onMaterialDeleted} is a plain {@code @EventListener}, so it runs
 * synchronously inside {@code MaterialService#deleteMaterial}'s own
 * transaction - forcing it to throw must roll back the deletion too.
 */
class MaterialDeletionAuditListenerRollbackIntegrationTest extends AuditLogManagementTestSupport {

	@MockitoBean
	private AuditLogApi auditLogApi;

	@Test
	void aFailureInTheAuditWriteRollsBackTheMaterialDeletion() {
		doThrow(new RuntimeException("Simulated audit log write failure")).when(auditLogApi).record(any());

		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-material-rollback"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("audit-material-rollback"), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, adminToken, course.id(), module.id(), lesson.id(),
				"Lecture Notes", textNotesFile("notes.txt"));

		var result = deleteMaterial(host, adminToken, course.id(), module.id(), lesson.id(), material.id());

		// 500 is the expected fallback here, not an unhandled-error smell to
		// "fix" later: the simulated RuntimeException is thrown deep inside
		// the listener, and no handler exists for a raw RuntimeException -
		// GlobalExceptionHandler#handleUnexpected catches it generically.
		// The behavior under test is the transaction rollback asserted below,
		// not the specific status code.
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		Long materialRowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM material WHERE id = ?", Long.class,
				material.id());
		assertThat(materialRowCount).isEqualTo(1L);

		Long auditRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'material.deleted' AND target_id = ?", Long.class,
				material.id());
		assertThat(auditRowCount).isEqualTo(0L);
	}

}

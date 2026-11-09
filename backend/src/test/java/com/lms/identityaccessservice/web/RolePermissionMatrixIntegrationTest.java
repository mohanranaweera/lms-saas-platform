package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.service.CapabilityElevationHolder;
import com.lms.usermanagement.staff.StaffApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * STAFF-MGMT-02 configurable role permissions through the real filter chain (Testcontainers):
 * matrix read/save/reload, atomic validation, optimistic concurrency, audit, cross-tenant
 * isolation, {@code @RequiresCapability} enforcement (interceptor before {@code @PreAuthorize},
 * per-request elevation, revocation on the next request) and {@code /api/v1/me/permissions}.
 */
class RolePermissionMatrixIntegrationTest extends StaffApiTestSupport {

	private static final String AUDIT = "staff.role_permissions_changed";

	private static final String TEST = "/api/test/capability";

	// ------------------------------------------------------------------ matrix read / save (A6, A7, A8, A11)

	@Test
	void matrixShowsDefaultsLockedAndUnavailableCells() {
		Fx fx = tenantWithAdmin("perm-read");
		JsonNode m = matrix(fx);

		assertThat(m.path("version").asLong()).isZero();
		List<String> roles = new ArrayList<>();
		m.path("roles").forEach(r -> roles.add(r.path("code").asString()));
		assertThat(roles).containsExactly("OPERATOR", "MANAGER", "TEACHER", "FINANCE_STAFF", "COURSE_COORDINATOR",
				"STUDENT_SUPPORT", "CONTENT_MANAGER", "EXAM_MANAGER", "ATTENDANCE_OPERATOR", "READ_ONLY_AUDITOR");
		assertThat(m.path("groups").get(0).path("label").asString()).isEqualTo("Finance");
		assertThat(m.path("groups").get(m.path("groups").size() - 1).path("key").asString()).isEqualTo("STAFF");

		assertThat(cell(m, "STUDENTS_VIEW", "OPERATOR").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "COURSES_VIEW", "TEACHER").path("state").asString()).isEqualTo("GRANTED");
		assertThat(cell(m, "STUDENTS_VIEW", "TEACHER").path("state").asString()).isEqualTo("LOCKED");
		assertThat(cell(m, "STUDENTS_VIEW", "TEACHER").path("reason").asString())
			.isEqualTo("Teacher access is ownership-scoped");
		assertThat(cell(m, "FINANCE_DASHBOARD_RECENT_PAYMENTS", "OPERATOR").path("state").asString())
			.isEqualTo("UNAVAILABLE");
		// TUTE-01: tute capabilities are editable for Operator and Teacher, not granted by default.
		assertThat(cell(m, "TUTE_CREATE", "OPERATOR").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "TUTE_CREATE", "TEACHER").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "TUTE_DISPATCH_PRINT_LABELS", "COURSE_COORDINATOR").path("state").asString())
			.isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "TUTE_DISPATCH_VIEW", "READ_ONLY_AUDITOR").path("state").asString())
			.isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "TUTE_UPDATE_STATUS", "READ_ONLY_AUDITOR").path("state").asString()).isEqualTo("LOCKED");
		assertThat(cell(m, "STAFF_PERMISSIONS_EDIT", "MANAGER").path("state").asString()).isEqualTo("LOCKED");
		assertThat(cell(m, "FINANCE_ACCOUNT_UPDATE", "OPERATOR").path("state").asString()).isEqualTo("LOCKED");
		// STAFF-MGMT-03: newly enforced rows are editable; Log as Student defaults to not granted.
		assertThat(cell(m, "STUDENTS_LOG_AS", "OPERATOR").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "TEACHERS_WALLET_ACTIONS", "MANAGER").path("state").asString()).isEqualTo("UNAVAILABLE");

		// Seeded roles show their static default (primary gate held) until overridden.
		assertThat(cell(m, "PAYMENTS_APPROVE_PENDING", "FINANCE_STAFF").path("state").asString())
			.isEqualTo("GRANTED");
		assertThat(cell(m, "FINANCE_CALCULATE_SETTLEMENTS", "FINANCE_STAFF").path("state").asString())
			.isEqualTo("GRANTED");
		assertThat(cell(m, "COURSES_ADD", "FINANCE_STAFF").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "COURSES_ADD", "COURSE_COORDINATOR").path("state").asString()).isEqualTo("GRANTED");
		// Enroll Students also needs PAYMENTS_SLIPS/APPROVE, so no seeded role holds it by default.
		assertThat(cell(m, "COURSES_ENROLL_STUDENTS", "STUDENT_SUPPORT").path("state").asString())
			.isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "STUDENTS_VIEW", "READ_ONLY_AUDITOR").path("state").asString()).isEqualTo("GRANTED");
		assertThat(cell(m, "STUDENTS_ADD", "READ_ONLY_AUDITOR").path("state").asString()).isEqualTo("LOCKED");
		assertThat(cell(m, "STUDENTS_ADD", "READ_ONLY_AUDITOR").path("reason").asString())
			.isEqualTo("Read-only Auditor can only hold view permissions");
		assertThat(cell(m, "STAFF_VIEW", "READ_ONLY_AUDITOR").path("state").asString()).isEqualTo("LOCKED");
	}

	@Test
	void seededRoleCellsShowTheirEffectiveStateAfterAnOverride() {
		Fx fx = tenantWithAdmin("perm-seeded");
		expect(saveMatrix(fx, fx.adminToken(), 0, List.of(change("FINANCE_STAFF", "PAYMENTS_APPROVE_PENDING", false),
				change("FINANCE_STAFF", "COURSES_ADD", true), change("READ_ONLY_AUDITOR", "STUDENTS_VIEW", false))),
				200);
		JsonNode m = matrix(fx);
		assertThat(cell(m, "PAYMENTS_APPROVE_PENDING", "FINANCE_STAFF").path("state").asString())
			.isEqualTo("NOT_GRANTED");
		assertThat(cell(m, "COURSES_ADD", "FINANCE_STAFF").path("state").asString()).isEqualTo("GRANTED");
		assertThat(cell(m, "STUDENTS_VIEW", "READ_ONLY_AUDITOR").path("state").asString()).isEqualTo("NOT_GRANTED");
		// Other seeded cells keep their default.
		assertThat(cell(m, "PAYMENTS_UNDO", "FINANCE_STAFF").path("state").asString()).isEqualTo("GRANTED");
		// Re-saving a default value is a no-op (no row, no version bump).
		expect(saveMatrix(fx, fx.adminToken(), 1, List.of(change("FINANCE_STAFF", "PAYMENTS_UNDO", true))), 200);
		assertThat(matrix(fx).path("version").asLong()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM tenant_role_capability_grant WHERE tenant_id = ? AND role_code = 'FINANCE_STAFF'",
				Long.class, fx.tenantId())).isEqualTo(2);
	}

	@Test
	@Tag("cross-tenant")
	void saveAndReloadIsAuditedAndNeverAffectsAnotherTenant() {
		Fx a = tenantWithAdmin("perm-save-a");
		Fx b = tenantWithAdmin("perm-save-b");

		JsonNode saved = expect(saveMatrix(a, a.adminToken(), 0, List.of(change("OPERATOR", "STUDENTS_VIEW", true),
				change("MANAGER", "COURSES_ADD", true), change("TEACHER", "COURSES_VIEW", false))), 200).path("data");
		assertThat(saved.path("version").asLong()).isEqualTo(1);

		JsonNode reloaded = matrix(a);
		assertThat(reloaded.path("version").asLong()).isEqualTo(1);
		assertThat(cell(reloaded, "STUDENTS_VIEW", "OPERATOR").path("state").asString()).isEqualTo("GRANTED");
		assertThat(cell(reloaded, "STUDENTS_VIEW", "MANAGER").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(reloaded, "COURSES_ADD", "MANAGER").path("state").asString()).isEqualTo("GRANTED");
		assertThat(cell(reloaded, "COURSES_VIEW", "TEACHER").path("state").asString()).isEqualTo("NOT_GRANTED");

		String metadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = ?", String.class, a.tenantId(),
				AUDIT);
		JsonNode meta = objectMapper.readTree(metadata);
		assertThat(meta.path("fromVersion").asLong()).isZero();
		assertThat(meta.path("toVersion").asLong()).isEqualTo(1);
		assertThat(meta.path("granted")).hasSize(2);
		assertThat(meta.path("revoked")).hasSize(1);
		assertThat(meta.path("revoked").get(0).path("capability").asString()).isEqualTo("COURSES_VIEW");

		// Tenant B: untouched.
		JsonNode other = matrix(b);
		assertThat(other.path("version").asLong()).isZero();
		assertThat(cell(other, "STUDENTS_VIEW", "OPERATOR").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(cell(other, "COURSES_VIEW", "TEACHER").path("state").asString()).isEqualTo("GRANTED");
		assertThat(auditCount(b.tenantId(), AUDIT)).isZero();

		// A no-op save (already granted) changes nothing and writes no audit entry.
		JsonNode noop = expect(saveMatrix(a, a.adminToken(), 1, List.of(change("OPERATOR", "STUDENTS_VIEW", true))),
				200).path("data");
		assertThat(noop.path("version").asLong()).isEqualTo(1);
		assertThat(auditCount(a.tenantId(), AUDIT)).isEqualTo(1);
	}

	@Test
	void invalidBatchesAre400WithNoPartialWriteAndNoAudit() {
		Fx fx = tenantWithAdmin("perm-invalid");
		Map<String, Object> valid = change("OPERATOR", "STUDENTS_VIEW", true);
		List<List<Map<String, Object>>> invalid = List.of(List.of(valid, change("OPERATOR", "STAFF_VIEW", true)),
				List.of(valid, change("MANAGER", "FINANCE_DASHBOARD_RECENT_PAYMENTS", true)),
				List.of(valid, change("READ_ONLY_AUDITOR", "TUTE_CREATE", true)),
				List.of(valid, change("OPERATOR", "NOT_A_CAPABILITY", true)),
				List.of(valid, change("TENANT_ADMIN", "STUDENTS_VIEW", true)),
				List.of(valid, change("TEACHER_ASSISTANT", "STUDENTS_VIEW", true)),
				// Read-only Auditor: view capabilities only (static "never a write" invariant).
				List.of(valid, change("READ_ONLY_AUDITOR", "STUDENTS_ADD", true)),
				List.of(valid, change("READ_ONLY_AUDITOR", "FINANCE_CALCULATE_SETTLEMENTS", true)),
				List.of(valid, change("TEACHER", "STUDENTS_VIEW", true)),
				List.of(valid, change("OPERATOR", "FINANCE_ACCOUNT_UPDATE", true)),
				List.of(valid, change("FINANCE_STAFF", "TEACHERS_WALLET_ACTIONS", true)),
				List.of(valid, change("OPERATOR", "STUDENTS_VIEW", false)));
		for (List<Map<String, Object>> batch : invalid) {
			JsonNode body = expect(saveMatrix(fx, fx.adminToken(), 0, batch), 400);
			assertThat(errorCode(body)).as(batch.toString()).isEqualTo("VALIDATION_ERROR");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_role_capability_grant WHERE tenant_id = ?",
				Long.class, fx.tenantId())).isZero();
		assertThat(matrix(fx).path("version").asLong()).isZero();
		assertThat(auditCount(fx.tenantId(), AUDIT)).isZero();
	}

	@Test
	void aStaleVersionIs409AndWritesNothing() {
		Fx fx = tenantWithAdmin("perm-stale");
		expect(saveMatrix(fx, fx.adminToken(), 0, List.of(change("OPERATOR", "STUDENTS_VIEW", true))), 200);

		JsonNode stale = expect(saveMatrix(fx, fx.adminToken(), 0, List.of(change("MANAGER", "STUDENTS_VIEW", true))),
				409);
		assertThat(errorCode(stale)).isEqualTo("PERMISSIONS_STALE");
		assertThat(cell(matrix(fx), "STUDENTS_VIEW", "MANAGER").path("state").asString()).isEqualTo("NOT_GRANTED");
		assertThat(auditCount(fx.tenantId(), AUDIT)).isEqualTo(1);

		// Even an all-no-op save tells a stale editor so.
		assertThat(errorCode(expect(saveMatrix(fx, fx.adminToken(), 0,
				List.of(change("OPERATOR", "STUDENTS_VIEW", true))), 409))).isEqualTo("PERMISSIONS_STALE");
	}

	@Test
	void onlyTheTenantAdminMayEditPermissions() {
		Fx fx = tenantWithAdmin("perm-who");
		String auditor = userToken(fx, uniqueEmail("auditor"), Role.READ_ONLY_AUDITOR);
		String finance = userToken(fx, uniqueEmail("finance"), Role.FINANCE_STAFF);
		String operator = userToken(fx, uniqueEmail("operator"), Role.OPERATOR);
		String teacher = userToken(fx, uniqueEmail("teacher"), Role.TEACHER);

		expect(call(HttpMethod.GET, MATRIX, fx.host(), auditor, null), 200);
		for (String token : List.of(auditor, finance, operator, teacher)) {
			expect(saveMatrix(fx, token, 0, List.of(change("OPERATOR", "STUDENTS_VIEW", true))), 403);
		}
		expect(call(HttpMethod.GET, MATRIX, fx.host(), operator, null), 403);
		assertThat(auditCount(fx.tenantId(), AUDIT)).isZero();
		assertThat(matrix(fx).path("version").asLong()).isZero();
	}

	// ------------------------------------------------------------------ enforcement (A9, A10)

	@Test
	void operatorIsDeniedUntilGrantedThenElevatedForThatRequestOnlyAndRevocationAppliesNextRequest() {
		Fx fx = tenantWithAdmin("perm-enforce");
		String operator = userToken(fx, uniqueEmail("operator"), Role.OPERATOR);

		expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), operator, null), 403);
		expect(call(HttpMethod.GET, TEST + "/plain", fx.host(), operator, null), 403);

		setGrants(fx, "OPERATOR", true, "STUDENTS_VIEW");

		// Passes the @PreAuthorize hasPermission gate only because the interceptor ran first.
		JsonNode ok = expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), operator, null), 200);
		assertThat(ok.path("elevated").asInt()).isEqualTo(1);
		assertThat(ok.path("studentsCreateEdit").asBoolean()).isFalse();
		// MockMvc dispatches on this thread: the elevation was cleared after the request.
		assertThat(CapabilityElevationHolder.get()).isEmpty();
		// Non-annotated endpoints stay closed even right after an elevated request.
		expect(call(HttpMethod.GET, TEST + "/plain", fx.host(), operator, null), 403);
		expect(call(HttpMethod.GET, STAFF, fx.host(), operator, null), 403);

		setGrants(fx, "OPERATOR", false, "STUDENTS_VIEW");
		// Same token, next request: denied.
		expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), operator, null), 403);
	}

	@Test
	@Tag("cross-tenant")
	void aGrantInOneTenantNeverEnablesTheSameRoleInAnotherTenant() {
		Fx a = tenantWithAdmin("perm-iso-a");
		Fx b = tenantWithAdmin("perm-iso-b");
		String email = uniqueEmail("operator");
		String tokenA = userToken(a, email, Role.OPERATOR);
		String tokenB = userToken(b, email, Role.OPERATOR);
		setGrants(a, "OPERATOR", true, "STUDENTS_VIEW");

		expect(call(HttpMethod.GET, TEST + "/annotated", a.host(), tokenA, null), 200);
		expect(call(HttpMethod.GET, TEST + "/annotated", b.host(), tokenB, null), 403);
		// A tenant-A token presented on tenant B's host is rejected outright.
		assertThat(call(HttpMethod.GET, TEST + "/annotated", b.host(), tokenA, null).getResponse().getStatus())
			.isIn(401, 403);
	}

	@Test
	void teacherGrantsOnlyNarrowAndNeverElevate() {
		Fx fx = tenantWithAdmin("perm-teacher");
		String teacher = userToken(fx, uniqueEmail("teacher"), Role.TEACHER);

		JsonNode ok = expect(call(HttpMethod.GET, TEST + "/teacher-courses", fx.host(), teacher, null), 200);
		assertThat(ok.path("elevated").asInt()).isZero();
		// A teacher-configurable grant never opens a static gate.
		expect(call(HttpMethod.GET, TEST + "/plain", fx.host(), teacher, null), 403);

		setGrants(fx, "TEACHER", false, "COURSES_VIEW");
		expect(call(HttpMethod.GET, TEST + "/teacher-courses", fx.host(), teacher, null), 403);
	}

	@Test
	void staticRolesAreUnchangedByTheInterceptor() {
		Fx fx = tenantWithAdmin("perm-static");
		String finance = userToken(fx, uniqueEmail("finance"), Role.FINANCE_STAFF);
		String attendance = userToken(fx, uniqueEmail("attendance"), Role.ATTENDANCE_OPERATOR);

		assertThat(expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), finance, null), 200).path("elevated")
			.asInt()).isZero();
		expect(call(HttpMethod.GET, TEST + "/plain", fx.host(), finance, null), 200);
		expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), fx.adminToken(), null), 200);
		expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), attendance, null), 200);
		// Operator grants never touch the static matrix of other roles.
		setGrants(fx, "OPERATOR", true, "STUDENTS_DELETE");
		assertThat(expect(call(HttpMethod.GET, TEST + "/annotated", fx.host(), finance, null), 200).path("elevated")
			.asInt()).isZero();
		assertThat(keysOf(fx, finance)).doesNotContain("STUDENTS_DELETE");
	}

	// ------------------------------------------------------------------ /me/permissions

	@Test
	void myPermissionsReflectRoleGrantsAndVersion() {
		Fx fx = tenantWithAdmin("perm-me");
		JsonNode admin = expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), fx.adminToken(), null), 200)
			.path("data");
		assertThat(admin.path("role").asString()).isEqualTo("TENANT_ADMIN");
		assertThat(keys(admin)).contains("STAFF_PERMISSIONS_EDIT", "STUDENTS_VIEW", "STUDENTS_LOG_AS")
			.contains("TUTE_CREATE", "TUTE_DISPATCH_PRINT_LABELS")
			.doesNotContain("FINANCE_DASHBOARD_RECENT_PAYMENTS");

		String operator = userToken(fx, uniqueEmail("operator"), Role.OPERATOR);
		JsonNode before = expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), operator, null), 200)
			.path("data");
		assertThat(keys(before)).isEmpty();
		long version = setGrants(fx, "OPERATOR", true, "STUDENTS_VIEW", "PAYMENTS_VIEW");
		JsonNode after = expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), operator, null), 200)
			.path("data");
		assertThat(keys(after)).containsExactlyInAnyOrder("STUDENTS_VIEW", "PAYMENTS_VIEW");
		assertThat(after.path("permissionVersion").asLong()).isEqualTo(version);

		String teacher = userToken(fx, uniqueEmail("teacher"), Role.TEACHER);
		JsonNode teacherPerms = expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), teacher, null), 200)
			.path("data");
		assertThat(keys(teacherPerms)).containsExactlyInAnyOrder("COURSES_VIEW", "COURSES_VIEW_STUDENTS",
				"MODULES_VIEW", "MODULES_ADD", "MODULES_DELETE", "EXAMS_VIEW");

		String student = userToken(fx, uniqueEmail("student"), Role.STUDENT);
		assertThat(keys(expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), student, null), 200)
			.path("data"))).isEmpty();
	}

	private List<String> keysOf(Fx fx, String token) {
		return keys(expect(call(HttpMethod.GET, "/api/v1/me/permissions", fx.host(), token, null), 200).path("data"));
	}

	private static List<String> keys(JsonNode perms) {
		List<String> keys = new ArrayList<>();
		perms.path("capabilities").forEach(k -> keys.add(k.asString()));
		return keys;
	}

}

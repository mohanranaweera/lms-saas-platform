package com.lms.integrationmanagement.media;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * MEDIA-INT-01 tenant-isolation review T1: the V60 schema itself refuses
 * cross-tenant references and invalid connections - proven with direct SQL,
 * bypassing every service-layer check.
 */
class MediaProviderConnectionSchemaIntegrationTest extends MediaIntegrationTestSupport {

	private record TenantUsers(Tenant tenant, TenantUser teacher) {
	}

	private TenantUsers seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		return new TenantUsers(tenant, teacher);
	}

	private UUID insertConnection(UUID tenantId, String provider, String scope, UUID ownerUserId, String authType,
			String status) {
		UUID id = UUID.randomUUID();
		boolean disconnected = "DISCONNECTED".equals(status);
		jdbcTemplate.update("""
				INSERT INTO media_provider_connection
				    (id, tenant_id, provider, scope, owner_user_id, auth_type, credentials_ciphertext, status,
				     disconnected_by, disconnected_at, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'v1:test', ?, ?, ?, now(), now())
				""", id, tenantId, provider, scope, ownerUserId, authType, status, disconnected ? "OWNER" : null,
				disconnected ? java.sql.Timestamp.from(Instant.now()) : null);
		return id;
	}

	private UUID insertTeacherZoom(UUID tenantId, UUID ownerUserId, String status) {
		return insertConnection(tenantId, "ZOOM", "TEACHER", ownerUserId, "OAUTH", status);
	}

	@Test
	void aConnectionCannotBeOwnedByAnotherTenantsUser() {
		TenantUsers a = seed("sch-owner-a");
		TenantUsers b = seed("sch-owner-b");

		assertThatThrownBy(() -> insertTeacherZoom(a.tenant().getId(), b.teacher().getId(), "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aClassSessionCannotUseAnotherTenantsConnection() {
		LiveClassFixture a = seedLiveClassFixture("sch-session-a");
		TenantUsers b = seed("sch-session-b");
		UUID connectionB = insertTeacherZoom(b.tenant().getId(), b.teacher().getId(), "ACTIVE");
		UUID sessionA = UUID.randomUUID();
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO class_session
				    (id, tenant_id, course_id, teacher_id, title, scheduled_start, scheduled_end, status,
				     meeting_provider, provider_status, provider_connection_id, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'Cross-tenant', ?, ?, 'SCHEDULED', 'ZOOM', 'PROVISIONED', ?, now(), now())
				""", sessionA, a.tenant().getId(), a.course().id(), a.teacher().getId(),
				java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(1, ChronoUnit.HOURS)), connectionB))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void teacherProvidersNeedAnOwnerAndTenantProvidersMustNotHaveOne() {
		TenantUsers a = seed("sch-scope");

		assertThatThrownBy(() -> insertConnection(a.tenant().getId(), "ZOOM", "TEACHER", null, "OAUTH", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertConnection(a.tenant().getId(), "BUNNY", "TENANT", a.teacher().getId(), "API_KEY",
				"ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertConnection(a.tenant().getId(), "DYNTUBE", "TEACHER", a.teacher().getId(),
				"API_KEY", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void onlyOneLiveConnectionPerTeacherProviderAndPerTenantProvider() {
		TenantUsers a = seed("sch-unique");
		insertTeacherZoom(a.tenant().getId(), a.teacher().getId(), "ACTIVE");
		assertThatThrownBy(() -> insertTeacherZoom(a.tenant().getId(), a.teacher().getId(), "NEEDS_RECONNECT"))
			.isInstanceOf(DataIntegrityViolationException.class);
		// Disconnected history rows are unlimited.
		assertThatCode(() -> insertTeacherZoom(a.tenant().getId(), a.teacher().getId(), "DISCONNECTED"))
			.doesNotThrowAnyException();

		insertConnection(a.tenant().getId(), "BUNNY", "TENANT", null, "API_KEY", "ACTIVE");
		assertThatThrownBy(() -> insertConnection(a.tenant().getId(), "BUNNY", "TENANT", null, "API_KEY", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void aDisconnectedRowMustRecordWhoAndWhen() {
		TenantUsers a = seed("sch-disconnected");

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO media_provider_connection
				    (id, tenant_id, provider, scope, owner_user_id, auth_type, credentials_ciphertext, status,
				     created_at, updated_at)
				VALUES (?, ?, 'ZOOM', 'TEACHER', ?, 'OAUTH', 'wiped', 'DISCONNECTED', now(), now())
				""", UUID.randomUUID(), a.tenant().getId(), a.teacher().getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

}

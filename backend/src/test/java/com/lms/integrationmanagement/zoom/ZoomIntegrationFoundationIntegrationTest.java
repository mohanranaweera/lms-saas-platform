package com.lms.integrationmanagement.zoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.media.MediaIntegrationTestSupport;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class ZoomIntegrationFoundationIntegrationTest extends MediaIntegrationTestSupport {

	private record Context(Tenant tenant, Tenant otherTenant, String host, String adminToken, String teacherToken,
			TenantUser teacher, String otherHost, String otherAdminToken) {
	}

	private Context context(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(tenant.getId(), teacher.getId(), "Teacher");
		Tenant other = seedActiveTenant(uniqueSubdomain(prefix + "-other"));
		seedTenantUser(other.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String otherHost = hostFor(other.getSubdomain());
		return new Context(tenant, other, host, loginAndGetToken(host, "admin@example.test"),
			loginAndGetToken(host, "teacher@example.test"), teacher, otherHost,
			loginAndGetToken(otherHost, "admin@example.test"));
	}

	private MvcResult create(Context ctx, String token, String name, String policy, List<UUID> teachers) {
		return perform(authenticated(post("/api/v1/tenant-admin/zoom-integrations")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "Synthetic integration",
					"teacherPolicy", policy, "allowedTeacherIds", teachers))), ctx.host(), token));
	}

	private MvcResult list(Context ctx, String token) {
		return perform(authenticated(get("/api/v1/tenant-admin/zoom-integrations"), ctx.host(), token));
	}

	private MvcResult map(Context ctx, String token, UUID integrationId, UUID teacherId, UUID externalId) {
		return perform(authenticated(put("/api/v1/tenant-admin/zoom-integrations/{integrationId}/teacher-mappings/{teacherId}",
				integrationId, teacherId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("externalUserId", externalId))), ctx.host(), token));
	}

	@Test
	void tenantAdminCanCreateAndListSelectedTeacherIntegration() {
		Context ctx = context("zoom-foundation-create");
		MvcResult created = create(ctx, ctx.adminToken(), "Biology Zoom", "SELECTED_TEACHERS", List.of(ctx.teacher().getId()));
		assertThat(created.getResponse().getStatus()).isEqualTo(HttpStatus.CREATED.value());
		JsonNode data = json(created).path("data");
		assertThat(data.path("teacherPolicy").asString()).isEqualTo("SELECTED_TEACHERS");
		assertThat(data.path("hostStrategy").asString()).isEqualTo("MAPPED_TEACHER_HOST");
		assertThat(data.path("readiness").asString()).isEqualTo("UNVALIDATED");
		assertThat(json(list(ctx, ctx.adminToken())).path("data").size()).isEqualTo(1);
	}

	@Test
	void allTeachersRequiresAnEmptySelection() {
		Context ctx = context("zoom-foundation-policy");
		MvcResult invalid = create(ctx, ctx.adminToken(), "All Teachers", "ALL_TEACHERS", List.of(ctx.teacher().getId()));
		assertThat(invalid.getResponse().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(json(invalid).path("error").path("code").asString()).isEqualTo("VALIDATION_ERROR");
	}

	@Test
	void teacherCannotMutateTenantAdminFoundation() {
		Context ctx = context("zoom-foundation-permission");
		MvcResult result = create(ctx, ctx.teacherToken(), "Denied", "ALL_TEACHERS", List.of());
		assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void integrationsAreTenantIsolated() {
		Context ctx = context("zoom-foundation-isolation");
		MvcResult created = create(ctx, ctx.adminToken(), "Tenant A Zoom", "ALL_TEACHERS", List.of());
		UUID id = UUID.fromString(json(created).path("data").path("id").asString());
		MvcResult otherGet = perform(authenticated(get("/api/v1/tenant-admin/zoom-integrations/{id}", id),
				ctx.otherHost(), ctx.otherAdminToken()));
		assertThat(otherGet.getResponse().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(json(list(ctx, ctx.otherAdminToken())).path("data").size()).isZero();
		MvcResult otherDelete = perform(authenticated(delete("/api/v1/tenant-admin/zoom-integrations/{id}", id),
				ctx.otherHost(), ctx.otherAdminToken()));
		assertThat(otherDelete.getResponse().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

	@Test
	void mappingRequiresAnExistingSameIntegrationExternalUser() {
		Context ctx = context("zoom-foundation-mapping");
		MvcResult created = create(ctx, ctx.adminToken(), "Mapped Zoom", "SELECTED_TEACHERS", List.of(ctx.teacher().getId()));
		UUID id = UUID.fromString(json(created).path("data").path("id").asString());
		MvcResult result = map(ctx, ctx.adminToken(), id, ctx.teacher().getId(), UUID.randomUUID());
		assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
	}

}

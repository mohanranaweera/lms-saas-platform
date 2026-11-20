package com.lms.usermanagement.staff.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.usermanagement.staff.StaffApiTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * STAFF-MGMT-02 Staff Activities ({@code GET /api/v1/staff/activities}, A12): tenant-scoped
 * audit feed, newest first, search by action / target / actor, paging caps, date bounds,
 * redaction of secret-looking metadata keys, and who may read it.
 */
class StaffActivitiesIntegrationTest extends StaffApiTestSupport {

	private static final String ACTIVITIES = STAFF + "/activities";

	@Test
	@Tag("cross-tenant")
	void feedIsTenantScopedNewestFirstRedactedAndResolvesActors() {
		Fx a = tenantWithAdmin("act-a");
		Fx b = tenantWithAdmin("act-b");
		jdbcTemplate.update("INSERT INTO staff_profile (id, tenant_id, user_id, name, first_name, last_name, "
				+ "created_at, updated_at) VALUES (?, ?, ?, 'Chief Admin', 'Chief', 'Admin', now(), now())",
				UUID.randomUUID(), a.tenantId(), a.adminId());
		String idA = createStaff(a, "Feed", "Member", uniqueEmail("feed"), "OPERATOR").path("id").asString();
		expect(call(HttpMethod.PATCH, STAFF + "/" + idA, a.host(), a.adminToken(),
				Map.of("firstName", "Feed", "lastName", "Renamed", "contactNumber", "")), 200);
		UUID secretRow = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO audit_log (id, tenant_id, actor_id, action, target_entity, target_id, "
				+ "metadata, occurred_at) VALUES (?, ?, ?, 'custom.secret_test', 'thing', ?, CAST(? AS jsonb), ?)",
				secretRow, a.tenantId(), a.adminId(), UUID.randomUUID(),
				"{\"newPassword\":\"p\",\"tokenHash\":\"h\",\"apiSecret\":\"s\",\"otp\":\"1\",\"keep\":\"yes\"}",
				Timestamp.from(Instant.now().plusSeconds(5)));
		String idB = createStaff(b, "Other", "Tenant", uniqueEmail("other"), "OPERATOR").path("id").asString();

		JsonNode page = activities(a, a.adminToken(), "");
		List<String> targets = new ArrayList<>();
		List<Instant> times = new ArrayList<>();
		page.path("content").forEach(item -> {
			targets.add(item.path("targetReference").asString());
			times.add(Instant.parse(item.path("occurredAt").asString()));
		});
		assertThat(targets).contains(idA).doesNotContain(idB);
		assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());

		JsonNode first = page.path("content").get(0);
		assertThat(first.path("id").asString()).isEqualTo(secretRow.toString());
		assertThat(first.path("details").path("keep").asString()).isEqualTo("yes");
		assertThat(first.path("details").size()).isEqualTo(1);

		JsonNode invited = find(page, "staff.invited");
		assertThat(invited.path("actionLabel").asString()).isEqualTo("Staff invited");
		assertThat(invited.path("actorName").asString()).isEqualTo("Chief Admin");
		assertThat(invited.path("actorRole").asString()).isEqualTo("Tenant Admin");
		assertThat(invited.path("targetEntity").asString()).isEqualTo("staff_profile");
		assertThat(invited.path("details").path("email").asString()).contains("•");

		// Tenant B's own feed never shows tenant A's rows; B's admin has no profile -> masked email.
		JsonNode pageB = activities(b, b.adminToken(), "");
		pageB.path("content").forEach(item -> assertThat(item.path("targetReference").asString()).isNotEqualTo(idA));
		assertThat(find(pageB, "staff.invited").path("actorName").asString()).isEqualTo("a•••@example.test");
	}

	@Test
	void searchPagingAndDateBounds() {
		Fx fx = tenantWithAdmin("act-search");
		jdbcTemplate.update("INSERT INTO staff_profile (id, tenant_id, user_id, name, first_name, last_name, "
				+ "created_at, updated_at) VALUES (?, ?, ?, 'Searchable Boss', 'Searchable', 'Boss', now(), now())",
				UUID.randomUUID(), fx.tenantId(), fx.adminId());
		String id = createStaff(fx, "Search", "Me", uniqueEmail("search"), "OPERATOR").path("id").asString();
		expect(call(HttpMethod.PUT, STAFF + "/" + id + "/role", fx.host(), fx.adminToken(),
				Map.of("roleCode", "MANAGER")), 200);
		for (int i = 0; i < 3; i++) {
			expect(call(HttpMethod.PATCH, STAFF + "/" + id, fx.host(), fx.adminToken(),
					Map.of("firstName", "Search" + i, "lastName", "Me", "contactNumber", "")), 200);
		}

		JsonNode byAction = activities(fx, fx.adminToken(), "?q=ROLE_CHANGED");
		assertThat(byAction.path("totalElements").asLong()).isEqualTo(1);
		assertThat(byAction.path("content").get(0).path("details").path("to").asString()).isEqualTo("MANAGER");
		assertThat(activities(fx, fx.adminToken(), "?q=staff_profile").path("totalElements").asLong())
			.isGreaterThanOrEqualTo(5);
		assertThat(activities(fx, fx.adminToken(), "?q=searchable").path("totalElements").asLong())
			.isGreaterThanOrEqualTo(5);
		assertThat(activities(fx, fx.adminToken(), "?q=no-such-thing").path("totalElements").asLong()).isZero();

		JsonNode one = activities(fx, fx.adminToken(), "?size=1&page=1");
		assertThat(one.path("content")).hasSize(1);
		assertThat(one.path("size").asInt()).isEqualTo(1);
		assertThat(activities(fx, fx.adminToken(), "?size=500").path("size").asInt()).isEqualTo(50);
		assertThat(activities(fx, fx.adminToken(), "").path("size").asInt()).isEqualTo(20);

		String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();
		assertThat(activities(fx, fx.adminToken(), "?from=" + future).path("totalElements").asLong()).isZero();
		String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
		assertThat(activities(fx, fx.adminToken(), "?to=" + past).path("totalElements").asLong()).isZero();
	}

	@Test
	void onlyTenantAdminAndAuditorMayReadTheFeed() {
		Fx fx = tenantWithAdmin("act-who");
		createStaff(fx, "Who", "Ever", uniqueEmail("who"), "OPERATOR");
		String auditor = userToken(fx, uniqueEmail("auditor"), Role.READ_ONLY_AUDITOR);
		assertThat(activities(fx, auditor, "").path("totalElements").asLong()).isGreaterThanOrEqualTo(1);
		for (Role role : List.of(Role.FINANCE_STAFF, Role.STUDENT_SUPPORT, Role.OPERATOR, Role.MANAGER, Role.TEACHER)) {
			String token = userToken(fx, uniqueEmail(role.name().toLowerCase()), role);
			expect(call(HttpMethod.GET, ACTIVITIES, fx.host(), token, null), 403);
		}
	}

	private JsonNode activities(Fx fx, String token, String query) {
		return expect(call(HttpMethod.GET, ACTIVITIES + query, fx.host(), token, null), 200).path("data");
	}

	private static JsonNode find(JsonNode page, String action) {
		for (JsonNode item : page.path("content")) {
			if (action.equals(item.path("action").asString())) {
				return item;
			}
		}
		throw new AssertionError("No " + action + " in feed");
	}

}

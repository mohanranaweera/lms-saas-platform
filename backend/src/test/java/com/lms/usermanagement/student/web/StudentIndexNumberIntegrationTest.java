package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.api.StudentIndexEntry;
import com.lms.usermanagement.api.StudentLookupApi;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * V95 per-tenant student index number (spec student-payment-report.md section
 * 6, owner decision D1): every creation path (staff create, public
 * self-registration, bulk CSV import) gets a unique, zero-padded number from
 * the database trigger - the application never supplies one - and the number
 * is immutable. The backfill of pre-existing students is proven by running the
 * real V95 over a throwaway schema migrated to V94. The counter is never reset,
 * so tests assert format, padding, order and uniqueness - never exact values
 * of the shared test database.
 */
class StudentIndexNumberIntegrationTest extends CourseManagementTestSupport {

	private static final String FORMAT = "^([0-9]{4}|[1-9][0-9]{4,19})$";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private StudentLookupApi studentLookupApi;

	private record Admin(Tenant tenant, String host, String token) {
	}

	private Admin admin(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		return new Admin(tenant, host, loginAndGetToken(host, "admin@example.test"));
	}

	private static String email(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

	private MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return perform(authenticated(
				builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), host,
				token));
	}

	private JsonNode data(MvcResult result) {
		return objectMapper.readTree(rawContent(result)).path("data");
	}

	private UUID createStudent(Admin a, String name) {
		Map<String, Object> body = Map.of("name", name, "email", email("idx"), "password", "password123");
		MvcResult result = send(a.host(), a.token(), post("/api/v1/students"), body);
		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(data(result).path("id").asString());
	}

	private String indexOf(UUID profileId) {
		return jdbcTemplate.queryForObject("SELECT index_number FROM student_profile WHERE id = ?", String.class,
				profileId);
	}

	private List<String> indexesOf(UUID tenantId) {
		return jdbcTemplate.queryForList("SELECT index_number FROM student_profile WHERE tenant_id = ? "
				+ "ORDER BY created_at, id", String.class, tenantId);
	}

	@Test
	void everyCreationPathGetsAUniqueZeroPaddedNumberFromTheDatabase() throws Exception {
		Admin a = admin("idx-paths");
		UUID first = createStudent(a, "Admin Created One");
		UUID second = createStudent(a, "Admin Created Two");

		Map<String, Object> registration = new LinkedHashMap<>();
		registration.put("name", "Self Registered");
		registration.put("email", email("self"));
		registration.put("password", "password123");
		MvcResult registered = send(a.host(), null, post("/api/v1/students/register"), registration);
		assertThat(registered.getResponse().getStatus()).as(rawContent(registered)).isEqualTo(201);
		UUID self = UUID.fromString(data(registered).path("studentProfileId").asString());

		String csv = "name,email,password\nBulk One," + email("bulk") + ",password123\nBulk Two," + email("bulk")
				+ ",password123\n";
		MockMultipartHttpServletRequestBuilder upload = multipart("/api/v1/students/bulk-import")
			.file(new MockMultipartFile("file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
		upload.header(HttpHeaders.HOST, a.host());
		upload.header(HttpHeaders.AUTHORIZATION, "Bearer " + a.token());
		MvcResult imported = mockMvc.perform(upload).andReturn();
		assertThat(imported.getResponse().getStatus()).as(rawContent(imported)).isEqualTo(200);

		List<String> all = indexesOf(a.tenant().getId());
		assertThat(all).hasSize(5);
		assertThat(new HashSet<>(all)).hasSize(5);
		for (String index : all) {
			// A new tenant's numbers are small: four digits with leading zeros.
			assertThat(index).matches("0[0-9]{3}");
		}
		// Assigned in creation order (strictly increasing), whatever the path.
		for (int i = 1; i < all.size(); i++) {
			assertThat(Integer.parseInt(all.get(i))).isGreaterThan(Integer.parseInt(all.get(i - 1)));
		}
		assertThat(List.of(indexOf(first), indexOf(second), indexOf(self))).doesNotContainNull();
		// The counter tracks the last number handed out.
		assertThat(jdbcTemplate.queryForObject("SELECT last_value FROM student_index_counter WHERE tenant_id = ?",
				Long.class, a.tenant().getId())).isEqualTo(Long.parseLong(all.get(all.size() - 1)));

		// Exposed through the user-management api as a string (tenant-scoped, batched).
		UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM student_profile WHERE id = ?", UUID.class, first);
		List<StudentIndexEntry> entries = withTenant(a.tenant().getId(),
				() -> studentLookupApi.findStudentIndexEntries(List.of(userId, UUID.randomUUID())));
		assertThat(entries).containsExactly(new StudentIndexEntry(userId, "Admin Created One", indexOf(first)));
	}

	@Test
	void numbersArePerTenantAndAnotherTenantsLookupSeesNothing() {
		Admin a = admin("idx-ta");
		Admin b = admin("idx-tb");
		UUID inA = createStudent(a, "Tenant A Student");
		UUID inB = createStudent(b, "Tenant B Student");
		// Each tenant has its own sequence: the first student of two fresh tenants gets the same number.
		assertThat(indexOf(inA)).isEqualTo(indexOf(inB)).matches(FORMAT);
		UUID userA = jdbcTemplate.queryForObject("SELECT user_id FROM student_profile WHERE id = ?", UUID.class, inA);
		assertThat(withTenant(b.tenant().getId(), () -> studentLookupApi.findStudentIndexEntries(List.of(userA))))
			.isEmpty();
	}

	@Test
	void theIndexNumberIsImmutable() {
		Admin a = admin("idx-immutable");
		UUID profile = createStudent(a, "Immutable");
		String index = indexOf(profile);
		// Application edits leave it untouched.
		MvcResult renamed = send(a.host(), a.token(), patch("/api/v1/students/{id}", profile),
				Map.of("name", "Renamed Student"));
		assertThat(renamed.getResponse().getStatus()).as(rawContent(renamed)).isEqualTo(200);
		assertThat(indexOf(profile)).isEqualTo(index);
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM student_profile WHERE id = ?", String.class, profile))
			.isEqualTo("Renamed Student");
		// Any direct change is refused by the database; an identical write is allowed.
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE student_profile SET index_number = '9999' WHERE id = ?",
				profile)).isInstanceOf(DataAccessException.class);
		jdbcTemplate.update("UPDATE student_profile SET index_number = index_number WHERE id = ?", profile);
		assertThat(indexOf(profile)).isEqualTo(index);
		// L4: the row can never move to another tenant (its number was allocated in this one).
		Admin other = admin("idx-immutable-other");
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE student_profile SET tenant_id = ? WHERE id = ?",
				other.tenant().getId(), profile)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("tenant_id cannot change once an index number is assigned");
		assertThat(jdbcTemplate.queryForObject("SELECT tenant_id FROM student_profile WHERE id = ?", UUID.class,
				profile)).isEqualTo(a.tenant().getId());
	}

	@Test
	void v95BackfillsExistingStudentsPerTenantInCreationOrder() {
		Admin a = admin("idx-backfill-a");
		Admin b = admin("idx-backfill-b");
		List<UUID> usersA = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			usersA.add(seedActiveStudent(a.tenant().getId(), email("bf-a")).getId());
		}
		UUID userB = seedActiveStudent(b.tenant().getId(), email("bf-b")).getId();
		String schema = "idx_mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try {
			migrate(schema, "94");
			for (Admin t : List.of(a, b)) {
				jdbcTemplate.update(String.format("INSERT INTO %s.tenant (%s) SELECT %s FROM public.tenant WHERE id = ?",
						schema, columnsOf(schema, "tenant"), columnsOf(schema, "tenant")), t.tenant().getId());
				String columns = columnsOf(schema, "tenant_user");
				jdbcTemplate.update(String.format("INSERT INTO %s.tenant_user (%s) SELECT %s FROM public.tenant_user "
						+ "WHERE tenant_id = ?", schema, columns, columns), t.tenant().getId());
			}
			Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
			// Tenant A: inserted out of creation order; two share a created_at (tie broken by id).
			UUID p1 = UUID.fromString("00000000-0000-0000-0000-00000000000b");
			UUID p2 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
			UUID p3 = UUID.randomUUID();
			insertLegacyProfile(schema, a.tenant().getId(), usersA.get(2), p3, t0.plusSeconds(60));
			insertLegacyProfile(schema, a.tenant().getId(), usersA.get(0), p1, t0);
			insertLegacyProfile(schema, a.tenant().getId(), usersA.get(1), p2, t0);
			UUID pb = UUID.randomUUID();
			insertLegacyProfile(schema, b.tenant().getId(), userB, pb, t0.plusSeconds(3600));

			migrate(schema, "95");

			assertThat(index(schema, p2)).isEqualTo("0001");
			assertThat(index(schema, p1)).isEqualTo("0002");
			assertThat(index(schema, p3)).isEqualTo("0003");
			assertThat(index(schema, pb)).isEqualTo("0001");
			assertThat(jdbcTemplate.queryForObject(String.format("SELECT last_value FROM %s.student_index_counter "
					+ "WHERE tenant_id = ?", schema), Long.class, a.tenant().getId())).isEqualTo(3L);
		}
		finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void migrate(String schema, String target) {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.locations("classpath:db/migration")
			.target(MigrationVersion.fromVersion(target))
			.load()
			.migrate();
	}

	private String columnsOf(String schema, String table) {
		return String.join(", ", jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
				+ "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position", String.class, schema, table));
	}

	private void insertLegacyProfile(String schema, UUID tenantId, UUID userId, UUID profileId, Instant createdAt) {
		jdbcTemplate.update(String.format("INSERT INTO %s.student_profile (id, tenant_id, user_id, name, created_at, "
				+ "updated_at) VALUES (?, ?, ?, ?, ?, ?)", schema), profileId, tenantId, userId, "Legacy " + profileId,
				Timestamp.from(createdAt), Timestamp.from(createdAt));
	}

	private String index(String schema, UUID profileId) {
		return jdbcTemplate.queryForObject(String.format("SELECT index_number FROM %s.student_profile WHERE id = ?",
				schema), String.class, profileId);
	}

}

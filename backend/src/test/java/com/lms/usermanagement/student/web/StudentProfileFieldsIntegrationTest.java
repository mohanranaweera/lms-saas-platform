package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * STU-PROF-01 end-to-end coverage through the real filter chain and a
 * Testcontainers Postgres: the Tenant Admin field settings API, the public
 * registration policy and registration validation, the student's own
 * "Additional details", the staff view/edit, and the mandatory cross-tenant
 * and cross-role negative cases.
 */
class StudentProfileFieldsIntegrationTest extends AuthIntegrationTestSupport {

	private static final String SETTINGS_PATH = "/api/v1/tenant-config/student-profile-fields";

	private static final String POLICY_PATH = "/api/v1/public/tenant-config/student-registration-policy";

	private static final String REGISTER_PATH = "/api/v1/students/register";

	private static final String OWN_PROFILE_PATH = "/api/v1/students/me/profile";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final int NON_LOCKED_FIELD_COUNT = (int) Arrays.stream(StudentProfileField.values())
		.filter(field -> !field.locked())
		.count();

	// ------------------------------------------------------------------
	// Tenant Admin settings.
	// ------------------------------------------------------------------

	@Test
	void aFreshTenantGetsDefaultsThatReproduceTheLegacyBehaviour() {
		Fixture t = newTenant("spf-defaults");

		JsonNode fields = data(call(HttpMethod.GET, SETTINGS_PATH, t.host, t.adminToken, null)).path("fields");

		assertThat(fields).hasSize(StudentProfileField.values().length);
		assertThat(field(fields, "EMAIL").path("locked").asBoolean()).isTrue();
		assertFlags(field(fields, "SCHOOL"), true, false, true);
		assertFlags(field(fields, "ADDRESS_LINE_1"), true, false, false);
		assertFlags(field(fields, "NIC_NUMBER"), false, false, false);
		assertThat(field(fields, "NIC_NUMBER").path("sensitive").asBoolean()).isTrue();
	}

	@Test
	void tenantAdminSavesSettingsWhichArePersistedForEveryFieldAndAuditLogged() {
		Fixture t = newTenant("spf-save");

		MvcResult result = saveSettings(t, setting("NIC_NUMBER", true, true, false),
				setting("SCHOOL", false, false, false));

		assertThat(status(result)).isEqualTo(HttpStatus.OK);
		JsonNode fields = data(call(HttpMethod.GET, SETTINGS_PATH, t.host, t.adminToken, null)).path("fields");
		assertFlags(field(fields, "NIC_NUMBER"), true, true, false);
		assertFlags(field(fields, "SCHOOL"), false, false, false);
		assertFlags(field(fields, "GRADE"), true, false, true);

		Long rows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_profile_field_setting WHERE tenant_id = ?", Long.class, t.tenant.getId());
		assertThat(rows).isEqualTo(NON_LOCKED_FIELD_COUNT);
		String metadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = 'student_profile_fields.updated'",
				String.class, t.tenant.getId());
		assertThat(metadata).contains("NIC_NUMBER").contains("SCHOOL").doesNotContain("GRADE");
	}

	@Test
	void invalidSettingsAreRejectedAndNothingIsWritten() {
		Fixture t = newTenant("spf-invalid");

		assertThat(status(saveSettings(t, setting("NOT_A_FIELD", true, false, false))))
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(status(saveSettings(t, setting("EMAIL", true, true, true)))).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(status(saveSettings(t, setting("NIC_NUMBER", false, true, false))))
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(status(saveSettings(t, setting("NIC_NUMBER", true, false, false),
				setting("NIC_NUMBER", true, true, false))))
			.isEqualTo(HttpStatus.BAD_REQUEST);

		Long rows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_profile_field_setting WHERE tenant_id = ?", Long.class, t.tenant.getId());
		assertThat(rows).isZero();
	}

	@Test
	void onlyTenantAdminCanEditSettingsAndOnlySettingsViewersCanReadThem() {
		Fixture t = newTenant("spf-roles");
		String coordinator = staffToken(t, Role.COURSE_COORDINATOR, "coordinator@example.test");
		String auditor = staffToken(t, Role.READ_ONLY_AUDITOR, "auditor@example.test");
		Map<String, Object> body = Map.of("fields", List.of(setting("NIC_NUMBER", true, false, false)));

		assertThat(status(call(HttpMethod.GET, SETTINGS_PATH, t.host, coordinator, null)))
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(call(HttpMethod.PUT, SETTINGS_PATH, t.host, coordinator, body)))
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(call(HttpMethod.GET, SETTINGS_PATH, t.host, auditor, null))).isEqualTo(HttpStatus.OK);
		assertThat(status(call(HttpMethod.PUT, SETTINGS_PATH, t.host, auditor, body)))
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void settingsAreIsolatedPerTenantIncludingThePublicRegistrationPolicy() {
		Fixture a = newTenant("spf-iso-a");
		Fixture b = newTenant("spf-iso-b");

		saveSettings(a, setting("NIC_NUMBER", true, true, true));

		JsonNode bFields = data(call(HttpMethod.GET, SETTINGS_PATH, b.host, b.adminToken, null)).path("fields");
		assertFlags(field(bFields, "NIC_NUMBER"), false, false, false);
		assertThat(registrationKeys(a)).contains("NIC_NUMBER");
		assertThat(registrationKeys(b)).doesNotContain("NIC_NUMBER");
		Long bRows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_profile_field_setting WHERE tenant_id = ?", Long.class, b.tenant.getId());
		assertThat(bRows).isZero();
	}

	@Test
	void theDatabaseRejectsRequiredOrRegistrationWithoutEnabled() {
		Fixture t = newTenant("spf-db-check");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO student_profile_field_setting (id, tenant_id, field_key, enabled, required, "
						+ "collect_at_registration, created_at, updated_at) VALUES (?, ?, 'NIC_NUMBER', false, true, "
						+ "false, now(), now())",
				UUID.randomUUID(), t.tenant.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// Registration.
	// ------------------------------------------------------------------

	@Test
	void registrationEnforcesRequiredRegistrationFieldsAndIgnoresFieldsNotAskedThere() {
		Fixture t = newTenant("spf-register");
		saveSettings(t, setting("WHATSAPP_NUMBER", true, true, true), setting("NIC_NUMBER", true, false, false));

		MvcResult missing = register(t, uniqueEmail("reg-missing"), Map.of());
		assertThat(status(missing)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(missing)).contains("profileFields.WHATSAPP_NUMBER");

		String email = uniqueEmail("reg-ok");
		MvcResult ok = register(t, email, Map.of("WHATSAPP_NUMBER", "+94 77 123 4567", "NIC_NUMBER", "200812345678"));
		assertThat(status(ok)).isEqualTo(HttpStatus.CREATED);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT sp.whatsapp_number, sp.nic_number FROM student_profile sp JOIN tenant_user tu "
						+ "ON sp.tenant_id = tu.tenant_id AND sp.user_id = tu.id WHERE sp.tenant_id = ? AND tu.email = ?",
				t.tenant.getId(), email);
		assertThat(row.get("whatsapp_number")).isEqualTo("+94 77 123 4567");
		assertThat(row.get("nic_number")).isNull();
	}

	@Test
	void anotherTenantsRequiredRegistrationFieldHasNoEffectOnThisTenantsRegistration() {
		Fixture a = newTenant("spf-reg-iso-a");
		Fixture b = newTenant("spf-reg-iso-b");
		saveSettings(a, setting("WHATSAPP_NUMBER", true, true, true));

		MvcResult atA = register(a, uniqueEmail("reg-iso-a"), Map.of());
		MvcResult atB = register(b, uniqueEmail("reg-iso-b"), Map.of());

		assertThat(status(atA)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(atA)).contains("profileFields.WHATSAPP_NUMBER");
		assertThat(status(atB)).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void legacyTopLevelRegistrationPropertiesAreStillAcceptedAndStored() {
		Fixture t = newTenant("spf-register-legacy");
		String email = uniqueEmail("reg-legacy");
		Map<String, Object> body = new HashMap<>();
		body.put("name", "Legacy Student");
		body.put("email", email);
		body.put("password", "password123");
		body.put("school", "Royal College");
		body.put("guardianName", "Parent");
		body.put("guardianPhone", "+94771234567");

		assertThat(status(call(HttpMethod.POST, REGISTER_PATH, t.host, null, body))).isEqualTo(HttpStatus.CREATED);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT sp.school, sp.guardian_name, sp.guardian_phone FROM student_profile sp JOIN tenant_user tu "
						+ "ON sp.tenant_id = tu.tenant_id AND sp.user_id = tu.id WHERE sp.tenant_id = ? AND tu.email = ?",
				t.tenant.getId(), email);
		assertThat(row).containsEntry("school", "Royal College")
			.containsEntry("guardian_name", "Parent")
			.containsEntry("guardian_phone", "+94771234567");
	}

	// ------------------------------------------------------------------
	// Student self-service.
	// ------------------------------------------------------------------

	@Test
	void aStudentSeesEnabledFieldsAndMustFillRequiredOnesWhenSaving() {
		Fixture t = newTenant("spf-self");
		saveSettings(t, setting("BIRTHDAY", true, true, false), setting("NIC_NUMBER", true, false, false));
		Student s = createStudent(t, "self");

		JsonNode initial = data(call(HttpMethod.GET, OWN_PROFILE_PATH, t.host, s.token, null));
		assertThat(keys(initial.path("fields"))).contains("BIRTHDAY", "NIC_NUMBER", "ADDRESS_LINE_1", "SCHOOL")
			.doesNotContain("PASSPORT_NUMBER", "EMAIL", "FULL_NAME");
		assertThat(texts(initial.path("missingRequiredFields"))).containsExactly("BIRTHDAY");

		MvcResult withoutBirthday = call(HttpMethod.PUT, OWN_PROFILE_PATH, t.host, s.token,
				values(Map.of("NIC_NUMBER", "200812345678")));
		assertThat(status(withoutBirthday)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(withoutBirthday)).contains("values.BIRTHDAY");

		MvcResult saved = call(HttpMethod.PUT, OWN_PROFILE_PATH, t.host, s.token,
				values(Map.of("BIRTHDAY", "2008-04-01", "NIC_NUMBER", "200812345678")));
		assertThat(status(saved)).isEqualTo(HttpStatus.OK);
		JsonNode after = data(saved);
		assertThat(field(after.path("fields"), "BIRTHDAY").path("value").asText()).isEqualTo("2008-04-01");
		assertThat(after.path("missingRequiredFields")).isEmpty();
	}

	@Test
	void aStudentCannotWriteDisabledUnknownOrLockedFields() {
		Fixture t = newTenant("spf-self-deny");
		Student s = createStudent(t, "self-deny");

		for (String key : List.of("PASSPORT_NUMBER", "NOT_A_FIELD", "EMAIL", "FULL_NAME")) {
			MvcResult result = call(HttpMethod.PUT, OWN_PROFILE_PATH, t.host, s.token, values(Map.of(key, "x")));
			assertThat(status(result)).as(key).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(fieldErrors(result)).as(key).contains("values." + key);
		}
	}

	@Test
	void theBillingAddressAndTheProfileFormShareTheSameColumns() {
		Fixture t = newTenant("spf-billing");
		Student s = createStudent(t, "billing");

		assertThat(status(call(HttpMethod.PUT, "/api/v1/students/me/billing-address", t.host, s.token,
				Map.of("addressLine", "12 Main Street", "city", "Colombo"))))
			.isEqualTo(HttpStatus.OK);

		JsonNode fields = data(call(HttpMethod.GET, OWN_PROFILE_PATH, t.host, s.token, null)).path("fields");
		assertThat(field(fields, "ADDRESS_LINE_1").path("value").asText()).isEqualTo("12 Main Street");
		assertThat(field(fields, "CITY").path("value").asText()).isEqualTo("Colombo");
	}

	@Test
	void disablingAFieldHidesButKeepsTheStoredValue() {
		Fixture t = newTenant("spf-hide");
		saveSettings(t, setting("NIC_NUMBER", true, false, false));
		Student s = createStudent(t, "hide");
		call(HttpMethod.PUT, OWN_PROFILE_PATH, t.host, s.token, values(Map.of("NIC_NUMBER", "200812345678")));

		saveSettings(t, setting("NIC_NUMBER", false, false, false));
		JsonNode hidden = data(call(HttpMethod.GET, OWN_PROFILE_PATH, t.host, s.token, null));
		assertThat(keys(hidden.path("fields"))).doesNotContain("NIC_NUMBER");
		assertThat(jdbcTemplate.queryForObject("SELECT nic_number FROM student_profile WHERE id = ?", String.class,
				s.profileId))
			.isEqualTo("200812345678");

		saveSettings(t, setting("NIC_NUMBER", true, false, false));
		JsonNode shown = data(call(HttpMethod.GET, OWN_PROFILE_PATH, t.host, s.token, null));
		assertThat(field(shown.path("fields"), "NIC_NUMBER").path("value").asText()).isEqualTo("200812345678");
	}

	// ------------------------------------------------------------------
	// Staff view/edit.
	// ------------------------------------------------------------------

	@Test
	void studentSupportCanCorrectAStudentWithoutRequiredFieldsAndTheAuditHoldsNoValues() {
		Fixture t = newTenant("spf-staff");
		saveSettings(t, setting("BIRTHDAY", true, true, false), setting("NIC_NUMBER", true, false, false));
		Student s = createStudent(t, "staff-edit");
		String support = staffToken(t, Role.STUDENT_SUPPORT, "support@example.test");

		MvcResult result = call(HttpMethod.PUT, staffPath(s.profileId), t.host, support,
				values(Map.of("NIC_NUMBER", "200899999999")));

		assertThat(status(result)).isEqualTo(HttpStatus.OK);
		assertThat(texts(data(result).path("missingRequiredFields"))).containsExactly("BIRTHDAY");
		String metadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = 'student.profile_fields_updated' "
						+ "AND target_id = ?",
				String.class, t.tenant.getId(), s.profileId);
		assertThat(metadata).contains("NIC_NUMBER").doesNotContain("200899999999");
	}

	@Test
	void viewOnlyStaffCanReadButNotEditAndStudentsCannotUseStaffEndpoints() {
		Fixture t = newTenant("spf-staff-roles");
		Student s = createStudent(t, "staff-roles");
		String coordinator = staffToken(t, Role.COURSE_COORDINATOR, "coordinator@example.test");

		assertThat(status(call(HttpMethod.GET, staffPath(s.profileId), t.host, coordinator, null)))
			.isEqualTo(HttpStatus.OK);
		assertThat(status(call(HttpMethod.PUT, staffPath(s.profileId), t.host, coordinator,
				values(Map.of("SCHOOL", "Blocked")))))
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(call(HttpMethod.GET, staffPath(s.profileId), t.host, s.token, null)))
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(call(HttpMethod.GET, OWN_PROFILE_PATH, t.host, t.adminToken, null)))
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void sensitiveIdentityNumbersAreMaskedForViewOnlyStaffButFullForStaffWhoCanEdit() {
		Fixture t = newTenant("spf-mask");
		saveSettings(t, setting("NIC_NUMBER", true, false, false), setting("SCHOOL", true, false, false));
		Student s = createStudent(t, "mask");
		call(HttpMethod.PUT, OWN_PROFILE_PATH, t.host, s.token,
				values(Map.of("NIC_NUMBER", "200812345678", "SCHOOL", "Royal College")));
		String coordinator = staffToken(t, Role.COURSE_COORDINATOR, "coordinator@example.test");
		String support = staffToken(t, Role.STUDENT_SUPPORT, "support@example.test");

		JsonNode viewOnly = data(call(HttpMethod.GET, staffPath(s.profileId), t.host, coordinator, null))
			.path("fields");
		assertThat(field(viewOnly, "NIC_NUMBER").path("value").asText()).isEqualTo("•••• 5678");
		assertThat(field(viewOnly, "SCHOOL").path("value").asText()).isEqualTo("Royal College");

		for (String token : List.of(support, t.adminToken)) {
			JsonNode editor = data(call(HttpMethod.GET, staffPath(s.profileId), t.host, token, null)).path("fields");
			assertThat(field(editor, "NIC_NUMBER").path("value").asText()).isEqualTo("200812345678");
		}
		JsonNode own = data(call(HttpMethod.GET, OWN_PROFILE_PATH, t.host, s.token, null)).path("fields");
		assertThat(field(own, "NIC_NUMBER").path("value").asText()).isEqualTo("200812345678");
	}

	@Test
	void anotherTenantsStaffGet404ForAStudentsProfileFieldsAndChangeNothing() {
		Fixture a = newTenant("spf-xt-a");
		Fixture b = newTenant("spf-xt-b");
		Student s = createStudent(a, "xt");
		call(HttpMethod.PUT, OWN_PROFILE_PATH, a.host, s.token, values(Map.of("SCHOOL", "Tenant A School")));

		assertThat(status(call(HttpMethod.GET, staffPath(s.profileId), b.host, b.adminToken, null)))
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(status(call(HttpMethod.PUT, staffPath(s.profileId), b.host, b.adminToken,
				values(Map.of("SCHOOL", "Hijacked")))))
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(jdbcTemplate.queryForObject("SELECT school FROM student_profile WHERE id = ?", String.class,
				s.profileId))
			.isEqualTo("Tenant A School");
	}

	// ------------------------------------------------------------------
	// Fixtures and plumbing.
	// ------------------------------------------------------------------

	private record Fixture(Tenant tenant, String host, String adminToken) {
	}

	private record Student(UUID profileId, String token) {
	}

	private Fixture newTenant(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		return new Fixture(tenant, host, token(host, "admin@example.test"));
	}

	private String staffToken(Fixture t, Role role, String email) {
		seedTenantUser(t.tenant.getId(), email, RAW_PASSWORD, role);
		return token(t.host, email);
	}

	private Student createStudent(Fixture t, String prefix) {
		String email = uniqueEmail(prefix);
		MvcResult created = call(HttpMethod.POST, "/api/v1/students", t.host, t.adminToken,
				Map.of("name", "Student " + prefix, "email", email, "password", RAW_PASSWORD));
		assertThat(status(created)).isEqualTo(HttpStatus.CREATED);
		return new Student(UUID.fromString(data(created).path("id").asText()), token(t.host, email));
	}

	private String token(String host, String email) {
		HttpResult<LoginResponse> login = login(host, email, RAW_PASSWORD);
		assertThat(login.getStatusCode()).as("login " + email).isEqualTo(HttpStatus.OK);
		return login.getBody().data().accessToken();
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

	private static String staffPath(UUID profileId) {
		return "/api/v1/students/" + profileId + "/profile-fields";
	}

	private static Map<String, Object> setting(String key, boolean enabled, boolean required, boolean collect) {
		return Map.of("key", key, "enabled", enabled, "required", required, "collectAtRegistration", collect);
	}

	@SafeVarargs
	private MvcResult saveSettings(Fixture t, Map<String, Object>... settings) {
		return call(HttpMethod.PUT, SETTINGS_PATH, t.host, t.adminToken, Map.of("fields", List.of(settings)));
	}

	private static Map<String, Object> values(Map<String, Object> values) {
		return Map.of("values", values);
	}

	private MvcResult register(Fixture t, String email, Map<String, Object> profileFields) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", "Registering Student");
		body.put("email", email);
		body.put("password", "password123");
		body.put("profileFields", profileFields);
		return call(HttpMethod.POST, REGISTER_PATH, t.host, null, body);
	}

	private List<String> registrationKeys(Fixture t) {
		return keys(data(call(HttpMethod.GET, POLICY_PATH, t.host, null, null)).path("registrationFields"));
	}

	private MvcResult call(HttpMethod method, String path, String host, String token, Object body) {
		try {
			MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
				.header(HttpHeaders.HOST, host);
			if (token != null) {
				request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			}
			if (body != null) {
				request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
			}
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	private static HttpStatus status(MvcResult result) {
		return HttpStatus.valueOf(result.getResponse().getStatus());
	}

	private static JsonNode body(MvcResult result) {
		try {
			return JSON.readTree(result.getResponse().getContentAsString());
		}
		catch (Exception e) {
			throw new IllegalStateException("Response is not JSON", e);
		}
	}

	private static JsonNode data(MvcResult result) {
		assertThat(status(result).is2xxSuccessful()).as("expected a 2xx response, got %s: %s", status(result),
				body(result))
			.isTrue();
		return body(result).path("data");
	}

	private static List<String> fieldErrors(MvcResult result) {
		List<String> fields = new ArrayList<>();
		body(result).path("error").path("fieldErrors").forEach(error -> fields.add(error.path("field").asText()));
		return fields;
	}

	private static JsonNode field(JsonNode fields, String key) {
		for (JsonNode field : fields) {
			if (key.equals(field.path("key").asText())) {
				return field;
			}
		}
		throw new AssertionError("No field " + key + " in " + fields);
	}

	private static List<String> keys(JsonNode fields) {
		List<String> keys = new ArrayList<>();
		fields.forEach(field -> keys.add(field.path("key").asText()));
		return keys;
	}

	private static List<String> texts(JsonNode array) {
		List<String> texts = new ArrayList<>();
		array.forEach(node -> texts.add(node.asText()));
		return texts;
	}

	private static void assertFlags(JsonNode field, boolean enabled, boolean required, boolean collect) {
		String key = field.path("key").asText();
		assertThat(field.path("enabled").asBoolean()).as(key + " enabled").isEqualTo(enabled);
		assertThat(field.path("required").asBoolean()).as(key + " required").isEqualTo(required);
		assertThat(field.path("collectAtRegistration").asBoolean()).as(key + " collectAtRegistration")
			.isEqualTo(collect);
	}

}

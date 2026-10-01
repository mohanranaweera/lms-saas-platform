package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.web.MessagingIntegrationController.ConfigureMessagingRequest;
import com.lms.notificationmanagement.service.BroadcastService.BroadcastView;
import com.lms.notificationmanagement.service.CommunicationTemplateService.TemplateView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Wave 11 security: role/ownership authorization on every communications
 * endpoint, cross-tenant isolation (including that async dispatch sends with
 * the recipient tenant's own provider account), and that messaging
 * credentials never come back out - not in responses, the database, the audit
 * log or the application log.
 */
@ExtendWith(OutputCaptureExtension.class)
class CommunicationsSecurityIntegrationTest extends CommunicationsTestSupport {

	private static final String BROADCASTS = "/api/v1/communications/broadcasts";

	private static final String MESSAGING = "/api/v1/tenant-admin/integrations/messaging";

	private static final String SECRET = "sk_live_SUPERSECRET_9876";

	// ---- authorization ----

	@Test
	void staffRolesGetExactlyTheirCommunicationsGrants() {
		CommsFixture f = seedFixture("comms-authz");
		Map<String, Object> body = broadcastBody(audience("ALL_STUDENTS"), List.of("IN_APP"));
		String student = loginAndGetToken(f.host(), "other@example.test");
		String auditor = staffToken(f, "auditor@example.test", Role.READ_ONLY_AUDITOR);
		String support = staffToken(f, "support@example.test", Role.STUDENT_SUPPORT);
		String finance = staffToken(f, "finance@example.test", Role.FINANCE_STAFF);

		assertThat(getRaw(f.host(), student, BROADCASTS).status()).isEqualTo(403);
		assertThat(postRaw(f.host(), student, BROADCASTS, body).status()).isEqualTo(403);
		assertThat(getRaw(f.host(), finance, BROADCASTS).status()).isEqualTo(403);

		assertThat(getRaw(f.host(), auditor, BROADCASTS).status()).isEqualTo(200);
		assertThat(postRaw(f.host(), auditor, BROADCASTS, body).status()).isEqualTo(403);
		assertThat(postRaw(f.host(), auditor, "/api/v1/communications/templates",
				Map.of("name", "x", "subject", "x", "body", "x")).status()).isEqualTo(403);
		assertThat(getRaw(f.host(), auditor, MESSAGING).status()).isEqualTo(200);

		assertThat(postRaw(f.host(), support, BROADCASTS, body).status()).isEqualTo(201);
		TemplateView template = readData(postRaw(f.host(), support, "/api/v1/communications/templates",
				Map.of("name", "Support note", "subject", "Hi", "body", "Hello {{name}}")), TemplateView.class);
		assertThat(postRaw(f.host(), support, "/api/v1/communications/templates/" + template.id()
				+ "/whatsapp-status", Map.of("action", "SUBMIT", "providerTemplateName", "support_note")).status())
			.as("recording WhatsApp approval is Tenant Admin only")
			.isEqualTo(403);
		assertThat(putRaw(f.host(), support, "/api/v1/communications/event-templates/PAYMENT_CONFIRMED",
				Map.of("subject", "Paid", "body", "Thanks {{amount}}")).status())
			.as("system templates are Tenant Admin only")
			.isEqualTo(403);
		assertThat(putRaw(f.host(), support, MESSAGING + "/SMS", Map.of("provider", "SANDBOX", "senderId", "X",
				"credentials", Map.of("apiKey", SECRET))).status()).isEqualTo(403);

		assertThat(putRaw(f.host(), f.adminToken(), "/api/v1/communications/event-templates/PAYMENT_CONFIRMED",
				Map.of("subject", "Paid {{amount}}", "body", "Thanks, ref {{paymentId}}")).status()).isEqualTo(200);
		assertThat(putRaw(f.host(), f.adminToken(), "/api/v1/communications/event-templates/PASSWORD_RESET",
				Map.of("subject", "Reset", "body", "No link here")).status())
			.as("a credential template must keep {{link}}")
			.isEqualTo(400);
	}

	@Test
	void teachersCanOnlyMessageTheirOwnCourseInAppOrByEmailAndSeeOnlyTheirOwnBroadcasts() {
		CommsFixture f = seedFixture("comms-teacher");
		Map<String, Object> ownCourse = audience("COURSE");
		ownCourse.put("courseId", f.course().id());

		BroadcastView own = sendOrFail(f, f.teacherToken(), broadcastBody(ownCourse, List.of("IN_APP", "EMAIL")));
		assertThat(own.recipientCount()).isEqualTo(1);

		assertThat(postRaw(f.host(), f.teacherToken(), BROADCASTS, broadcastBody(audience("ALL_STUDENTS"),
				List.of("IN_APP"))).status()).isEqualTo(403);
		configureMessaging(f, "SMS", "ACADEMY", "sandbox-api-key-0001234");
		assertThat(postRaw(f.host(), f.teacherToken(), BROADCASTS, broadcastBody(ownCourse, List.of("SMS"))).status())
			.isEqualTo(403);

		seedTenantUser(f.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(f.host(), "teacher2@example.test");
		assertThat(postRaw(f.host(), otherTeacher, BROADCASTS, broadcastBody(ownCourse, List.of("IN_APP"))).status())
			.as("another teacher's course looks like it does not exist")
			.isEqualTo(404);
		assertThat(getRaw(f.host(), otherTeacher, BROADCASTS + "/" + own.id()).status()).isEqualTo(404);
		assertThat(getRaw(f.host(), otherTeacher, BROADCASTS).body()).doesNotContain(own.id().toString());
		assertThat(getRaw(f.host(), f.teacherToken(), BROADCASTS).body()).contains(own.id().toString());

		BroadcastView adminBroadcast = sendOrFail(f, f.adminToken(),
				broadcastBody(audience("ALL_STUDENTS"), List.of("IN_APP")));
		assertThat(getRaw(f.host(), f.teacherToken(), BROADCASTS + "/" + adminBroadcast.id()).status())
			.isEqualTo(404);
	}

	// ---- cross-tenant ----

	@Test
	void noCommunicationsResourceIsReachableFromAnotherTenant() {
		CommsFixture a = seedFixture("comms-xt-a");
		CommsFixture b = seedFixture("comms-xt-b");
		BroadcastView aBroadcast = sendOrFail(a, a.adminToken(),
				broadcastBody(audience("ALL_STUDENTS"), List.of("IN_APP")));
		TemplateView aTemplate = readData(postRaw(a.host(), a.adminToken(), "/api/v1/communications/templates",
				Map.of("name", "A only", "subject", "A", "body", "A")), TemplateView.class);

		assertThat(getRaw(b.host(), b.adminToken(), BROADCASTS + "/" + aBroadcast.id()).status()).isEqualTo(404);
		assertThat(getRaw(b.host(), b.adminToken(), BROADCASTS + "/" + aBroadcast.id() + "/deliveries").status())
			.isEqualTo(404);
		assertThat(getRaw(b.host(), b.adminToken(), BROADCASTS).body()).doesNotContain(aBroadcast.id().toString());
		UUID aOtherProfile = jdbcTemplate.queryForObject("SELECT id FROM student_profile WHERE user_id = ?", UUID.class,
				a.other().getId());
		assertThat(getRaw(b.host(), b.adminToken(), "/api/v1/communications/students/" + aOtherProfile
				+ "/deliveries").status()).isEqualTo(404);
		assertThat(putRaw(b.host(), b.adminToken(), "/api/v1/communications/templates/" + aTemplate.id(),
				Map.of("name", "hijack", "subject", "x", "body", "x")).status()).isEqualTo(404);
		assertThat(getRaw(b.host(), b.adminToken(), "/api/v1/communications/templates").body())
			.doesNotContain(aTemplate.id().toString());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification n JOIN tenant_user u "
				+ "ON u.id = n.recipient_user_id WHERE n.tenant_id = ? AND u.tenant_id <> n.tenant_id", Integer.class,
				a.tenant().getId())).isZero();
		Raw history = getRaw(a.host(), a.adminToken(), "/api/v1/communications/students/" + aOtherProfile
				+ "/deliveries");
		assertThat(history.status()).isEqualTo(200);
		assertThat(history.body()).contains(aBroadcast.id().toString());
	}

	@Test
	void asyncDispatchSendsWithTheRecipientTenantsOwnProviderAccount() {
		CommsFixture a = seedFixture("comms-acct-a");
		CommsFixture b = seedFixture("comms-acct-b");
		configureMessaging(a, "SMS", "ACADEMY-A", "sandbox-api-key-aaaa1111");
		configureMessaging(b, "SMS", "ACADEMY-B", "sandbox-api-key-bbbb2222");
		BroadcastView aView = sendOrFail(a, a.adminToken(), broadcastBody(audience("ALL_STUDENTS"), List.of("SMS")));
		BroadcastView bView = sendOrFail(b, b.adminToken(), broadcastBody(audience("ALL_STUDENTS"), List.of("SMS")));

		// Interleave the two tenants' rows in one "poller cycle".
		dispatchDue(bView.id());
		dispatchDue(aView.id());

		for (CommsFixture fixture : List.of(a, b)) {
			Integer foreign = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_delivery d "
					+ "JOIN tenant_messaging_account acc ON acc.id = d.provider_account_id "
					+ "WHERE d.tenant_id = ? AND acc.tenant_id <> d.tenant_id", Integer.class, fixture.tenant().getId());
			Integer sent = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_delivery d "
					+ "WHERE d.tenant_id = ? AND d.status = 'SENT' AND d.provider_account_id IS NOT NULL", Integer.class,
					fixture.tenant().getId());
			assertThat(foreign).isZero();
			assertThat(sent).isEqualTo(2);
		}
	}

	// ---- secret exposure ----

	@Test
	void messagingCredentialsAreEncryptedMaskedAndNeverReturnedOrLogged(CapturedOutput output) {
		CommsFixture f = seedFixture("comms-secret");

		Raw saved = configureMessaging(f, "SMS", "ACADEMY", SECRET);
		assertThat(saved.status()).isEqualTo(200);
		assertThat(saved.body()).doesNotContain(SECRET).contains("••••9876");
		assertThat(getRaw(f.host(), f.adminToken(), MESSAGING).body()).doesNotContain(SECRET);

		String ciphertext = jdbcTemplate.queryForObject("SELECT credentials_ciphertext FROM tenant_messaging_account "
				+ "WHERE tenant_id = ? AND status = 'ACTIVE'", String.class, f.tenant().getId());
		assertThat(ciphertext).startsWith("v1:").doesNotContain(SECRET).doesNotContain("SUPERSECRET");
		List<String> auditMetadata = jdbcTemplate.queryForList("SELECT coalesce(metadata::text, '') FROM audit_log "
				+ "WHERE tenant_id = ?", String.class, f.tenant().getId());
		assertThat(auditMetadata).isNotEmpty().noneMatch(text -> text.contains("SUPERSECRET"));

		// Replacing credentials keeps the old row (deliveries reference it) but disables it.
		configureMessaging(f, "SMS", "ACADEMY", "sandbox-api-key-replaced-0000");
		assertThat(jdbcTemplate.queryForList("SELECT status FROM tenant_messaging_account WHERE tenant_id = ? "
				+ "ORDER BY created_at", String.class, f.tenant().getId())).containsExactly("DISABLED", "ACTIVE");

		Raw test = postRaw(f.host(), f.adminToken(), MESSAGING + "/SMS/test", Map.of("destination", "+94771239999"));
		assertThat(test.status()).isEqualTo(200);
		assertThat(test.body()).doesNotContain("94771239999").contains("•••999");

		assertThat(new ConfigureMessagingRequest("SANDBOX", "ACADEMY", Map.of("apiKey", SECRET)).toString())
			.doesNotContain(SECRET);
		assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("SUPERSECRET")
			.doesNotContain("94771239999");
	}

	@Test
	void anUnavailableProviderOrMalformedCredentialsAreRefused() {
		CommsFixture f = seedFixture("comms-badcfg");
		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/SMS", Map.of("provider", "TWILIO", "senderId",
				"X", "credentials", Map.of("apiKey", "k"))).status()).isEqualTo(400);
		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/SMS", Map.of("provider", "SANDBOX", "senderId",
				"X", "credentials", Map.of())).status()).isEqualTo(400);
		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/SMS", Map.of("provider", "SANDBOX", "senderId",
				"X", "credentials", Map.of("apiKey", "k", "extra", "v"))).status()).isEqualTo(400);
		assertThat(postRaw(f.host(), f.adminToken(), MESSAGING + "/SMS/disable", null).status()).isEqualTo(409);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_messaging_account WHERE tenant_id = ?",
				Integer.class, f.tenant().getId())).isZero();
	}

}

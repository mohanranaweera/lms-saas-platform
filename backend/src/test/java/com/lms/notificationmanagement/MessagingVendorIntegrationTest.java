package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.gateway.messaging.FakeMessagingVendorServer;
import com.lms.integrationmanagement.gateway.messaging.FakeMessagingVendorServer.Recorded;
import com.lms.integrationmanagement.gateway.messaging.MetaWebhookSignatures;
import com.lms.notificationmanagement.service.BroadcastService.BroadcastView;
import com.lms.notificationmanagement.service.CommunicationTemplateService.TemplateView;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * MSG-PROV-01 / ADR-024, end to end through the real HTTP API: tenant-level
 * Text.lk and Meta WhatsApp settings (validation, write-only secrets, the
 * template language, the managers-only webhook setup), broadcasts sent
 * through the vendors (a local fake), and Meta status callbacks - verified
 * handshake, HMAC with the tenant's own App Secret, forward-only status, and
 * no cross-tenant effect.
 */
@ExtendWith(OutputCaptureExtension.class)
class MessagingVendorIntegrationTest extends CommunicationsTestSupport {

	private static final String MESSAGING = "/api/v1/tenant-admin/integrations/messaging";

	private static final String BROADCASTS = "/api/v1/communications/broadcasts";

	private static final String WEBHOOK = "/api/v1/integrations/webhooks/meta-whatsapp/";

	private static final FakeMessagingVendorServer VENDOR = FakeMessagingVendorServer.get();

	@DynamicPropertySource
	static void vendorUrls(DynamicPropertyRegistry registry) {
		registry.add("app.messaging.textlk.base-url", VENDOR::baseUrl);
		registry.add("app.messaging.meta-whatsapp.base-url", VENDOR::baseUrl);
		registry.add("app.messaging.webhook-base-url", () -> "https://api.example.test");
	}

	// ---- settings ----

	@Test
	void textLkSettingsAreValidatedPerProviderAndTheTokenIsWriteOnly(CapturedOutput output) {
		CommsFixture f = seedFixture("msg-textlk");
		String token = "textlk-live-token-" + UUID.randomUUID();

		JsonNode settings = data(getRaw(f.host(), f.adminToken(), MESSAGING));
		assertThat(settings.path("availableProviders").toString()).contains("\"TEXTLK\"").contains("\"META_WHATSAPP\"")
			.contains("\"templateLanguage\"");

		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/SMS",
				Map.of("provider", "TEXTLK", "senderId", "ABC Institute Colombo", "credentials",
						Map.of("apiToken", token)))
			.status()).as("Text.lk sender IDs are at most 11 letters/digits").isEqualTo(400);
		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "TEXTLK", "senderId", "ABCInst", "credentials", Map.of("apiToken", token)))
			.status()).as("Text.lk is SMS only").isEqualTo(400);

		Raw saved = putRaw(f.host(), f.adminToken(), MESSAGING + "/SMS",
				Map.of("provider", "TEXTLK", "senderId", "ABCInst", "credentials", Map.of("apiToken", token)));
		assertThat(saved.status()).isEqualTo(200);
		assertThat(saved.body()).doesNotContain(token).contains("\"provider\":\"TEXTLK\"");
		assertThat(getRaw(f.host(), f.adminToken(), MESSAGING).body()).doesNotContain(token);
		assertThat(jdbcTemplate.queryForObject("SELECT credentials_ciphertext FROM tenant_messaging_account "
				+ "WHERE tenant_id = ? AND status = 'ACTIVE' AND channel = 'SMS'", String.class, f.tenant().getId()))
			.doesNotContain(token);
		assertThat(output.getAll()).doesNotContain(token);
	}

	@Test
	void metaSettingsNeedATemplateLanguageAndShowTheWebhookOnlyToManagers() {
		CommsFixture f = seedFixture("msg-meta-settings");
		Map<String, Object> credentials = Map.of("accessToken", "meta-token-" + UUID.randomUUID(), "appSecret",
				"meta-secret-" + UUID.randomUUID());

		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "META_WHATSAPP", "senderId", "106540000000001", "credentials", credentials))
			.status()).as("template language is required").isEqualTo(400);
		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "META_WHATSAPP", "senderId", "+94110000000", "credentials", credentials,
						"templateLanguage", "en"))
			.status()).as("sender must be the phone number ID").isEqualTo(400);
		assertThat(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "META_WHATSAPP", "senderId", "106540000000001", "credentials",
						Map.of("accessToken", "only-the-token-0000"), "templateLanguage", "en"))
			.status()).as("the App Secret is required").isEqualTo(400);

		JsonNode account = data(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "META_WHATSAPP", "senderId", "106540000000001", "credentials", credentials,
						"templateLanguage", "si")));
		assertThat(account.path("templateLanguage").asString()).isEqualTo("si");
		String callbackUrl = account.path("webhook").path("callbackUrl").asString();
		String verifyToken = account.path("webhook").path("verifyToken").asString();
		assertThat(callbackUrl).startsWith("https://api.example.test" + WEBHOOK);
		assertThat(verifyToken).hasSizeGreaterThanOrEqualTo(32);
		assertThat(account.toString()).doesNotContain((String) credentials.get("accessToken"))
			.doesNotContain((String) credentials.get("appSecret"));

		// Replacing the credentials keeps the same webhook URL and token (the Meta app keeps working).
		JsonNode replaced = data(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "META_WHATSAPP", "senderId", "106540000000001", "credentials", credentials,
						"templateLanguage", "en_US")));
		assertThat(replaced.path("webhook").path("callbackUrl").asString()).isEqualTo(callbackUrl);
		assertThat(replaced.path("webhook").path("verifyToken").asString()).isEqualTo(verifyToken);

		String auditor = staffToken(f, "auditor@example.test", Role.READ_ONLY_AUDITOR);
		Raw viewed = getRaw(f.host(), auditor, MESSAGING);
		assertThat(viewed.status()).isEqualTo(200);
		assertThat(viewed.body()).contains("\"templateLanguage\":\"en_US\"").doesNotContain(verifyToken);
	}

	// ---- sending ----

	@Test
	void broadcastsGoThroughTextLkAndMetaWithOrderedTemplateParameters() {
		CommsFixture f = seedFixture("msg-send");
		String smsToken = "textlk-token-" + UUID.randomUUID();
		String waToken = "meta-token-" + UUID.randomUUID();
		configureTextLk(f, smsToken);
		configureMeta(f, "106540000000011", waToken, "meta-secret-" + UUID.randomUUID());
		optIntoWhatsApp(f);
		UUID templateId = approvedTemplate(f, "welcome_term", "Hi {{name}}, welcome back to {{instituteName}}.");

		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId()));
		Map<String, Object> body = broadcastBody(selected, List.of("SMS", "WHATSAPP"));
		body.put("templateId", templateId);
		BroadcastView view = sendOrFail(f, f.adminToken(), body);
		dispatchDue(view.id());

		Map<String, Object> sms = deliveryRow(view.id(), f.other().getId(), "SMS");
		assertThat(sms).containsEntry("status", "SENT");
		assertThat((String) sms.get("provider_message_id")).startsWith("tlk-");
		Recorded smsRequest = VENDOR.lastContaining("\"recipient\":\"94771230200\"");
		assertThat(smsRequest.authorization()).isEqualTo("Bearer " + smsToken);
		// SMS carries the broadcast's own (editable) copy.
		assertThat(smsRequest.body()).contains("\"sender_id\":\"ABCInst\"")
			.contains("Classes resume on Monday at " + f.tenant().getName());

		Map<String, Object> wa = deliveryRow(view.id(), f.other().getId(), "WHATSAPP");
		assertThat(wa).containsEntry("status", "SENT");
		assertThat((String) wa.get("provider_message_id")).startsWith("wamid.");
		Recorded waRequest = VENDOR.lastContaining("welcome_term");
		assertThat(waRequest.path()).endsWith("/106540000000011/messages");
		assertThat(waRequest.authorization()).isEqualTo("Bearer " + waToken);
		assertThat(waRequest.body()).contains("\"to\":\"94771230299\"")
			.contains("\"language\":{\"code\":\"en\"}");
		// WhatsApp parameters follow the approved template ({{name}}, {{instituteName}}), even
		// though this broadcast's copy only uses {{instituteName}} (ADR-024 V4).
		JsonNode parameters = objectMapper.readTree(waRequest.body()).at("/template/components/0/parameters");
		assertThat(parameters.size()).isEqualTo(2);
		assertThat(parameters.get(0).get("text").asString()).isEqualTo("Bimal Other");
		assertThat(parameters.get(1).get("text").asString()).isEqualTo(f.tenant().getName());
	}

	@Test
	void transientVendorFailuresAreRetriedAndAuthFailuresFailAtOnce() {
		CommsFixture f = seedFixture("msg-errors");
		configureTextLk(f, "textlk-token-" + UUID.randomUUID());
		jdbcTemplate.update("UPDATE student_profile SET mobile = '0770000429' WHERE user_id = ?", f.enrolled().getId());
		jdbcTemplate.update("UPDATE student_profile SET mobile = '0770000401' WHERE user_id = ?", f.other().getId());

		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.enrolled().getId(), f.other().getId()));
		BroadcastView view = sendOrFail(f, f.adminToken(), broadcastBody(selected, List.of("SMS")));
		dispatchDue(view.id());

		assertThat(deliveryRow(view.id(), f.enrolled().getId(), "SMS")).containsEntry("status", "PENDING")
			.containsEntry("last_error_code", "PROVIDER_TRANSIENT");
		assertThat(deliveryRow(view.id(), f.other().getId(), "SMS")).containsEntry("status", "FAILED")
			.containsEntry("last_error_code", "PROVIDER_AUTH_REJECTED");
	}

	// ---- Meta status webhook ----

	@Test
	void verifiedStatusCallbacksMoveTheDeliveryForwardOnly(CapturedOutput output) {
		CommsFixture f = seedFixture("msg-webhook");
		String appSecret = "meta-secret-" + UUID.randomUUID();
		JsonNode account = configureMeta(f, "106540000000021", "meta-token-" + UUID.randomUUID(), appSecret);
		String key = webhookKey(account);
		String verifyToken = account.path("webhook").path("verifyToken").asString();
		UUID deliveryBroadcast = sendWhatsApp(f);
		String wamid = (String) deliveryRow(deliveryBroadcast, f.other().getId(), "WHATSAPP")
			.get("provider_message_id");

		// Subscription handshake.
		MvcResult ok = perform(get(WEBHOOK + key).param("hub.mode", "subscribe")
			.param("hub.verify_token", verifyToken)
			.param("hub.challenge", "1158201444"));
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		assertThat(rawContent(ok)).isEqualTo("1158201444");
		assertThat(perform(get(WEBHOOK + key).param("hub.mode", "subscribe")
			.param("hub.verify_token", "wrong-token")
			.param("hub.challenge", "1")).getResponse().getStatus()).isEqualTo(403);

		// Delivered, then read; a late "delivered" never moves it back.
		assertThat(callback(key, appSecret, status("106540000000021", wamid, "delivered", null))).isEqualTo(200);
		assertThat(providerStatus(deliveryBroadcast, f)).isEqualTo("DELIVERED");
		assertThat(callback(key, appSecret, status("106540000000021", wamid, "read", null))).isEqualTo(200);
		assertThat(callback(key, appSecret, status("106540000000021", wamid, "delivered", null))).isEqualTo(200);
		assertThat(providerStatus(deliveryBroadcast, f)).isEqualTo("READ");
		assertThat(deliveryRow(deliveryBroadcast, f.other().getId(), "WHATSAPP"))
			.as("the dispatch lifecycle is untouched")
			.containsEntry("status", "SENT");

		// The staff delivery list shows it.
		assertThat(getRaw(f.host(), f.adminToken(), BROADCASTS + "/" + deliveryBroadcast + "/deliveries").body())
			.contains("\"providerStatus\":\"READ\"");

		// Bad signature, unknown key: rejected, nothing written.
		assertThat(callback(key, "not-the-secret", status("106540000000021", wamid, "failed", 131026)))
			.isEqualTo(401);
		assertThat(callback("x".repeat(43), appSecret, status("106540000000021", wamid, "failed", 131026)))
			.isEqualTo(401);
		assertThat(providerStatus(deliveryBroadcast, f)).isEqualTo("READ");
		assertThat(output.getAll()).doesNotContain(appSecret).contains("messaging.meta_webhook_rejected");
	}

	@Test
	void aFailedStatusRecordsMetaErrorCodeAndAWrongPhoneNumberIdIsIgnored() {
		CommsFixture f = seedFixture("msg-webhook-fail");
		String appSecret = "meta-secret-" + UUID.randomUUID();
		String key = webhookKey(configureMeta(f, "106540000000031", "meta-token-" + UUID.randomUUID(), appSecret));
		UUID broadcast = sendWhatsApp(f);
		String wamid = (String) deliveryRow(broadcast, f.other().getId(), "WHATSAPP").get("provider_message_id");

		assertThat(callback(key, appSecret, status("999999999999", wamid, "failed", 131026))).isEqualTo(200);
		assertThat(providerStatus(broadcast, f)).as("another phone number's callback changes nothing").isNull();

		assertThat(callback(key, appSecret, status("106540000000031", wamid, "failed", 131026))).isEqualTo(200);
		assertThat(deliveryRow(broadcast, f.other().getId(), "WHATSAPP")).containsEntry("provider_status", "FAILED")
			.containsEntry("provider_error_code", "131026");
	}

	@Test
	void oneTenantsWebhookCanNeverUpdateAnotherTenantsDelivery() {
		CommsFixture a = seedFixture("msg-xt-a");
		CommsFixture b = seedFixture("msg-xt-b");
		String secretA = "meta-secret-a-" + UUID.randomUUID();
		String secretB = "meta-secret-b-" + UUID.randomUUID();
		String keyA = webhookKey(configureMeta(a, "106540000000041", "meta-token-a-" + UUID.randomUUID(), secretA));
		String keyB = webhookKey(configureMeta(b, "106540000000042", "meta-token-b-" + UUID.randomUUID(), secretB));
		UUID broadcastB = sendWhatsApp(b);
		String wamidB = (String) deliveryRow(broadcastB, b.other().getId(), "WHATSAPP").get("provider_message_id");

		// Tenant A's URL + A's secret, naming tenant B's message (even with B's phone number ID): no effect.
		assertThat(callback(keyA, secretA, status("106540000000041", wamidB, "read", null))).isEqualTo(200);
		assertThat(callback(keyA, secretA, status("106540000000042", wamidB, "read", null))).isEqualTo(200);
		// Tenant A's URL signed with tenant B's secret: rejected.
		assertThat(callback(keyA, secretB, status("106540000000042", wamidB, "read", null))).isEqualTo(401);
		assertThat(providerStatus(broadcastB, b)).isNull();

		// Tenant B's own URL and secret work.
		assertThat(callback(keyB, secretB, status("106540000000042", wamidB, "delivered", null))).isEqualTo(200);
		assertThat(providerStatus(broadcastB, b)).isEqualTo("DELIVERED");
	}

	// ---- helpers ----

	private JsonNode data(Raw raw) {
		assertThat(raw.status()).as(raw.body()).isEqualTo(200);
		return objectMapper.readTree(raw.body()).path("data");
	}

	private void configureTextLk(CommsFixture f, String token) {
		data(putRaw(f.host(), f.adminToken(), MESSAGING + "/SMS",
				Map.of("provider", "TEXTLK", "senderId", "ABCInst", "credentials", Map.of("apiToken", token))));
	}

	private JsonNode configureMeta(CommsFixture f, String phoneNumberId, String accessToken, String appSecret) {
		return data(putRaw(f.host(), f.adminToken(), MESSAGING + "/WHATSAPP",
				Map.of("provider", "META_WHATSAPP", "senderId", phoneNumberId, "credentials",
						Map.of("accessToken", accessToken, "appSecret", appSecret), "templateLanguage", "en")));
	}

	private static String webhookKey(JsonNode account) {
		String url = account.path("webhook").path("callbackUrl").asString();
		return url.substring(url.lastIndexOf('/') + 1);
	}

	private void optIntoWhatsApp(CommsFixture f) {
		String otherToken = loginAndGetToken(f.host(), "other@example.test");
		assertThat(putRaw(f.host(), otherToken, "/api/v1/notifications/preferences", Map.of("WHATSAPP", true))
			.status()).isEqualTo(200);
	}

	private UUID approvedTemplate(CommsFixture f, String metaName, String body) {
		TemplateView template = readData(postRaw(f.host(), f.adminToken(), "/api/v1/communications/templates",
				Map.of("name", "Welcome " + metaName, "subject", "Welcome", "body", body)), TemplateView.class);
		String status = "/api/v1/communications/templates/" + template.id() + "/whatsapp-status";
		postRaw(f.host(), f.adminToken(), status, Map.of("action", "SUBMIT", "providerTemplateName", metaName));
		postRaw(f.host(), f.adminToken(), status, Map.of("action", "APPROVE"));
		return template.id();
	}

	/** A WhatsApp broadcast to {@code other}, dispatched; returns the broadcast id. */
	private UUID sendWhatsApp(CommsFixture f) {
		optIntoWhatsApp(f);
		UUID templateId = approvedTemplate(f, "status_check", "Hi {{name}}.");
		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId()));
		Map<String, Object> body = broadcastBody(selected, List.of("WHATSAPP"));
		body.put("templateId", templateId);
		BroadcastView view = sendOrFail(f, f.adminToken(), body);
		dispatchDue(view.id());
		assertThat(deliveryRow(view.id(), f.other().getId(), "WHATSAPP")).containsEntry("status", "SENT");
		return view.id();
	}

	private String providerStatus(UUID broadcastId, CommsFixture f) {
		return (String) deliveryRow(broadcastId, f.other().getId(), "WHATSAPP").get("provider_status");
	}

	private static String status(String phoneNumberId, String wamid, String status, Integer errorCode) {
		String errors = (errorCode == null) ? ""
				: ",\"errors\":[{\"code\":" + errorCode + ",\"title\":\"Message undeliverable.\"}]";
		return "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"102290129340398\",\"changes\":[{"
				+ "\"field\":\"messages\",\"value\":{\"messaging_product\":\"whatsapp\",\"metadata\":{"
				+ "\"display_phone_number\":\"94110000000\",\"phone_number_id\":\"" + phoneNumberId + "\"},"
				+ "\"statuses\":[{\"id\":\"" + wamid + "\",\"status\":\"" + status + "\",\"timestamp\":\""
				+ (System.currentTimeMillis() / 1000) + "\",\"recipient_id\":\"94771230299\"" + errors + "}]}}]}]}";
	}

	private int callback(String key, String appSecret, String json) {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		return perform(post(WEBHOOK + key).contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("X-Hub-Signature-256", "sha256=" + MetaWebhookSignatures.sign(appSecret, body))).getResponse()
			.getStatus();
	}

}

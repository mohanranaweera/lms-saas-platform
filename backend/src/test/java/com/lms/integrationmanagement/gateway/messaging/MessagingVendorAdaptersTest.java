package com.lms.integrationmanagement.gateway.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.config.MessagingProperties;
import com.lms.integrationmanagement.gateway.messaging.FakeMessagingVendorServer.Recorded;
import com.lms.integrationmanagement.gateway.messaging.MessagingProviderAdapter.SendRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-PROV-01 / ADR-024: the Text.lk and Meta WhatsApp adapters against a
 * local fake vendor - request shape, auth header, number normalisation, the
 * transient/permanent error split, and that no token, number or vendor
 * response text is ever logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class MessagingVendorAdaptersTest {

	private static final String TEXTLK_TOKEN = "textlk-test-token-ABCDEFGH";

	private static final String META_TOKEN = "meta-test-access-token-XYZ123";

	private static final String META_APP_SECRET = "meta-test-app-secret-QRS789";

	private final FakeMessagingVendorServer vendor = FakeMessagingVendorServer.get();

	private TextLkMessagingAdapter textLk;

	private MetaWhatsAppMessagingAdapter meta;

	private MessagingProperties properties;

	@BeforeEach
	void setUp() {
		properties = new MessagingProperties();
		properties.getTextlk().setBaseUrl(vendor.baseUrl() + "/");
		properties.getMetaWhatsapp().setBaseUrl(vendor.baseUrl());
		MessagingHttpClient http = new MessagingHttpClient(new ObjectMapper());
		textLk = new TextLkMessagingAdapter(properties, http);
		meta = new MetaWhatsAppMessagingAdapter(properties, http);
	}

	private static SendRequest sms(String destination, String body) {
		return new SendRequest(Channel.SMS, Map.of("apiToken", TEXTLK_TOKEN), "ABCInst", null, destination, body,
				null, null);
	}

	private static SendRequest whatsApp(String destination, String template, List<String> parameters) {
		return new SendRequest(Channel.WHATSAPP, Map.of("accessToken", META_TOKEN, "appSecret", META_APP_SECRET),
				"106540352242922", "si", destination, "ignored body", template, parameters);
	}

	// ---- Text.lk ----

	@Test
	void textLkSendsAPlainSmsWithTheTenantTokenAndANormalisedNumber() {
		String id = textLk.send(sms("077 123 4567", "Classes resume Monday."));

		assertThat(id).startsWith("tlk-");
		Recorded request = vendor.lastContaining("Classes resume Monday.");
		assertThat(request.path()).isEqualTo("/api/v3/sms/send");
		assertThat(request.authorization()).isEqualTo("Bearer " + TEXTLK_TOKEN);
		assertThat(request.body()).contains("\"recipient\":\"94771234567\"")
			.contains("\"sender_id\":\"ABCInst\"")
			.contains("\"type\":\"plain\"");
	}

	@Test
	void textLkMapsVendorFailuresToTransientOrPermanentCodes(CapturedOutput output) {
		assertFailure(() -> textLk.send(sms("+94770000429", "x")), MessagingSendException.PROVIDER_TRANSIENT, true);
		assertFailure(() -> textLk.send(sms("+94770000500", "x")), MessagingSendException.PROVIDER_TRANSIENT, true);
		assertFailure(() -> textLk.send(sms("+94770000401", "x")), MessagingSendException.PROVIDER_AUTH_REJECTED,
				false);
		assertFailure(() -> textLk.send(sms("+94770001026", "x")), MessagingSendException.PROVIDER_REJECTED, false);
		assertFailure(() -> textLk.send(sms("12", "x")), MessagingSendException.INVALID_DESTINATION, false);

		assertThat(output.getAll()).doesNotContain(TEXTLK_TOKEN)
			.doesNotContain("94770001026")
			.doesNotContain("Invalid recipient");
	}

	@Test
	void textLkAcceptsOnlyItsOwnSenderIdShapeAndOnlySms() {
		assertThat(textLk.validateSenderId("ABCInst")).isNull();
		assertThat(textLk.validateSenderId("+94112345678")).isNull();
		assertThat(textLk.validateSenderId("ABC Institute Colombo")).isNotNull();
		assertThat(textLk.supportedChannels()).containsExactly(Channel.SMS);
		assertThat(textLk.requiredCredentialKeys()).containsExactly("apiToken");
	}

	@Test
	void aDisabledVendorRefusesToSend() {
		properties.getTextlk().setEnabled(false);
		assertFailure(() -> textLk.send(sms("+94771234567", "x")), MessagingSendException.CHANNEL_NOT_AVAILABLE,
				false);
	}

	// ---- Meta WhatsApp ----

	@Test
	void metaSendsATemplateMessageWithLanguageAndOrderedBodyParameters() {
		String id = meta.send(whatsApp("+94 77 123 0299", "term_start", List.of("Amara", "ABC Institute")));

		assertThat(id).startsWith("wamid.");
		Recorded request = vendor.lastContaining("term_start");
		assertThat(request.path()).isEqualTo("/v23.0/106540352242922/messages");
		assertThat(request.authorization()).isEqualTo("Bearer " + META_TOKEN);
		assertThat(request.body()).contains("\"messaging_product\":\"whatsapp\"")
			.contains("\"to\":\"94771230299\"")
			.contains("\"type\":\"template\"")
			.contains("\"language\":{\"code\":\"si\"}")
			.doesNotContain(META_APP_SECRET)
			.doesNotContain("ignored body");
		var parameters = new ObjectMapper().readTree(request.body())
			.at("/template/components/0/parameters");
		assertThat(parameters.size()).isEqualTo(2);
		assertThat(parameters.get(0).get("type").asString()).isEqualTo("text");
		assertThat(parameters.get(0).get("text").asString()).isEqualTo("Amara");
		assertThat(parameters.get(1).get("text").asString()).isEqualTo("ABC Institute");
	}

	@Test
	void metaClassifiesErrorsByMetaErrorCode(CapturedOutput output) {
		assertFailure(() -> meta.send(whatsApp("+94770000429", "t", List.of())),
				MessagingSendException.PROVIDER_TRANSIENT, true);
		assertFailure(() -> meta.send(whatsApp("+94770000500", "t", List.of())),
				MessagingSendException.PROVIDER_TRANSIENT, true);
		assertFailure(() -> meta.send(whatsApp("+94770000401", "t", List.of())),
				MessagingSendException.PROVIDER_AUTH_REJECTED, false);
		assertFailure(() -> meta.send(whatsApp("+94770001026", "t", List.of())),
				MessagingSendException.PROVIDER_REJECTED, false);

		assertThat(output.getAll()).contains("131026")
			.doesNotContain(META_TOKEN)
			.doesNotContain("94770001026")
			.doesNotContain("Message undeliverable");
	}

	@Test
	void metaTestSendUsesTheHelloWorldTemplateAndAPhoneNumberIdSender() {
		assertThat(meta.testMessage().templateName()).isEqualTo("hello_world");
		assertThat(meta.testMessage().templateLanguage()).isEqualTo("en_US");
		assertThat(meta.validateSenderId("106540352242922")).isNull();
		assertThat(meta.validateSenderId("+94112345678")).isNotNull();
		assertThat(meta.requiredCredentialKeys()).containsExactlyInAnyOrder("accessToken", "appSecret");
		assertThat(meta.requiredSettingKeys()).containsExactly("templateLanguage");
	}

	@Test
	void aSendRequestNeverRendersItsSecretsOrDestination() {
		assertThat(whatsApp("+94771230299", "t", List.of()).toString()).doesNotContain(META_TOKEN)
			.doesNotContain(META_APP_SECRET)
			.doesNotContain("94771230299");
	}

	private static void assertFailure(Runnable call, String code, boolean retryable) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(MessagingSendException.class, ex -> {
			assertThat(ex.getErrorCode()).isEqualTo(code);
			assertThat(ex.isRetryable()).isEqualTo(retryable);
		});
	}

}

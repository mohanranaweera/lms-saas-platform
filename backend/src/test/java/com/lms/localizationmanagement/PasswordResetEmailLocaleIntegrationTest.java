package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.notificationmanagement.service.NotificationDispatchService;
import com.lms.notificationmanagement.service.NotificationTemplateSeedingService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * I18N-01: the password-reset email is rendered in the recipient's language (saved preference
 * -> tenant default -> en-LK); English output is byte-for-byte the pre-I18N text; a
 * tenant-customized template is always sent as written.
 */
class PasswordResetEmailLocaleIntegrationTest extends I18nTestSupport {

	private static final Pattern LINK = Pattern.compile("(https?://\\S+#token=\\S+)");

	private static final String SI_SUBJECT = "{tenantName} මුරපදය යළි සකසන්න";

	private static final String SI_BODY = "ආයුබෝවන්,\n\n{tenantName} මුරපදය යළි සැකසීමට: {link}\n\nකල් ඉකුත් වේ: {expiresIn}.\n";

	@Autowired
	private NotificationDispatchService dispatchService;

	@BeforeEach
	void reset() {
		clearInvocations(messagingProviderApi);
	}

	private record Sent(String subject, String body) {
	}

	private Sent requestResetAndDispatch(Fx fx, String email) {
		MvcResult requested = json(fx.host(), null, post("/api/v1/auth/password-reset/request"), Map.of("email", email));
		assertThat(status(requested)).as(rawContent(requested)).isEqualTo(202);
		List<UUID> rows = List.of();
		for (int i = 0; i < 50 && rows.isEmpty(); i++) {
			rows = jdbcTemplate.queryForList("SELECT id FROM notification_outbox WHERE tenant_id = ? AND "
					+ "event_type = 'PASSWORD_RESET' AND status = 'PENDING'", UUID.class, fx.tenantId());
			if (rows.isEmpty()) {
				try {
					Thread.sleep(100);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}
		assertThat(rows).hasSize(1);
		dispatchService.dispatchOne(rows.get(0));
		ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(messagingProviderApi).sendEmail(eq(email), subject.capture(), body.capture());
		clearInvocations(messagingProviderApi);
		return new Sent(subject.getValue(), body.getValue());
	}

	private String tenantName(Fx fx) {
		return jdbcTemplate.queryForObject("SELECT name FROM tenant WHERE id = ?", String.class, fx.tenantId());
	}

	private static String link(String body) {
		Matcher matcher = LINK.matcher(body);
		assertThat(matcher.find()).as(body).isTrue();
		return matcher.group(1);
	}

	@Test
	void englishRecipientsGetTheUnchangedEmail() {
		Fx fx = seed("i18n-mail-en");
		seedActiveStudent(fx.tenantId(), "student@example.test");
		Sent sent = requestResetAndDispatch(fx, "student@example.test");
		String name = org.springframework.web.util.HtmlUtils.htmlEscape(tenantName(fx));
		assertThat(sent.subject()).isEqualTo(
				NotificationTemplateSeedingService.PASSWORD_RESET_SUBJECT.replace("{{tenantName}}", name));
		String expectedBody = NotificationTemplateSeedingService.PASSWORD_RESET_BODY.replace("{{tenantName}}", name)
			.replace("{{link}}", link(sent.body()))
			.replace("{{expiresIn}}", "60 minutes");
		assertThat(sent.body()).isEqualTo(expectedBody);
	}

	@Test
	void recipientPreferenceThenTenantDefaultDecideTheLanguage() {
		Fx fx = seed("i18n-mail-si");
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", false);
		saveDraftOk(fx, "si-LK", "email.passwordReset.subject", SI_SUBJECT, null);
		saveDraftOk(fx, "si-LK", "email.passwordReset.body", SI_BODY, null);
		saveDraftOk(fx, "si-LK", PLURAL_KEY, "{count, plural, one {මිනිත්තු #} other {මිනිත්තු #}}", null);
		publishOk(fx, "si-LK", 0);

		String student = userToken(fx, "sinhala@example.test", Role.STUDENT);
		seedActiveStudent(fx.tenantId(), "english@example.test");
		assertThat(status(json(fx.host(), student, put("/api/v1/me/locale"), Map.of("locale", "si-LK"))))
			.isEqualTo(200);

		Sent sinhala = requestResetAndDispatch(fx, "sinhala@example.test");
		assertThat(sinhala.subject()).isEqualTo(
				org.springframework.web.util.HtmlUtils.htmlEscape(tenantName(fx)) + " මුරපදය යළි සකසන්න");
		assertThat(sinhala.body()).startsWith("ආයුබෝවන්,").contains("#token=").endsWith("කල් ඉකුත් වේ: මිනිත්තු 60.\n");

		// No preference: tenant default (English) - unchanged text.
		Sent english = requestResetAndDispatch(fx, "english@example.test");
		assertThat(english.subject()).startsWith("Reset your ");

		// Tenant default Sinhala: users without a preference get Sinhala too.
		setLanguagesOk(fx, null, "si-LK", null);
		Sent byDefault = requestResetAndDispatch(fx, "english@example.test");
		assertThat(byDefault.subject()).endsWith("මුරපදය යළි සකසන්න");
	}

	@Test
	void aCustomizedTemplateIsSentAsWritten() {
		Fx fx = seed("i18n-mail-custom");
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "si-LK", false);
		saveDraftOk(fx, "si-LK", "email.passwordReset.subject", SI_SUBJECT, null);
		publishOk(fx, "si-LK", 0);
		seedActiveStudent(fx.tenantId(), "student@example.test");
		// First send seeds the default template; then the tenant customizes it.
		requestResetAndDispatch(fx, "student@example.test");
		jdbcTemplate.update("UPDATE notification_template SET subject = 'Custom reset for {{tenantName}}' "
				+ "WHERE tenant_id = ? AND template_key = 'PASSWORD_RESET'", fx.tenantId());
		Sent custom = requestResetAndDispatch(fx, "student@example.test");
		assertThat(custom.subject()).startsWith("Custom reset for ");
		verifyNoStrayEmail();
	}

	private void verifyNoStrayEmail() {
		org.mockito.Mockito.verify(messagingProviderApi, org.mockito.Mockito.never())
			.sendEmail(anyString(), anyString(), anyString());
	}

}

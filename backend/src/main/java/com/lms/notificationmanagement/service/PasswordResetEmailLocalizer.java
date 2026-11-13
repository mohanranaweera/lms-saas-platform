package com.lms.notificationmanagement.service;

import com.lms.localizationmanagement.api.LocaleApi;
import com.lms.localizationmanagement.api.Translator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * I18N-01: renders the platform-default password-reset email in the recipient's language
 * (saved preference -> tenant default -> {@code en-LK}) from the registered
 * {@code email.passwordReset.*} messages (i18n/source/email.json). The English source text is
 * identical to {@link NotificationTemplateSeedingService#PASSWORD_RESET_BODY}, so English output
 * is unchanged. Every interpolated value is HTML-escaped exactly like
 * {@link NotificationTemplateRenderer} does. Runs on the dispatcher thread, which has set the
 * outbox row's tenant as the {@code TenantContext}.
 */
@Component
public class PasswordResetEmailLocalizer {

	/** The reset link lifetime the email states (the token TTL, unchanged from the template). */
	static final int RESET_LINK_MINUTES = 60;

	private final LocaleApi localeApi;

	private final Translator translator;

	public PasswordResetEmailLocalizer(LocaleApi localeApi, Translator translator) {
		this.localeApi = localeApi;
		this.translator = translator;
	}

	public RenderedNotification render(UUID tenantId, UUID recipientUserId, Map<String, Object> variables) {
		String locale = localeApi.recipientLocale(tenantId, recipientUserId);
		String expiresIn = translator.msg("email.common.expiresInMinutes", locale,
				Map.of("count", RESET_LINK_MINUTES));
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("tenantName", escape(variables.get("tenantName")));
		params.put("link", escape(variables.get("link")));
		params.put("expiresIn", escape(expiresIn));
		return new RenderedNotification(translator.msg("email.passwordReset.subject", locale, params),
				translator.msg("email.passwordReset.body", locale, params));
	}

	private static String escape(Object value) {
		return HtmlUtils.htmlEscape(value == null ? "" : String.valueOf(value));
	}

}

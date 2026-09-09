package com.lms.notificationmanagement.service;

import com.lms.notificationmanagement.domain.NotificationTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Pure, no persistence/IO. Replaces every {@code {{key}}} token in a
 * template's subject/body with the corresponding rendering variable, per
 * plan §9.2/§12. Fails closed (throws {@link TemplateRenderingException}) on
 * any unresolvable token instead of emitting the raw {@code {{token}}} text
 * or a partially-rendered string - the caller ({@link
 * NotificationDispatchService}) is responsible for catching this and marking
 * that row {@code FAILED}.
 *
 * <h2>Substituted values are HTML-escaped (security-review finding)</h2>
 * At least one rendering variable this module ever substitutes -
 * {@code reason} on a {@code PAYMENT_REFUNDED} event - is free text supplied
 * by an authenticated Finance Staff/Institute Owner ({@code
 * RefundService#processRefund}), not a system-generated value. The rendered
 * body/subject is both emailed as plain text ({@code SmtpMessagingProviderApi}
 * uses {@code SimpleMailMessage}, safe either way) AND stored verbatim as
 * {@code in_app_notification.body}, returned as-is via {@code
 * NotificationController}'s JSON response. Escaping every substituted value
 * here - not just the ones a caller happens to know are free text - is
 * defense in depth against a stored-content-injection vector should the
 * Notification Center frontend ever render {@code body} as HTML: template
 * literal text itself is still author-controlled and never escaped, and
 * plain values (amounts, currency codes, UUIDs) contain no HTML-special
 * characters, so this has no visible effect on any currently-seeded
 * template's rendered output.
 */
@Component
public class NotificationTemplateRenderer {

	private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

	/**
	 * The one rendering variable every currently-seeded template (plan
	 * §9.1/§9.3's {@code PAYMENT_CONFIRMED}/{@code PAYMENT_REJECTED}/{@code
	 * PAYMENT_REFUNDED} payloads) substitutes that is genuinely a currency
	 * value - see {@link #formatVariableValue} for why it alone gets
	 * explicit 2-decimal formatting here.
	 */
	private static final String AMOUNT_VARIABLE_KEY = "amount";

	public RenderedNotification render(NotificationTemplate template, Map<String, Object> variables) {
		String subject = renderString(template.getSubject(), variables);
		String body = renderString(template.getBody(), variables);
		return new RenderedNotification(subject, body);
	}

	private String renderString(String source, Map<String, Object> variables) {
		Matcher matcher = TOKEN_PATTERN.matcher(source);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String key = matcher.group(1);
			if (!variables.containsKey(key)) {
				throw new TemplateRenderingException("Template references unresolvable variable: " + key);
			}
			String escapedValue = HtmlUtils.htmlEscape(formatVariableValue(key, variables.get(key)));
			matcher.appendReplacement(result, Matcher.quoteReplacement(escapedValue));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Post-ship review finding: a {@code BigDecimal} amount (e.g. {@code new
	 * BigDecimal("5.00")}) that has already round-tripped through JSONB into
	 * this generically-typed {@code Map<String,Object>} ({@code
	 * NotificationDispatchService}'s {@code objectMapper.readValue(...,
	 * Map<String,Object>)} with no target type) arrives here as a
	 * {@code Double}/{@code Integer}/{@code String} with no guaranteed
	 * scale - a raw {@code String.valueOf} on that value would silently
	 * render {@code "5.0"} instead of {@code "5.00"}. Explicitly
	 * re-normalizing to a fixed 2-decimal scale here, at render time, fixes
	 * this regardless of which numeric type the value happens to have
	 * deserialized as. Every other variable this module ever substitutes
	 * (currency codes, UUIDs, free-text reasons) is left exactly as-is, via
	 * the same plain {@code String.valueOf} fallback used both for those
	 * keys and for a genuinely non-numeric {@code amount} value (defensive -
	 * never worth failing template rendering over a formatting nicety).
	 */
	private static String formatVariableValue(String key, Object value) {
		if (AMOUNT_VARIABLE_KEY.equals(key) && value != null) {
			try {
				return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP).toPlainString();
			}
			catch (NumberFormatException ex) {
				// Not a genuinely numeric amount value - fall through.
			}
		}
		return String.valueOf(value);
	}

}

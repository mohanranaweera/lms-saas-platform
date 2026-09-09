package com.lms.notificationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.notificationmanagement.domain.NotificationTemplate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit, no-Spring-context, no-Testcontainers unit coverage for {@link
 * NotificationTemplateRenderer} (MVP-018 plan §18): correct {@code {{token}}}
 * variable substitution across subject and body, and the fail-closed
 * behavior on an unresolvable variable - a {@link TemplateRenderingException}
 * is thrown rather than the raw {@code {{token}}} text, a partially-rendered
 * string, or a raw exception message leaking into what would become an
 * emailed body (plan §12).
 */
class NotificationTemplateRendererTest {

	private final NotificationTemplateRenderer renderer = new NotificationTemplateRenderer();

	private static NotificationTemplate template(String subject, String body) {
		return new NotificationTemplate(UUID.randomUUID(), "PAYMENT_CONFIRMED", subject, body);
	}

	@Test
	void substitutesEveryTokenInBothSubjectAndBodyFromThePayloadMap() {
		NotificationTemplate template = template("Payment Confirmed - {{amount}} {{currency}}",
				"Hi,\n\nYour payment of {{amount}} {{currency}} (reference {{paymentId}}) has been confirmed.\n");
		Map<String, Object> variables = Map.of("amount", new BigDecimal("49.99"), "currency", "USD", "paymentId",
				"11111111-1111-1111-1111-111111111111");

		RenderedNotification rendered = renderer.render(template, variables);

		assertThat(rendered.subject()).isEqualTo("Payment Confirmed - 49.99 USD");
		assertThat(rendered.body()).isEqualTo(
				"Hi,\n\nYour payment of 49.99 USD (reference 11111111-1111-1111-1111-111111111111) has been confirmed.\n");
	}

	@Test
	void aTemplateWithNoTokensRendersUnchanged() {
		NotificationTemplate template = template("Static subject", "Static body, no placeholders here.");

		RenderedNotification rendered = renderer.render(template, Map.of());

		assertThat(rendered.subject()).isEqualTo("Static subject");
		assertThat(rendered.body()).isEqualTo("Static body, no placeholders here.");
	}

	@Test
	void theSameTokenRepeatedMultipleTimesIsSubstitutedEverywhere() {
		NotificationTemplate template = template("{{amount}} received", "You paid {{amount}}. Confirmed: {{amount}}.");

		RenderedNotification rendered = renderer.render(template, Map.of("amount", "20.00"));

		assertThat(rendered.subject()).isEqualTo("20.00 received");
		assertThat(rendered.body()).isEqualTo("You paid 20.00. Confirmed: 20.00.");
	}

	@Test
	void aSubjectTokenMissingFromThePayloadFailsClosedWithoutLeakingRawExceptionTextAsOutput() {
		NotificationTemplate template = template("Refund of {{amount}} processed", "Reason: {{reason}}");

		assertThatThrownBy(() -> renderer.render(template, Map.of("reason", "duplicate charge")))
			.isInstanceOf(TemplateRenderingException.class)
			.hasMessageContaining("amount");
	}

	@Test
	void aBodyTokenMissingFromThePayloadFailsClosedEvenWhenTheSubjectRendersFine() {
		NotificationTemplate template = template("Payment Rejected", "Reference {{paymentId}}, reason {{reason}}");

		assertThatThrownBy(() -> renderer.render(template, Map.of("paymentId", "abc-123")))
			.isInstanceOf(TemplateRenderingException.class)
			.hasMessageContaining("reason");
	}

	@Test
	void aTokenValueContainingDollarSignsAndBackslashesIsSubstitutedLiterallyWithoutBreakingRendering() {
		// Realistic staff-supplied free text (RefundService's reason field),
		// not a contrived input - regex replacement strings treat $ and \
		// specially unless quoted, which is exactly what production code's
		// Matcher.quoteReplacement call is for.
		NotificationTemplate template = template("Refund of {{amount}} processed", "Reason: {{reason}}");

		RenderedNotification rendered = renderer.render(template,
				Map.of("amount", "5.00", "reason", "duplicate $5 charge, ref \\batch-12"));

		assertThat(rendered.body()).isEqualTo("Reason: duplicate $5 charge, ref \\batch-12");
	}

	@Test
	void aTokenValueContainingLiteralDoubleCurlyTextIsNotReRenderedAsASecondPassToken() {
		NotificationTemplate template = template("Payment Confirmed", "Note: {{note}}");

		RenderedNotification rendered = renderer.render(template, Map.of("note", "see {{amount}} above"));

		// The literal text "{{amount}}" that CAME FROM a variable's own value
		// must appear verbatim in the output, not be interpreted as a second
		// token to resolve - rendering is single-pass over the TEMPLATE's own
		// tokens only.
		assertThat(rendered.body()).isEqualTo("Note: see {{amount}} above");
	}

	@Test
	void anUnclosedTokenIsTreatedAsLiteralTextRatherThanFailingClosed() {
		// Documents current, intended regex behavior (\{\{(\w+)}} requires a
		// closing "}}") rather than leaving it as an untested assumption -
		// an unclosed "{{" never matches TOKEN_PATTERN at all, so it passes
		// through unchanged like any other literal text, it does NOT throw.
		NotificationTemplate template = template("Payment Confirmed", "Amount due: {{amount missing close brace");

		RenderedNotification rendered = renderer.render(template, Map.of());

		assertThat(rendered.body()).isEqualTo("Amount due: {{amount missing close brace");
	}

	@Test
	void aTokenValueContainingHtmlSpecialCharactersIsEscapedInTheRenderedOutput() {
		// Defense-in-depth against a stored-content-injection vector if the
		// Notification Center frontend ever renders in_app_notification.body
		// as HTML - see NotificationTemplateRenderer's javadoc.
		NotificationTemplate template = template("Payment Refunded", "Reason: {{reason}}");

		RenderedNotification rendered = renderer.render(template,
				Map.of("reason", "<script>alert('x')</script> & \"quoted\""));

		assertThat(rendered.body()).isEqualTo(
				"Reason: &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;quoted&quot;");
		assertThat(rendered.body()).doesNotContain("<script>");
	}

}

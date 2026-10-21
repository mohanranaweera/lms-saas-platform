package com.lms.integrationmanagement.gateway.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.integrationmanagement.api.MessagingChannelApi.Channel;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.api.MessagingChannelApi.OutboundMessage;
import com.lms.integrationmanagement.gateway.messaging.MessagingProviderAdapter.SendRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wave 11 sandbox adapter (decision D1): fails closed when disabled (every
 * real environment), deterministic failure numbers, and nothing sensitive in
 * what it keeps or prints.
 */
class SandboxMessagingAdapterTest {

	private static final Map<String, String> CREDENTIALS = Map.of("apiKey", "sandbox-key");

	private static SendRequest request(Channel channel, String destination, String body, String template) {
		return new SendRequest(channel, CREDENTIALS, "ACADEMY", null, destination, body, template, null);
	}

	@Test
	void aDisabledSandboxRefusesToSendAndReportsItself() {
		SandboxMessagingAdapter adapter = new SandboxMessagingAdapter(false);
		assertThat(adapter.isEnabled()).isFalse();
		assertThatThrownBy(() -> adapter.send(request(Channel.SMS, "+94771234567", "hi", null)))
			.isInstanceOfSatisfying(MessagingSendException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(MessagingSendException.CHANNEL_NOT_AVAILABLE);
				assertThat(ex.isRetryable()).isFalse();
			});
	}

	@Test
	void deterministicNumbersDriveTransientAndPermanentFailures() {
		SandboxMessagingAdapter adapter = new SandboxMessagingAdapter(true);
		assertThatThrownBy(() -> adapter.send(request(Channel.SMS, "+94770000001", "hi", null)))
			.isInstanceOfSatisfying(MessagingSendException.class, ex -> assertThat(ex.isRetryable()).isTrue());
		assertThatThrownBy(() -> adapter.send(request(Channel.WHATSAPP, "+94770000002", "hi", "t")))
			.isInstanceOfSatisfying(MessagingSendException.class, ex -> {
				assertThat(ex.isRetryable()).isFalse();
				assertThat(ex.getMessage()).isEqualTo(MessagingSendException.PROVIDER_REJECTED)
					.doesNotContain("94770000002");
			});
	}

	@Test
	void aSuccessfulSendKeepsOnlyAMaskedDestination() {
		SandboxMessagingAdapter adapter = new SandboxMessagingAdapter(true);
		String id = adapter.send(request(Channel.SMS, "+94771234567", "hello", null));
		assertThat(id).startsWith("sandbox-");
		assertThat(adapter.recentMessages().get(0).maskedDestination()).isEqualTo("+947•••567");
		assertThat(adapter.recentMessages().toString()).doesNotContain("94771234567").doesNotContain("sandbox-key");
	}

	@Test
	void anOutboundMessageNeverRendersItsDestination() {
		assertThat(new OutboundMessage(Channel.SMS, "+94771234567", "hello", null, null).toString())
			.doesNotContain("94771234567");
	}

}

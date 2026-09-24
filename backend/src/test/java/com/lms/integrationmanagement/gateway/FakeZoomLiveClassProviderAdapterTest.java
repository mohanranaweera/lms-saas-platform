package com.lms.integrationmanagement.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.integrationmanagement.api.MeetingCreationResult;
import com.lms.integrationmanagement.api.ShortLivedJoinLink;
import com.lms.integrationmanagement.api.ShortLivedPlaybackLink;
import com.lms.integrationmanagement.config.LiveClassProviderProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit coverage for {@link FakeZoomLiveClassProviderAdapter}'s determinism/no-network shape (Wave 4 plan §8). */
class FakeZoomLiveClassProviderAdapterTest {

	private final LiveClassProviderProperties properties = new LiveClassProviderProperties();

	private final FakeZoomLiveClassProviderAdapter adapter = new FakeZoomLiveClassProviderAdapter(properties);

	@Test
	void createMeetingReturnsAUniqueOpaqueFakeReferenceEveryCall() {
		MeetingCreationResult first = adapter.createMeeting(UUID.randomUUID(), UUID.randomUUID(), "Title", Instant.now(),
				Instant.now().plusSeconds(3600));
		MeetingCreationResult second = adapter.createMeeting(UUID.randomUUID(), UUID.randomUUID(), "Title", Instant.now(),
				Instant.now().plusSeconds(3600));

		assertThat(first.providerReference()).startsWith("FAKE-ZOOM-");
		assertThat(second.providerReference()).startsWith("FAKE-ZOOM-");
		assertThat(first.providerReference()).isNotEqualTo(second.providerReference());
	}

	@Test
	void getJoinUrlReturnsAShortLivedFutureExpiringLink() {
		ShortLivedJoinLink link = adapter.getJoinUrl("FAKE-ZOOM-abc", UUID.randomUUID(), "PARTICIPANT", "user-123");

		assertThat(link.url()).startsWith("https://live-class-provider.test/join/");
		assertThat(link.expiresAt()).isAfter(Instant.now());
		assertThat(link.expiresAt()).isBefore(Instant.now().plusSeconds(3600));
	}

	@Test
	void getJoinUrlIsNeverTheSameTwice() {
		ShortLivedJoinLink first = adapter.getJoinUrl("FAKE-ZOOM-abc", UUID.randomUUID(), "PARTICIPANT", "user-123");
		ShortLivedJoinLink second = adapter.getJoinUrl("FAKE-ZOOM-abc", UUID.randomUUID(), "PARTICIPANT", "user-123");
		assertThat(first.url()).isNotEqualTo(second.url());
	}

	@Test
	void getRecordingPlaybackUrlReturnsAShortLivedFutureExpiringLink() {
		ShortLivedPlaybackLink link = adapter.getRecordingPlaybackUrl("FAKE-ZOOM-REC-abc", UUID.randomUUID());
		assertThat(link.url()).startsWith("https://live-class-provider.test/recordings/");
		assertThat(link.expiresAt()).isAfter(Instant.now());
	}

	@Test
	void cancelMeetingNeverThrowsForAnyReference() {
		adapter.cancelMeeting("FAKE-ZOOM-abc");
		adapter.cancelMeeting(null);
		adapter.cancelMeeting("does-not-exist");
	}

	// ------------------------------------------------------------------
	// Signature verification - fail-closed secret protection.
	// ------------------------------------------------------------------

	@Test
	void verifySignatureFailsClosedWhenSecretIsEmpty() {
		properties.setWebhookSecret("");
		String body = "{\"eventId\":\"evt-1\"}";
		String signature = WebhookSignatureVerifier.sign(body, "some-secret");

		assertThat(adapter.verifySignature(body, signature)).isFalse();
	}

	@Test
	void verifySignatureAcceptsAValidSignatureWithANonEmptySecret() {
		properties.setWebhookSecret("a-real-test-secret");
		String body = "{\"eventId\":\"evt-1\"}";
		String signature = WebhookSignatureVerifier.sign(body, "a-real-test-secret");

		assertThat(adapter.verifySignature(body, signature)).isTrue();
	}

	@Test
	void verifySignatureRejectsATamperedBody() {
		properties.setWebhookSecret("a-real-test-secret");
		String signature = WebhookSignatureVerifier.sign("{\"eventId\":\"evt-1\"}", "a-real-test-secret");

		assertThat(adapter.verifySignature("{\"eventId\":\"evt-2\"}", signature)).isFalse();
	}

}

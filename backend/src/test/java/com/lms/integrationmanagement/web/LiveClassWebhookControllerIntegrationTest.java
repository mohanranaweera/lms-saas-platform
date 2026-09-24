package com.lms.integrationmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.liveclassmanagement.LiveClassManagementTestSupport;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for {@code POST
 * /api/v1/integrations/webhooks/live-class} (Wave 4 plan §8): signature
 * -required with zero state change on failure, tenant resolution from the
 * platform's own record (never the payload), duplicate delivery is an
 * idempotent no-op, and an unknown/malformed event type is handled
 * gracefully (never a 500).
 */
class LiveClassWebhookControllerIntegrationTest extends LiveClassManagementTestSupport {

	@Test
	void missingSignatureIsRejectedWithZeroStateChange() {
		Long before = providerEventCount();

		HttpResult<Void> result = sendRawLiveClassWebhook("{\"eventId\":\"evt-1\",\"eventType\":\"recording.completed\"}",
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(providerEventCount()).isEqualTo(before);
	}

	@Test
	void invalidSignatureIsRejectedWithZeroStateChange() {
		Long before = providerEventCount();

		HttpResult<Void> result = sendRawLiveClassWebhook("{\"eventId\":\"evt-1\",\"eventType\":\"recording.completed\"}",
				"0000000000000000000000000000000000000000000000000000000000000000");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(providerEventCount()).isEqualTo(before);
	}

	@Test
	void duplicateDeliveryOfTheSameEventIdIsAnIdempotentNoOp() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-webhook-dup");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Webhook dup"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		completeSession(fixture.host(), fixture.teacherToken(), created.id());
		String providerReference = providerReferenceOf(created.id());
		String eventId = UUID.randomUUID().toString();

		HttpResult<Void> first = sendLiveClassWebhook(eventId, "recording.completed", providerReference,
				"FAKE-ZOOM-REC-dup", 120);
		HttpResult<Void> second = sendLiveClassWebhook(eventId, "recording.completed", providerReference,
				"FAKE-ZOOM-REC-dup-2", 999);

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		Long ledgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM class_session_provider_event WHERE provider_event_id = ?", Long.class, eventId);
		assertThat(ledgerCount).isEqualTo(1L);
		Long recordingCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM class_session_recording WHERE session_id = ?", Long.class, created.id());
		assertThat(recordingCount).isEqualTo(1L);
		// The second (duplicate) delivery's payload must never overwrite the
		// first's - the duration/reference stay from the first delivery.
		Integer durationSeconds = jdbcTemplate.queryForObject(
				"SELECT duration_seconds FROM class_session_recording WHERE session_id = ?", Integer.class,
				created.id());
		assertThat(durationSeconds).isEqualTo(120);
	}

	@Test
	void unknownEventTypeIsHandledGracefullyNeverA500() {
		HttpResult<Void> result = sendLiveClassWebhook(UUID.randomUUID().toString(), "meeting.some_future_event_type",
				"FAKE-ZOOM-does-not-matter", null, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void malformedPayloadMissingEventIdIsHandledGracefullyAndNotRecorded() {
		Long before = providerEventCount();
		String body = "{\"eventType\":\"recording.completed\",\"providerReference\":\"FAKE-ZOOM-x\"}";
		String signature = com.lms.integrationmanagement.gateway.WebhookSignatureVerifier.sign(body,
				LIVE_CLASS_TEST_WEBHOOK_SECRET);

		HttpResult<Void> result = sendRawLiveClassWebhook(body, signature);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(providerEventCount()).isEqualTo(before);
	}

	@Test
	void anUnresolvableProviderReferenceIsHandledGracefullyAndTenantIdIsNull() {
		String eventId = UUID.randomUUID().toString();

		HttpResult<Void> result = sendLiveClassWebhook(eventId, "recording.completed", "FAKE-ZOOM-never-existed",
				"FAKE-ZOOM-REC-x", 60);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		UUID ledgerTenantId = jdbcTemplate.queryForObject(
				"SELECT tenant_id FROM class_session_provider_event WHERE provider_event_id = ?", UUID.class, eventId);
		assertThat(ledgerTenantId).isNull();
	}

	@Test
	void tenantIdIsResolvedFromThePlatformsOwnRecordNeverFromThePayload() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-webhook-tenant");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Tenant resolution"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		completeSession(fixture.host(), fixture.teacherToken(), created.id());
		String providerReference = providerReferenceOf(created.id());
		String eventId = UUID.randomUUID().toString();

		sendLiveClassWebhook(eventId, "recording.completed", providerReference, "FAKE-ZOOM-REC-tenant", 45);

		UUID ledgerTenantId = jdbcTemplate.queryForObject(
				"SELECT tenant_id FROM class_session_provider_event WHERE provider_event_id = ?", UUID.class, eventId);
		assertThat(ledgerTenantId).isEqualTo(fixture.tenant().getId());
	}

	private Long providerEventCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM class_session_provider_event", Long.class);
	}

}

package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.lms.notificationmanagement.service.BroadcastService.AudiencePreview;
import com.lms.notificationmanagement.service.BroadcastService.BroadcastView;
import com.lms.notificationmanagement.service.BroadcastService.ChannelStats;
import com.lms.notificationmanagement.service.CommunicationTemplateService.TemplateView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

/**
 * Wave 11 broadcasts end to end: audience correctness, send (in-app now, the
 * rest via the async dispatcher and the provider adapters), bounded retry,
 * provider failure, stuck-row recovery, duplicate protection, recipient
 * consent and the WhatsApp pre-approved-template rule.
 */
class CommunicationsBroadcastIntegrationTest extends CommunicationsTestSupport {

	private static final String BROADCASTS = "/api/v1/communications/broadcasts";

	private static final String PREVIEW = "/api/v1/communications/audience-preview";

	// ---- audience correctness ----

	@Test
	void everyAudienceTypeResolvesExactlyItsActiveRecipients() {
		CommsFixture f = seedFixture("comms-aud");

		AudiencePreview all = preview(f, audience("ALL_STUDENTS"));
		assertThat(all.recipientCount()).as("suspended student excluded, staff/teachers excluded").isEqualTo(3);
		assertThat(all.sampleNames()).containsExactlyInAnyOrder("Amara Enrolled", "Bimal Other", "Dilan Pending");
		assertThat(all.reachByChannel()).containsEntry("SMS", 2).containsEntry("EMAIL", 3).containsEntry("WHATSAPP", 2);

		Map<String, Object> course = audience("COURSE");
		course.put("courseId", f.course().id());
		assertThat(preview(f, course).sampleNames()).containsExactly("Amara Enrolled");

		Map<String, Object> paid = audience("PAYMENT_STATUS");
		paid.put("paymentStatus", "PAID");
		assertThat(preview(f, paid).sampleNames()).containsExactly("Amara Enrolled");
		Map<String, Object> pending = audience("PAYMENT_STATUS");
		pending.put("paymentStatus", "PENDING");
		pending.put("courseId", f.course().id());
		assertThat(preview(f, pending).sampleNames()).containsExactly("Dilan Pending");

		assertThat(preview(f, audience("ALL_TEACHERS")).sampleNames()).containsExactly("Nimal Teacher");

		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId(), f.suspended().getId()));
		assertThat(preview(f, selected).sampleNames()).as("suspended account silently excluded")
			.containsExactly("Bimal Other");
	}

	@Test
	void selectedStudentsWithAForeignOrNonStudentIdAreRejectedWithoutSendingAnything() {
		CommsFixture a = seedFixture("comms-sel-a");
		CommsFixture b = seedFixture("comms-sel-b");

		Map<String, Object> foreign = audience("SELECTED_STUDENTS");
		foreign.put("studentIds", profileIds(a.other().getId(), b.other().getId()));
		assertThat(postRaw(a.host(), a.adminToken(), BROADCASTS, broadcastBody(foreign, List.of("IN_APP"))).status())
			.isEqualTo(400);

		Map<String, Object> teacherAsStudent = audience("SELECTED_STUDENTS");
		teacherAsStudent.put("studentIds", List.of(a.teacher().getId()));
		assertThat(postRaw(a.host(), a.adminToken(), BROADCASTS, broadcastBody(teacherAsStudent, List.of("IN_APP")))
			.status()).isEqualTo(400);

		Map<String, Object> foreignCourse = audience("COURSE");
		foreignCourse.put("courseId", b.course().id());
		assertThat(postRaw(a.host(), a.adminToken(), PREVIEW, foreignCourse).status()).isEqualTo(404);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM broadcast WHERE tenant_id = ?", Integer.class,
				a.tenant().getId())).isZero();
	}

	// ---- send + dispatch through adapters ----

	@Test
	void sendWritesInAppAtOnceAndDispatchesEmailAndSmsThroughTheAdapters() {
		CommsFixture f = seedFixture("comms-send");
		assertThat(configureMessaging(f, "SMS", "ACADEMY", "sandbox-api-key-0001234").status()).isEqualTo(200);

		BroadcastView view = sendOrFail(f, f.adminToken(),
				broadcastBody(audience("ALL_STUDENTS"), List.of("IN_APP", "EMAIL", "SMS")));

		assertThat(view.recipientCount()).isEqualTo(3);
		List<String> titles = jdbcTemplate.queryForList("SELECT title FROM in_app_notification WHERE tenant_id = ? "
				+ "AND recipient_user_id = ?", String.class, f.tenant().getId(), f.enrolled().getId());
		assertThat(titles).contains("Hello Amara Enrolled");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE tenant_id = ? "
				+ "AND recipient_user_id = ?", Integer.class, f.tenant().getId(), f.suspended().getId()))
			.as("suspended students receive nothing")
			.isZero();

		assertThat(dispatchDue(view.id())).isEqualTo(6);
		verify(messagingProviderApi, times(3)).sendEmail(anyString(), anyString(), anyString());
		verify(messagingProviderApi).sendEmail(eq("enrolled@example.test"), eq("Hello Amara Enrolled"),
				org.mockito.ArgumentMatchers.contains("Test Institute"));

		Map<String, Object> sms = deliveryRow(view.id(), f.enrolled().getId(), "SMS");
		assertThat(sms.get("status")).isEqualTo("SENT");
		assertThat(sms.get("destination_masked")).isEqualTo("+947•••100");
		assertThat((String) sms.get("provider_message_id")).startsWith("sandbox-");
		assertThat(sms.get("provider_account_id")).isNotNull();
		Map<String, Object> noPhone = deliveryRow(view.id(), f.pendingPayer().getId(), "SMS");
		assertThat(noPhone.get("status")).isEqualTo("SKIPPED");
		assertThat(noPhone.get("last_error_code")).isEqualTo("NO_ADDRESS");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM notification_delivery WHERE broadcast_id = ? AND destination_masked LIKE '%1230100%'",
				Integer.class, view.id())).as("no full phone number is ever stored").isZero();

		BroadcastView reloaded = readData(getRaw(f.host(), f.adminToken(), BROADCASTS + "/" + view.id()),
				BroadcastView.class);
		ChannelStats smsStats = reloaded.stats().stream().filter(s -> s.channel().equals("SMS")).findFirst()
			.orElseThrow();
		assertThat(smsStats.sent()).isEqualTo(2);
		assertThat(smsStats.skipped()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? "
				+ "AND action = 'communication.broadcast_sent'", Integer.class, f.tenant().getId())).isEqualTo(1);
	}

	@Test
	void aChannelThatIsNotConfiguredIsRefusedUpFrontAndOneDisabledLaterIsSkipped() {
		CommsFixture f = seedFixture("comms-unavail");
		Raw refused = postRaw(f.host(), f.adminToken(), BROADCASTS,
				broadcastBody(audience("ALL_STUDENTS"), List.of("SMS")));
		assertThat(refused.status()).isEqualTo(409);
		assertThat(refused.body()).contains("CHANNEL_NOT_AVAILABLE");

		configureMessaging(f, "SMS", "ACADEMY", "sandbox-api-key-0001234");
		BroadcastView view = sendOrFail(f, f.adminToken(), broadcastBody(audience("ALL_STUDENTS"), List.of("SMS")));
		assertThat(postRaw(f.host(), f.adminToken(), "/api/v1/tenant-admin/integrations/messaging/SMS/disable", null)
			.status()).isEqualTo(200);
		dispatchDue(view.id());
		assertThat(deliveryRow(view.id(), f.enrolled().getId(), "SMS")).containsEntry("status", "SKIPPED")
			.containsEntry("last_error_code", "CHANNEL_NOT_AVAILABLE");
	}

	// ---- retries and provider failure ----

	@Test
	void transientProviderFailuresRetryWithBackoffUpToThreeAttemptsAndPermanentOnesFailAtOnce() {
		CommsFixture f = seedFixture("comms-retry");
		configureMessaging(f, "SMS", "ACADEMY", "sandbox-api-key-0001234");
		jdbcTemplate.update("UPDATE student_profile SET mobile = '+94770000001' WHERE user_id = ?", f.other().getId());
		jdbcTemplate.update("UPDATE student_profile SET mobile = '+94770000002' WHERE user_id = ?",
				f.enrolled().getId());
		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId(), f.enrolled().getId()));
		BroadcastView view = sendOrFail(f, f.adminToken(), broadcastBody(selected, List.of("SMS")));

		dispatchDue(view.id());
		Map<String, Object> transientRow = deliveryRow(view.id(), f.other().getId(), "SMS");
		assertThat(transientRow).containsEntry("status", "PENDING").containsEntry("attempt_count", 1)
			.containsEntry("last_error_code", "PROVIDER_TRANSIENT");
		Instant next = ((Timestamp) transientRow.get("next_attempt_at")).toInstant();
		assertThat(next).isAfter(Instant.now().plus(Duration.ofSeconds(50)));
		assertThat(deliveryRow(view.id(), f.enrolled().getId(), "SMS")).containsEntry("status", "FAILED")
			.containsEntry("attempt_count", 1)
			.containsEntry("last_error_code", "PROVIDER_REJECTED");

		assertThat(dispatchDue(view.id())).as("a retry is not due before its backoff").isZero();

		makeRetriesDue(view.id());
		dispatchDue(view.id());
		Map<String, Object> second = deliveryRow(view.id(), f.other().getId(), "SMS");
		assertThat(second).containsEntry("status", "PENDING").containsEntry("attempt_count", 2);
		assertThat(((Timestamp) second.get("next_attempt_at")).toInstant())
			.as("second backoff is longer (5 min)")
			.isAfter(Instant.now().plus(Duration.ofMinutes(4)));

		makeRetriesDue(view.id());
		dispatchDue(view.id());
		assertThat(deliveryRow(view.id(), f.other().getId(), "SMS")).containsEntry("status", "FAILED")
			.containsEntry("attempt_count", 3)
			.containsEntry("last_error_code", "PROVIDER_TRANSIENT");
		makeRetriesDue(view.id());
		assertThat(dispatchDue(view.id())).as("nothing left after the third attempt").isZero();
	}

	@Test
	void anEmailTransportFailureIsRetriedAndThenSucceeds() {
		CommsFixture f = seedFixture("comms-mailretry");
		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId()));
		doThrow(new MailSendException("smtp down for other@example.test")).when(messagingProviderApi)
			.sendEmail(anyString(), anyString(), anyString());
		BroadcastView view = sendOrFail(f, f.adminToken(), broadcastBody(selected, List.of("EMAIL")));

		dispatchDue(view.id());
		assertThat(deliveryRow(view.id(), f.other().getId(), "EMAIL")).containsEntry("status", "PENDING")
			.containsEntry("last_error_code", "EMAIL_TRANSIENT");

		reset(messagingProviderApi);
		makeRetriesDue(view.id());
		dispatchDue(view.id());
		assertThat(deliveryRow(view.id(), f.other().getId(), "EMAIL")).containsEntry("status", "SENT")
			.containsEntry("attempt_count", 2)
			.containsEntry("destination_masked", "o•••@example.test");
	}

	@Test
	void aDeliveryStuckInSendingIsFailedForwardAndNeverSentAgain() {
		CommsFixture f = seedFixture("comms-stuck");
		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId()));
		BroadcastView view = sendOrFail(f, f.adminToken(), broadcastBody(selected, List.of("EMAIL")));
		jdbcTemplate.update("UPDATE notification_delivery SET status = 'SENDING', attempt_count = 1, "
				+ "claimed_at = now() - interval '1 hour' WHERE broadcast_id = ?", view.id());

		deliveryClaimService.findStuckSendingIds(100).forEach(deliveryClaimService::reconcileOne);

		assertThat(deliveryRow(view.id(), f.other().getId(), "EMAIL")).containsEntry("status", "FAILED")
			.containsEntry("last_error_code", "STUCK_IN_SENDING");
		assertThat(dispatchDue(view.id())).isZero();
		verify(messagingProviderApi, never()).sendEmail(anyString(), anyString(), anyString());
	}

	// ---- duplicates ----

	@Test
	void aRepeatedSubmitAndARepeatedDispatchNeverSendTwice() {
		CommsFixture f = seedFixture("comms-dup");
		Map<String, Object> body = broadcastBody(audience("ALL_STUDENTS"), List.of("IN_APP", "EMAIL"));
		BroadcastView first = sendOrFail(f, f.adminToken(), body);

		Raw replay = postRaw(f.host(), f.adminToken(), BROADCASTS, body);
		assertThat(replay.status()).isEqualTo(200);
		BroadcastView replayed = readData(replay, BroadcastView.class);
		assertThat(replayed.id()).isEqualTo(first.id());
		assertThat(replayed.replayed()).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM broadcast WHERE tenant_id = ?", Integer.class,
				f.tenant().getId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE tenant_id = ? "
				+ "AND recipient_user_id = ? AND title = 'Hello Bimal Other'", Integer.class, f.tenant().getId(),
				f.other().getId())).isEqualTo(1);

		UUID emailDelivery = (UUID) deliveryRow(first.id(), f.other().getId(), "EMAIL").get("id");
		deliveryDispatchService.dispatchOne(emailDelivery);
		deliveryDispatchService.dispatchOne(emailDelivery);
		verify(messagingProviderApi, times(1)).sendEmail(eq("other@example.test"), anyString(), anyString());
	}

	// ---- consent + WhatsApp template rule ----

	@Test
	void recipientConsentAndTheApprovedWhatsAppTemplateRuleAreEnforced() {
		CommsFixture f = seedFixture("comms-consent");
		configureMessaging(f, "SMS", "ACADEMY", "sandbox-api-key-0001234");
		configureMessaging(f, "WHATSAPP", "+94110000000", "sandbox-api-key-0005678");
		String otherToken = loginAndGetToken(f.host(), "other@example.test");
		assertThat(putRaw(f.host(), otherToken, "/api/v1/notifications/preferences",
				Map.of("SMS", false, "WHATSAPP", true)).status()).isEqualTo(200);

		Map<String, Object> selected = audience("SELECTED_STUDENTS");
		selected.put("studentIds", profileIds(f.other().getId(), f.enrolled().getId()));
		assertThat(postRaw(f.host(), f.adminToken(), BROADCASTS, broadcastBody(selected, List.of("WHATSAPP")))
			.status()).as("WhatsApp without an approved template").isEqualTo(400);

		TemplateView template = readData(postRaw(f.host(), f.adminToken(), "/api/v1/communications/templates",
				Map.of("name", "Term start", "subject", "Term starts", "body", "Hi {{name}}, term starts Monday.")),
				TemplateView.class);
		String status = "/api/v1/communications/templates/" + template.id() + "/whatsapp-status";
		assertThat(postRaw(f.host(), f.adminToken(), status,
				Map.of("action", "APPROVE")).status()).as("cannot approve before submitting").isEqualTo(409);
		postRaw(f.host(), f.adminToken(), status, Map.of("action", "SUBMIT", "providerTemplateName", "term_start"));
		TemplateView approved = readData(postRaw(f.host(), f.adminToken(), status, Map.of("action", "APPROVE")),
				TemplateView.class);
		assertThat(approved.whatsAppStatus()).isEqualTo("APPROVED");

		Map<String, Object> body = broadcastBody(selected, List.of("SMS", "WHATSAPP"));
		body.put("templateId", template.id());
		BroadcastView view = sendOrFail(f, f.adminToken(), body);
		dispatchDue(view.id());

		assertThat(deliveryRow(view.id(), f.other().getId(), "SMS")).containsEntry("status", "SKIPPED")
			.containsEntry("last_error_code", "NO_CONSENT");
		assertThat(deliveryRow(view.id(), f.other().getId(), "WHATSAPP")).containsEntry("status", "SENT")
			.containsEntry("destination_masked", "+947•••299");
		assertThat(deliveryRow(view.id(), f.enrolled().getId(), "WHATSAPP"))
			.as("WhatsApp is opt-in: no preference = no message")
			.containsEntry("status", "SKIPPED")
			.containsEntry("last_error_code", "NO_CONSENT");
		assertThat(deliveryRow(view.id(), f.enrolled().getId(), "SMS")).containsEntry("status", "SENT");

		TemplateView edited = readData(putRaw(f.host(), f.adminToken(), "/api/v1/communications/templates/"
				+ template.id(), Map.of("name", "Term start", "subject", "Term starts", "body", "Changed text.")),
				TemplateView.class);
		assertThat(edited.whatsAppStatus()).as("an edit invalidates the provider approval")
			.isEqualTo("NOT_SUBMITTED");
	}

	@Test
	void unknownTemplateVariablesAreRejected() {
		CommsFixture f = seedFixture("comms-vars");
		Map<String, Object> body = broadcastBody(audience("ALL_STUDENTS"), List.of("IN_APP"));
		body.put("body", "Your password is {{password}}");
		assertThat(postRaw(f.host(), f.adminToken(), BROADCASTS, body).status()).isEqualTo(400);
		assertThat(postRaw(f.host(), f.adminToken(), "/api/v1/communications/templates",
				Map.of("name", "Bad", "subject", "x", "body", "{{secret}}")).status()).isEqualTo(400);
	}

	private AudiencePreview preview(CommsFixture f, Map<String, Object> audience) {
		Raw raw = postRaw(f.host(), f.adminToken(), PREVIEW, audience);
		assertThat(raw.status()).as(raw.body()).isEqualTo(200);
		return readData(raw, AudiencePreview.class);
	}

}

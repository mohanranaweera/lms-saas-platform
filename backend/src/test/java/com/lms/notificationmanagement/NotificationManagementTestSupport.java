package com.lms.notificationmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.notificationmanagement.domain.InAppNotification;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.repository.InAppNotificationRepository;
import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import com.lms.notificationmanagement.repository.NotificationTemplateRepository;
import com.lms.notificationmanagement.service.NotificationDispatchPoller;
import com.lms.notificationmanagement.web.dto.NotificationResponse;
import com.lms.notificationmanagement.web.dto.UnreadCountResponse;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared Testcontainers/MockMvc helpers for MVP-018 (Email Notifications)'s
 * integration tests, mirroring {@code PaymentManagementTestSupport}'s
 * established technique. Extends it (rather than {@code
 * CourseManagementTestSupport}/{@code AuthIntegrationTestSupport} directly)
 * so tests can drive a real payment-confirmation webhook flow - the most
 * realistic trigger path for {@code PaymentConfirmedEvent}/{@code
 * PaymentRejectedEvent} - as well as reuse its tenant/course/order fixtures.
 *
 * <p>Overrides two beans context-wide for every test extending this class:
 * <ul>
 * <li>{@link MessagingProviderApi} - the real {@code SmtpMessagingProviderApi}
 * would attempt a genuine SMTP connection (nothing listens on the test
 * config's {@code localhost:1025}); every test that needs a dispatch to
 * "succeed" or "fail" controls this mock explicitly.</li>
 * <li>{@link NotificationDispatchPoller} - this codebase's only {@code
 * @Scheduled} bean (5s fixed delay). Left un-mocked, it would race the
 * assertions below (e.g. a "still PENDING, simulating a poller crash"
 * fixture could be silently dispatched by the REAL poller before the test
 * gets to assert on it) since Testcontainers-backed tests routinely take
 * several seconds. Overriding it with a Mockito mock means Spring's {@code
 * ScheduledAnnotationBeanPostProcessor} either schedules calls against a
 * no-op mock method (nothing happens beyond an unstubbed void no-op) or does
 * not schedule anything at all if the mock doesn't retain the {@code
 * @Scheduled} annotation on its generated subclass - both outcomes are
 * exactly the "no automatic background dispatch" behavior these tests need,
 * so this override is deliberately not dependent on which of the two
 * actually happens.</li>
 * </ul>
 */
public abstract class NotificationManagementTestSupport extends PaymentManagementTestSupport {

	@Autowired
	protected NotificationOutboxRepository notificationOutboxRepository;

	@Autowired
	protected NotificationTemplateRepository notificationTemplateRepository;

	@Autowired
	protected InAppNotificationRepository inAppNotificationRepository;

	@MockitoBean
	protected MessagingProviderApi messagingProviderApi;

	@MockitoBean
	protected NotificationDispatchPoller notificationDispatchPoller;

	protected NotificationTemplate seedTemplate(UUID tenantId, NotificationEventType eventType, String subject,
			String body) {
		return withTenant(tenantId,
				() -> notificationTemplateRepository.save(new NotificationTemplate(tenantId, eventType.name(), subject, body)));
	}

	protected NotificationOutbox seedPendingOutbox(UUID tenantId, UUID recipientUserId, NotificationEventType eventType,
			Map<String, Object> variables) {
		String payload = objectMapper.writeValueAsString(variables);
		return withTenant(tenantId, () -> notificationOutboxRepository
			.save(new NotificationOutbox(tenantId, eventType, recipientUserId, payload, Instant.now())));
	}

	protected InAppNotification seedInAppNotification(UUID tenantId, UUID recipientUserId, String title, String body) {
		return withTenant(tenantId,
				() -> inAppNotificationRepository.save(new InAppNotification(tenantId, recipientUserId, title, body, Instant.now())));
	}

	protected HttpResult<PageResponse<NotificationResponse>> listNotifications(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/notifications");
		return parsePage(perform(authenticated(builder, host, token)), NotificationResponse.class);
	}

	protected HttpResult<PageResponse<NotificationResponse>> listNotifications(String host, String token,
			String queryString) {
		MockHttpServletRequestBuilder builder = get("/api/v1/notifications?" + queryString);
		return parsePage(perform(authenticated(builder, host, token)), NotificationResponse.class);
	}

	protected HttpResult<NotificationResponse> markNotificationRead(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = patch("/api/v1/notifications/{id}/read", id);
		return parseSingle(perform(authenticated(builder, host, token)), NotificationResponse.class);
	}

	protected HttpResult<UnreadCountResponse> getUnreadCount(String host, String token) {
		MockHttpServletRequestBuilder builder = get("/api/v1/notifications/unread-count");
		return parseSingle(perform(authenticated(builder, host, token)), UnreadCountResponse.class);
	}

}

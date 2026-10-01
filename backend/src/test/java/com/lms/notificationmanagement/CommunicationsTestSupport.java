package com.lms.notificationmanagement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.service.BroadcastService.BroadcastView;
import com.lms.notificationmanagement.service.NotificationDeliveryClaimService;
import com.lms.notificationmanagement.service.NotificationDeliveryDispatchService;
import com.lms.notificationmanagement.service.NotificationDeliveryPoller;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared fixtures for the Wave 11 communications tests. Extends {@link
 * NotificationManagementTestSupport} (email provider mocked, outbox poller
 * disabled) and additionally disables {@link NotificationDeliveryPoller}, so a
 * delivery is only ever dispatched by a test's explicit {@link #dispatchDue()}.
 * SMS/WhatsApp go through the real {@code MessagingAccountService} and the
 * in-process sandbox adapter (enabled in the test profile).
 */
public abstract class CommunicationsTestSupport extends NotificationManagementTestSupport {

	@MockitoBean
	protected NotificationDeliveryPoller notificationDeliveryPoller;

	@Autowired
	protected NotificationDeliveryClaimService deliveryClaimService;

	@Autowired
	protected NotificationDeliveryDispatchService deliveryDispatchService;

	/** A tenant with staff, a teacher who owns a course, and three students (enrolled, not enrolled, suspended). */
	protected record CommsFixture(Tenant tenant, String host, String adminToken, TenantUser teacher,
			String teacherToken, CourseResponse course, TenantUser enrolled, TenantUser other, TenantUser suspended,
			TenantUser pendingPayer) {

	}

	protected record Raw(int status, String body) {

	}

	protected CommsFixture seedFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		String host = hostFor(tenant.getSubdomain());
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		jdbcTemplate.update("INSERT INTO teacher_profile (id, tenant_id, user_id, name, approval_status, approved_by, "
				+ "approved_at, created_at, updated_at) VALUES (?, ?, ?, 'Nimal Teacher', 'APPROVED', ?, now(), now(), "
				+ "now())", UUID.randomUUID(), tenant.getId(), teacher.getId(), admin.getId());
		TenantUser enrolled = seedActiveStudent(tenant.getId(), "enrolled@example.test");
		TenantUser other = seedActiveStudent(tenant.getId(), "other@example.test");
		TenantUser suspended = seedActiveStudent(tenant.getId(), "suspended@example.test");
		TenantUser pendingPayer = seedActiveStudent(tenant.getId(), "pending@example.test");
		seedStudentProfile(tenant.getId(), enrolled.getId(), "Amara Enrolled", "+94771230100", null);
		seedStudentProfile(tenant.getId(), other.getId(), "Bimal Other", "+94771230200", "+94771230299");
		seedStudentProfile(tenant.getId(), suspended.getId(), "Chathu Suspended", "+94771230300", null);
		seedStudentProfile(tenant.getId(), pendingPayer.getId(), "Dilan Pending", null, null);

		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));

		String enrolledToken = loginAndGetToken(host, "enrolled@example.test");
		OrderResponse order = createOrderOrFail(host, enrolledToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, enrolledToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		String pendingToken = loginAndGetToken(host, "pending@example.test");
		OrderResponse pendingOrder = createOrderOrFail(host, pendingToken, course.id());
		initiatePaymentOrFail(host, pendingToken, pendingOrder.id());

		suspendTenantUser(suspended.getId());
		return new CommsFixture(tenant, host, adminToken, teacher, teacherToken, course, enrolled, other, suspended,
				pendingPayer);
	}

	protected void seedStudentProfile(UUID tenantId, UUID userId, String name, String mobile, String whatsapp) {
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, mobile, whatsapp_number, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())", UUID.randomUUID(), tenantId, userId,
				name, mobile, whatsapp);
	}

	/** Student profile ids (what the SELECTED_STUDENTS audience takes) for the given user ids. */
	protected List<UUID> profileIds(UUID... userIds) {
		return java.util.Arrays.stream(userIds)
			.map(userId -> jdbcTemplate.queryForObject("SELECT id FROM student_profile WHERE user_id = ?", UUID.class,
					userId))
			.toList();
	}

	protected String staffToken(CommsFixture f, String email, Role role) {
		seedTenantUser(f.tenant().getId(), email, RAW_PASSWORD, role);
		return loginAndGetToken(f.host(), email);
	}

	// ---- HTTP helpers ----

	protected Raw call(MockHttpServletRequestBuilder builder, String host, String token, Object body) {
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		MvcResult result = perform(authenticated(builder, host, token));
		return new Raw(result.getResponse().getStatus(), rawContent(result));
	}

	protected Raw getRaw(String host, String token, String path) {
		return call(get(path), host, token, null);
	}

	protected Raw postRaw(String host, String token, String path, Object body) {
		return call(post(path), host, token, body);
	}

	protected Raw putRaw(String host, String token, String path, Object body) {
		return call(put(path), host, token, body);
	}

	protected static Map<String, Object> audience(String type) {
		Map<String, Object> audience = new LinkedHashMap<>();
		audience.put("type", type);
		return audience;
	}

	protected static Map<String, Object> broadcastBody(Map<String, Object> audience, List<String> channels) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("clientRequestId", UUID.randomUUID().toString());
		body.put("subject", "Hello {{name}}");
		body.put("body", "Classes resume on Monday at {{instituteName}}.");
		body.put("channels", channels);
		body.put("audience", audience);
		return body;
	}

	protected BroadcastView sendOrFail(CommsFixture f, String token, Map<String, Object> body) {
		Raw raw = postRaw(f.host(), token, "/api/v1/communications/broadcasts", body);
		if (raw.status() != 201) {
			throw new IllegalStateException("Broadcast failed: " + raw.status() + " " + raw.body());
		}
		return readData(raw, BroadcastView.class);
	}

	protected <T> T readData(Raw raw, Class<T> type) {
		var tree = objectMapper.readTree(raw.body());
		return objectMapper.treeToValue(tree.get("data"), type);
	}

	protected Raw configureMessaging(CommsFixture f, String channel, String senderId, String apiKey) {
		return putRaw(f.host(), f.adminToken(), "/api/v1/tenant-admin/integrations/messaging/" + channel,
				Map.of("provider", "SANDBOX", "senderId", senderId, "credentials", Map.of("apiKey", apiKey)));
	}

	/**
	 * Dispatches every due delivery of one broadcast once, as one poller cycle
	 * would. Scoped to the broadcast so rows left by other tests sharing the
	 * database never interfere.
	 */
	protected int dispatchDue(UUID broadcastId) {
		List<UUID> ids = jdbcTemplate.queryForList("SELECT id FROM notification_delivery WHERE broadcast_id = ? "
				+ "AND status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= now())", UUID.class,
				broadcastId);
		ids.forEach(deliveryDispatchService::dispatchOne);
		return ids.size();
	}

	protected Map<String, Object> deliveryRow(UUID broadcastId, UUID recipientId, String channel) {
		return jdbcTemplate.queryForMap("SELECT * FROM notification_delivery WHERE broadcast_id = ? "
				+ "AND recipient_user_id = ? AND channel = ?", broadcastId, recipientId, channel);
	}

	/** Makes every PENDING retry of this broadcast due now (instead of waiting out the backoff). */
	protected void makeRetriesDue(UUID broadcastId) {
		jdbcTemplate.update("UPDATE notification_delivery SET next_attempt_at = now() - interval '1 second' "
				+ "WHERE broadcast_id = ? AND status = 'PENDING' AND next_attempt_at IS NOT NULL", broadcastId);
	}

}

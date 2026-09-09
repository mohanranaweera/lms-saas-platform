package com.lms.notificationmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.domain.InAppNotification;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.web.dto.NotificationResponse;
import com.lms.notificationmanagement.web.dto.UnreadCountResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

/**
 * Closes MVP-018 plan §18's two mandatory negative-test requirements:
 * <ul>
 * <li>Cross-tenant: Tenant B cannot read Tenant A's {@code
 * notification_template}/{@code notification_outbox}/{@code
 * in_app_notification} rows via any repository method or endpoint.</li>
 * <li>Same-tenant BOLA (distinct from the above): within ONE tenant, User B
 * cannot list or mark-read User A's {@code in_app_notification} rows via the
 * real {@code GET}/{@code PATCH} endpoints - mark-read returns {@code 404},
 * never {@code 403}, per plan §10's explicit anti-enumeration
 * requirement.</li>
 * </ul>
 * Plus the plan's explicit empty-state acceptance criterion.
 */
class NotificationCrossTenantIntegrationTest extends NotificationManagementTestSupport {

	@Test
	void templateOutboxAndInAppNotificationRowsAreInvisibleToAnotherTenantAtTheRepositoryLevel() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("notif-xt-repo-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("notif-xt-repo-b"));
		TenantUser studentA = seedActiveStudent(tenantA.getId(), "repo-student-a@example.test");

		NotificationTemplate template = seedTemplate(tenantA.getId(), NotificationEventType.PAYMENT_CONFIRMED,
				"Subject A", "Body A");
		NotificationOutbox outbox = seedPendingOutbox(tenantA.getId(), studentA.getId(),
				NotificationEventType.PAYMENT_CONFIRMED, Map.of("amount", "5.00"));
		InAppNotification inApp = seedInAppNotification(tenantA.getId(), studentA.getId(), "Title A", "Body A");

		// Under tenant B's context: none of tenant A's rows resolve by id or
		// by any tenant-scoped finder.
		var templateByIdUnderB = withTenant(tenantB.getId(), () -> notificationTemplateRepository.findById(template.getId()));
		var outboxByIdUnderB = withTenant(tenantB.getId(), () -> notificationOutboxRepository.findById(outbox.getId()));
		var inAppByIdUnderB = withTenant(tenantB.getId(), () -> inAppNotificationRepository.findById(inApp.getId()));
		var templateByKeyUnderB = withTenant(tenantB.getId(),
				() -> notificationTemplateRepository.findByTemplateKey("PAYMENT_CONFIRMED"));
		var inAppListUnderB = withTenant(tenantB.getId(),
				() -> inAppNotificationRepository.findByRecipientUserId(studentA.getId(), PageRequest.of(0, 10)));

		assertThat(templateByIdUnderB).isEmpty();
		assertThat(outboxByIdUnderB).isEmpty();
		assertThat(inAppByIdUnderB).isEmpty();
		assertThat(templateByKeyUnderB).isEmpty();
		assertThat(inAppListUnderB.getContent()).isEmpty();

		// Sanity: tenant A's own context DOES see all three - proving the
		// emptiness above is isolation, not a broken read.
		var templateUnderA = withTenant(tenantA.getId(), () -> notificationTemplateRepository.findById(template.getId()));
		var outboxUnderA = withTenant(tenantA.getId(), () -> notificationOutboxRepository.findById(outbox.getId()));
		var inAppUnderA = withTenant(tenantA.getId(), () -> inAppNotificationRepository.findById(inApp.getId()));
		assertThat(templateUnderA).isPresent();
		assertThat(outboxUnderA).isPresent();
		assertThat(inAppUnderA).isPresent();
	}

	@Test
	void crossTenantNotificationListEndpointNeverReturnsAnotherTenantsRows() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("notif-xt-list-a"));
		TenantUser studentA = seedActiveStudent(tenantA.getId(), "list-student-a@example.test");
		seedInAppNotification(tenantA.getId(), studentA.getId(), "Tenant A Title", "Tenant A Body");

		Tenant tenantB = seedActiveTenant(uniqueSubdomain("notif-xt-list-b"));
		seedActiveStudent(tenantB.getId(), "list-student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenB = loginAndGetToken(hostB, "list-student-b@example.test");

		HttpResult<PageResponse<NotificationResponse>> resultB = listNotifications(hostB, tokenB);

		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultB.getBody().data().content()).isEmpty();

		// Sanity: tenant A's own student DOES see its own row.
		String hostA = hostFor(tenantA.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "list-student-a@example.test");
		HttpResult<PageResponse<NotificationResponse>> resultA = listNotifications(hostA, tokenA);
		assertThat(resultA.getBody().data().content()).extracting(NotificationResponse::title)
			.containsExactly("Tenant A Title");
	}

	@Test
	void sameTenantBolaListNeverReturnsAnotherUsersNotifications() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-bola-list"));
		TenantUser studentA = seedActiveStudent(tenant.getId(), "bola-student-a@example.test");
		seedActiveStudent(tenant.getId(), "bola-student-b@example.test");
		seedInAppNotification(tenant.getId(), studentA.getId(), "Student A's Title", "Student A's Body");
		String host = hostFor(tenant.getSubdomain());
		String tokenB = loginAndGetToken(host, "bola-student-b@example.test");

		HttpResult<PageResponse<NotificationResponse>> resultB = listNotifications(host, tokenB);

		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultB.getBody().data().content()).isEmpty();

		// Sanity: student A's own token DOES see it - same tenant, different
		// (correct) recipient.
		String tokenA = loginAndGetToken(host, "bola-student-a@example.test");
		HttpResult<PageResponse<NotificationResponse>> resultA = listNotifications(host, tokenA);
		assertThat(resultA.getBody().data().content()).extracting(NotificationResponse::title)
			.containsExactly("Student A's Title");
	}

	@Test
	void sameTenantBolaMarkReadReturns404NeverRevealsExistenceAndLeavesTheOwnersRowUntouched() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-bola-read"));
		TenantUser studentA = seedActiveStudent(tenant.getId(), "bola-read-a@example.test");
		seedActiveStudent(tenant.getId(), "bola-read-b@example.test");
		InAppNotification notificationOfA = seedInAppNotification(tenant.getId(), studentA.getId(), "A's notification",
				"Body");
		String host = hostFor(tenant.getSubdomain());
		String tokenB = loginAndGetToken(host, "bola-read-b@example.test");

		HttpResult<NotificationResponse> result = markNotificationRead(host, tokenB, notificationOfA.getId());

		// 404, never 403 - per plan §10, existence of another user's row must
		// never be revealed even to another authenticated user in the SAME
		// tenant.
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		InAppNotification untouched = withTenant(tenant.getId(),
				() -> inAppNotificationRepository.findById(notificationOfA.getId())).orElseThrow();
		assertThat(untouched.getReadAt()).isNull();

		// Sanity: student A's OWN token can mark their own row read.
		String tokenA = loginAndGetToken(host, "bola-read-a@example.test");
		HttpResult<NotificationResponse> resultA = markNotificationRead(host, tokenA, notificationOfA.getId());
		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultA.getBody().data().readAt()).isNotNull();
	}

	@Test
	void crossTenantMarkReadAlsoReturns404NeverRevealsExistenceAcrossTenants() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("notif-xt-read-a"));
		TenantUser studentA = seedActiveStudent(tenantA.getId(), "xt-read-student-a@example.test");
		InAppNotification notificationOfA = seedInAppNotification(tenantA.getId(), studentA.getId(), "A's notification",
				"Body");

		Tenant tenantB = seedActiveTenant(uniqueSubdomain("notif-xt-read-b"));
		seedActiveStudent(tenantB.getId(), "xt-read-student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenB = loginAndGetToken(hostB, "xt-read-student-b@example.test");

		HttpResult<NotificationResponse> result = markNotificationRead(hostB, tokenB, notificationOfA.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		InAppNotification untouched = withTenant(tenantA.getId(),
				() -> inAppNotificationRepository.findById(notificationOfA.getId())).orElseThrow();
		assertThat(untouched.getReadAt()).isNull();
	}

	@Test
	void sameTenantBolaUnreadCountNeverIncludesAnotherUsersRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-bola-count"));
		TenantUser studentA = seedActiveStudent(tenant.getId(), "bola-count-a@example.test");
		seedActiveStudent(tenant.getId(), "bola-count-b@example.test");
		seedInAppNotification(tenant.getId(), studentA.getId(), "Student A's Title", "Student A's Body");
		String host = hostFor(tenant.getSubdomain());
		String tokenB = loginAndGetToken(host, "bola-count-b@example.test");

		HttpResult<UnreadCountResponse> resultB = getUnreadCount(host, tokenB);

		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultB.getBody().data().unreadCount()).isEqualTo(0L);

		// Sanity: student A's own token DOES count it.
		String tokenA = loginAndGetToken(host, "bola-count-a@example.test");
		HttpResult<UnreadCountResponse> resultA = getUnreadCount(host, tokenA);
		assertThat(resultA.getBody().data().unreadCount()).isEqualTo(1L);
	}

	@Test
	void crossTenantUnreadCountNeverIncludesAnotherTenantsRows() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("notif-xt-count-a"));
		TenantUser studentA = seedActiveStudent(tenantA.getId(), "xt-count-student-a@example.test");
		seedInAppNotification(tenantA.getId(), studentA.getId(), "Tenant A Title", "Tenant A Body");

		Tenant tenantB = seedActiveTenant(uniqueSubdomain("notif-xt-count-b"));
		seedActiveStudent(tenantB.getId(), "xt-count-student-b@example.test");
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenB = loginAndGetToken(hostB, "xt-count-student-b@example.test");

		HttpResult<UnreadCountResponse> resultB = getUnreadCount(hostB, tokenB);

		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultB.getBody().data().unreadCount()).isEqualTo(0L);
	}

	@Test
	void unreadCountReturnsExactlyTheCallersOwnUnreadRowCountIgnoringAlreadyReadRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-count-correct"));
		TenantUser student = seedActiveStudent(tenant.getId(), "count-correct-student@example.test");
		InAppNotification unreadOne = seedInAppNotification(tenant.getId(), student.getId(), "Unread 1", "Body");
		seedInAppNotification(tenant.getId(), student.getId(), "Unread 2", "Body");
		seedInAppNotification(tenant.getId(), student.getId(), "Unread 3", "Body");
		InAppNotification readOne = seedInAppNotification(tenant.getId(), student.getId(), "Read 1", "Body");
		InAppNotification readTwo = seedInAppNotification(tenant.getId(), student.getId(), "Read 2", "Body");
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "count-correct-student@example.test");
		markNotificationRead(host, token, readOne.getId());
		markNotificationRead(host, token, readTwo.getId());
		// unreadOne is left deliberately untouched, alongside the other two
		// never-marked-read rows, to prove the count reflects real state, not
		// just "total minus a fixed number".
		assertThat(unreadOne.getReadAt()).isNull();

		HttpResult<UnreadCountResponse> result = getUnreadCount(host, token);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().unreadCount()).isEqualTo(3L);
	}

	@Test
	void aRecipientWithZeroNotificationsGetsAWellFormedEmptyPageResponseNotAnErrorOrNull() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("notif-empty"));
		seedActiveStudent(tenant.getId(), "empty-student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "empty-student@example.test");

		HttpResult<PageResponse<NotificationResponse>> result = listNotifications(host, token);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data()).isNotNull();
		assertThat(result.getBody().data().content()).isNotNull().isEmpty();
		assertThat(result.getBody().data().totalElements()).isEqualTo(0L);
	}

}

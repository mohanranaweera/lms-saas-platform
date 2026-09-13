package com.lms.auditlogmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.auditlogmanagement.web.dto.AuditLogEntryResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Covers AUDIT-3's read endpoint: pagination, filtering, default sort order,
 * the Institute-Owner/Read-only-Auditor allowlist authorization gate, and
 * the empty-filter-match case. Rows are seeded directly via {@code
 * seedAuditLogRow} (precise control over {@code occurredAt}/{@code action}/
 * {@code targetEntity}) rather than triggered through 25+ real domain
 * flows, per the test plan's explicit sanction for this purpose.
 */
class AuditLogViewerIntegrationTest extends AuditLogManagementTestSupport {

	@Test
	void pageZeroReturnsTwentyNewestRowsAndPageOneReturnsTheRemainingFiveInDescendingOrder() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-page"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
		List<UUID> targetIds = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			UUID targetId = UUID.randomUUID();
			targetIds.add(targetId);
			seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", targetId,
					base.plusSeconds(i));
		}

		var page0 = auditLogSearch(host, adminToken);
		assertThat(page0.getStatusCode()).isEqualTo(HttpStatus.OK);
		var page0Body = page0.getBody().data();
		assertThat(page0Body.content()).hasSize(20);
		assertThat(page0Body.totalElements()).isEqualTo(25);
		assertThat(page0Body.totalPages()).isEqualTo(2);
		// Descending occurredAt: row 24 (newest) first.
		assertThat(page0Body.content().get(0).targetId()).isEqualTo(targetIds.get(24));
		assertThat(page0Body.content().get(19).targetId()).isEqualTo(targetIds.get(5));
		assertDescending(page0Body.content());

		var page1 = auditLogSearch(host, adminToken, "page=1");
		assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
		var page1Body = page1.getBody().data();
		assertThat(page1Body.content()).hasSize(5);
		assertThat(page1Body.content().get(0).targetId()).isEqualTo(targetIds.get(4));
		assertThat(page1Body.content().get(4).targetId()).isEqualTo(targetIds.get(0));
		assertDescending(page1Body.content());
	}

	@Test
	void defaultSortOrderIsOccurredAtDescendingWhenNoFiltersGiven() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-default-sort"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant base = Instant.now().minusSeconds(120);
		UUID oldest = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base);
		UUID middle = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base.plusSeconds(30));
		UUID newest = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base.plusSeconds(60));

		var result = auditLogSearch(host, adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(AuditLogEntryResponse::id)
			.containsExactly(newest, middle, oldest);
	}

	@Test
	void filterByActionAloneReturnsOnlyMatchingRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-filter-action"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant now = Instant.now();
		UUID priceChangeId = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), now.minusSeconds(10));
		seedAuditLogRow(tenant.getId(), admin.getId(), "material.deleted", "material", UUID.randomUUID(),
				now.minusSeconds(5));

		var result = auditLogSearch(host, adminToken, "action=course.price_changed");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).extracting(AuditLogEntryResponse::id).containsExactly(priceChangeId);
		assertThat(body.totalElements()).isEqualTo(1);
	}

	@Test
	void filterByTargetEntityAloneReturnsOnlyMatchingRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-filter-target"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant now = Instant.now();
		seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", UUID.randomUUID(),
				now.minusSeconds(10));
		UUID refundId = seedAuditLogRow(tenant.getId(), admin.getId(), "payment.refunded", "payment_refund",
				UUID.randomUUID(), now.minusSeconds(5));

		var result = auditLogSearch(host, adminToken, "targetEntity=payment_refund");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).extracting(AuditLogEntryResponse::id).containsExactly(refundId);
		assertThat(body.totalElements()).isEqualTo(1);
	}

	@Test
	void filterByDateRangeAloneReturnsOnlyMatchingRows() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-filter-date"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant base = Instant.now().minus(2, ChronoUnit.HOURS);
		UUID tooOld = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base);
		UUID inRange = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base.plusSeconds(3600));
		UUID tooNew = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base.plusSeconds(7200));

		String from = base.plusSeconds(1800).toString();
		String to = base.plusSeconds(5400).toString();
		var result = auditLogSearch(host, adminToken, "from=" + from + "&to=" + to);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).extracting(AuditLogEntryResponse::id).containsExactly(inRange);
		assertThat(body.content()).extracting(AuditLogEntryResponse::id)
			.doesNotContain(tooOld, tooNew);
	}

	@Test
	void combinedFiltersNarrowToOnlyRowsMatchingAllOfThem() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-filter-combined"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant base = Instant.now().minusSeconds(600);
		UUID match = seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), base.plusSeconds(100));
		// Wrong action, same window/targetEntity.
		seedAuditLogRow(tenant.getId(), admin.getId(), "material.deleted", "course", UUID.randomUUID(),
				base.plusSeconds(100));
		// Right action/targetEntity, but outside the from/to window.
		seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", UUID.randomUUID(),
				base.minusSeconds(500));

		String from = base.toString();
		String to = base.plusSeconds(300).toString();
		var result = auditLogSearch(host, adminToken,
				"action=course.price_changed&targetEntity=course&from=" + from + "&to=" + to);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(AuditLogEntryResponse::id)
			.containsExactly(match);
	}

	/**
	 * Closes the actor-display-name-happy-path finding: {@code
	 * AuditLogQueryService#resolveActorDisplayNames} is otherwise only
	 * unit-tested (Mockito) on its null-fallback branch - this proves that,
	 * through the real HTTP path (real seeded {@code tenant_user} row, real
	 * {@code UserProvisioningApi} bean, real DB), a legitimate actor's
	 * {@code actorDisplayName} is populated with that user's email, not just
	 * {@code null}/absent as the unit test alone would leave unverified.
	 */
	@Test
	void actorDisplayNameIsPopulatedWithTheSeededActorsEmailForARealActor() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-actor-name"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", UUID.randomUUID(),
				Instant.now());

		var result = auditLogSearch(host, adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).hasSize(1);
		assertThat(body.content().get(0).actorId()).isEqualTo(admin.getId());
		assertThat(body.content().get(0).actorDisplayName()).isEqualTo("admin@example.test");
	}

	/**
	 * Closes the {@code from}-after-{@code to} finding: proven only via a
	 * direct {@code AuditLogSearchCriteria} unit test elsewhere ({@code
	 * AuditLogSearchCriteriaTest#fromAfterToThrowsInvalidAuditLogSearchException}),
	 * unlike the malformed-{@code from}/malformed-{@code to}/unallowed-{@code
	 * sort} cases which are already proven through this real HTTP path -
	 * this mirrors those cases for the {@code from > to} validation rule.
	 */
	@Test
	void fromAfterToReturnsBadRequestThroughTheRealHttpPath() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-from-after-to"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		Instant now = Instant.now();
		String from = now.toString();
		String to = now.minusSeconds(60).toString();

		var result = auditLogSearch(host, adminToken, "from=" + from + "&to=" + to);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void aRealButNonMatchingFilterReturnsAnEmptyPageNotAnError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-empty-match"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");

		var result = auditLogSearch(host, adminToken, "action=nonexistent.action");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		var body = result.getBody().data();
		assertThat(body.content()).isEmpty();
		assertThat(body.totalElements()).isEqualTo(0);
	}

	/**
	 * Closes the unrestricted-{@code sort}-parameter finding: {@code
	 * metadata} is a real {@link AuditLogEntryResponse} field but is not on
	 * {@code AuditLogQueryService.SORTABLE_PROPERTIES}, so it must be
	 * rejected with a clean 400 rather than reaching the repository as an
	 * unhandled {@code PropertyReferenceException} (500).
	 */
	@Test
	void sortByAnUnallowedPropertyReturns400() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-sort-reject"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");

		var result = auditLogSearch(host, adminToken, "sort=metadata,asc");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void tenantAdminCanSearch() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-authz-admin"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");

		var result = auditLogSearch(host, adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void readOnlyAuditorCanSearch() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-authz-auditor"));
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String auditorToken = loginAndGetToken(host, "auditor@example.test");

		var result = auditLogSearch(host, auditorToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	/**
	 * {@code FINANCE_STAFF} holds the coarse {@code AUDIT_LOG}/{@code VIEW}
	 * domain grant in {@code PermissionCheckServiceImpl}'s matrix, but is not
	 * on {@code AuditLogQueryService.VIEWER_ALLOWED_ROLES} - proves the
	 * narrower allowlist gate actually denies a role the coarse check alone
	 * would let through.
	 */
	@Test
	void financeStaffHoldsTheCoarseGrantButIsDeniedByTheNarrowerAllowlist() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-authz-finance"));
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String host = hostFor(tenant.getSubdomain());
		String financeToken = loginAndGetToken(host, "finance@example.test");

		var result = auditLogSearch(host, financeToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void contentManagerHoldsTheCoarseGrantButIsDeniedByTheNarrowerAllowlist() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-authz-content"));
		seedTenantUser(tenant.getId(), "content@example.test", RAW_PASSWORD, Role.CONTENT_MANAGER);
		String host = hostFor(tenant.getSubdomain());
		String contentToken = loginAndGetToken(host, "content@example.test");

		var result = auditLogSearch(host, contentToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void studentHasNoGrantAtAllAndIsDenied() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-authz-student"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String studentToken = loginAndGetToken(host, "student@example.test");

		var result = auditLogSearch(host, studentToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void teacherHasNoGrantAtAllAndIsDenied() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-authz-teacher"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");

		var result = auditLogSearch(host, teacherToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Closes the unbounded-{@code size}-parameter finding: {@code
	 * AuditLogQueryService#clampPageSize} caps the effective page size at
	 * {@code MAX_PAGE_SIZE} (100) regardless of what the client requests.
	 */
	@Test
	void requestingAnOversizedPageSizeIsClampedToTheServerSideMaximum() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-clamp"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		seedAuditLogRow(tenant.getId(), admin.getId(), "course.price_changed", "course", UUID.randomUUID(),
				Instant.now());

		var result = auditLogSearch(host, adminToken, "size=1000");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().size()).isEqualTo(100);
	}

	/**
	 * Closes the malformed-{@code from}/{@code to}-parameter finding: {@code
	 * AuditLogController} binds {@code from}/{@code to} as {@code
	 * @RequestParam Instant} - a non-ISO value must fail type conversion as a
	 * clean {@code 400} (via {@code GlobalExceptionHandler
	 * #handleTypeMismatch}), not an unhandled {@code 500}.
	 */
	@Test
	void malformedFromQueryParamReturnsACleanBadRequestNotAnUnhandledServerError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-bad-from"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");

		var result = auditLogSearch(host, adminToken, "from=not-a-date");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedToQueryParamReturnsACleanBadRequestNotAnUnhandledServerError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-bad-to"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");

		var result = auditLogSearch(host, adminToken, "to=not-a-date");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * Closes the malformed-{@code metadata}-JSON finding: {@code
	 * AuditLogQueryService#deserializeMetadata}'s own javadoc documents this
	 * as an intentional "fail loudly rather than silently return an
	 * empty/wrong metadata map" decision - a row whose {@code metadata} is
	 * valid {@code jsonb} (Postgres itself would reject truly-invalid JSON
	 * text at insert time) but not the expected JSON-object shape must
	 * surface as a clean, generic {@code 500} via {@code
	 * GlobalExceptionHandler#handleUnexpected}, not silently corrupt the rest
	 * of the page or leak internal detail.
	 */
	@Test
	void aRowWithMetadataThatCannotDeserializeIntoAMapFailsWithACleanServerErrorRatherThanCorruptingTheWholePage() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-bad-metadata"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		seedAuditLogRowWithMetadata(tenant.getId(), admin.getId(), "course.price_changed", "course",
				UUID.randomUUID(), Instant.now(), "[1,2,3]");

		var result = auditLogSearch(host, adminToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	/**
	 * Closes the unauthenticated-access finding: {@code AuditLogController}
	 * is gated {@code @PreAuthorize("isAuthenticated()")} - a request with no
	 * {@code Authorization} header at all must be rejected {@code 401}, the
	 * same guarantee every other authenticated endpoint on this platform
	 * gives (see {@code PermissionEnforcementIntegrationTest
	 * #noTokenInvalidTokenAndAnExpiredSessionAllReturn401ForTheFixtureEndpointSpecifically}).
	 */
	@Test
	void noAuthorizationHeaderReturns401() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("audit-no-token"));
		String host = hostFor(tenant.getSubdomain());

		var result = auditLogSearch(host, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private void assertDescending(List<AuditLogEntryResponse> content) {
		for (int i = 0; i < content.size() - 1; i++) {
			assertThat(content.get(i).occurredAt()).isAfterOrEqualTo(content.get(i + 1).occurredAt());
		}
	}

}

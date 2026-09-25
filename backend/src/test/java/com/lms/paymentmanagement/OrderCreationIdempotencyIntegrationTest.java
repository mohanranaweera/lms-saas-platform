package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Wave 6 §3.3/§4/§8 - {@code OrderService#createOrder}'s new {@code
 * idempotencyKey} replay behavior: a same-key repeat returns the same order
 * (200, not 201, per plan §4's explicit contract) with no duplicate row
 * persisted; a genuinely concurrent same-key race is closed by V52's {@code
 * uq_student_order_idempotency} partial unique index plus the {@code
 * DataIntegrityViolationException}-catch-and-requery fallback (mirroring
 * {@code EnrollmentActivationService}'s established insert-race idiom); a
 * different/absent key still creates a genuinely new order (regression
 * check).
 */
class OrderCreationIdempotencyIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void aRepeatedCreateOrderCallWithTheSameIdempotencyKeyReplaysTheSameOrderWithNoDuplicateRow() {
		Fixture fixture = seedTenant("order-idem-replay");
		UUID idempotencyKey = UUID.randomUUID();

		HttpResult<OrderResponse> first = createOrder(fixture.host(), fixture.studentToken(), fixture.course().id(),
				null, idempotencyKey);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<OrderResponse> second = createOrder(fixture.host(), fixture.studentToken(), fixture.course().id(),
				null, idempotencyKey);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody().data().id()).isEqualTo(first.getBody().data().id());

		Long orderCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ?", Long.class,
				fixture.tenant().getId(), fixture.studentId());
		assertThat(orderCount).isEqualTo(1L);
	}

	@Test
	void twoGenuinelyConcurrentCreateOrderCallsWithTheSameIdempotencyKeyProduceExactlyOneOrder() throws Exception {
		Fixture fixture = seedTenant("order-idem-race");
		UUID idempotencyKey = UUID.randomUUID();

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<OrderResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return createOrder(fixture.host(), fixture.studentToken(), fixture.course().id(), null,
						idempotencyKey);
			});
		}

		List<HttpStatus> statuses = new ArrayList<>();
		List<UUID> orderIds = new ArrayList<>();
		try {
			List<Future<HttpResult<OrderResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<OrderResponse>> future : futures) {
				HttpResult<OrderResponse> result = future.get(15, TimeUnit.SECONDS);
				statuses.add(result.getStatusCode());
				if (result.getStatusCode().is2xxSuccessful()) {
					orderIds.add(result.getBody().data().id());
				}
			}
		}
		finally {
			executor.shutdownNow();
		}

		// Every delivery must complete cleanly (never an unhandled 500 from
		// a raw constraint-violation leaking through) and both must resolve
		// to the SAME order id.
		assertThat(statuses).allMatch(HttpStatus::is2xxSuccessful);
		assertThat(orderIds).hasSize(2).containsOnly(orderIds.get(0));

		Long orderCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ?", Long.class,
				fixture.tenant().getId(), fixture.studentId());
		assertThat(orderCount).isEqualTo(1L);
	}

	/**
	 * Security-review follow-up (Wave 6 completion review, finding #2): {@code
	 * OrderService#createOrder}'s idempotency lookup is {@code
	 * findByStudentIdAndIdempotencyKey(principal.userId(), idempotencyKey)} -
	 * scoped to the CALLER's own, server-resolved {@code principal.userId()},
	 * never to a client-supplied student id. Reusing another student's exact
	 * {@code idempotencyKey} value (which a client can always observe/guess,
	 * since it is client-supplied) must therefore never replay or otherwise
	 * expose that other student's order - it must simply behave as a normal,
	 * independent order creation for the second student. This proves the
	 * idempotency key can never be leveraged as a cross-student authorization
	 * bypass.
	 */
	@Test
	void aSecondStudentReusingTheFirstStudentsExactIdempotencyKeyNeverReplaysOrLeaksTheFirstStudentsOrder() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("order-idem-cross-student"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser studentA = seedActiveStudent(tenant.getId(), "student-a@example.test");
		TenantUser studentB = seedActiveStudent(tenant.getId(), "student-b@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentATokenValue = loginAndGetToken(host, "student-a@example.test");
		String studentBTokenValue = loginAndGetToken(host, "student-b@example.test");
		CourseResponse courseA = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("order-idem-cross-student-a"), teacher.getId(), CourseStatus.PUBLIC));
		CourseResponse courseB = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("order-idem-cross-student-b"), teacher.getId(), CourseStatus.PUBLIC));
		UUID sharedIdempotencyKey = UUID.randomUUID();

		HttpResult<OrderResponse> orderA = createOrder(host, studentATokenValue, courseA.id(), null,
				sharedIdempotencyKey);
		assertThat(orderA.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		// Student B independently creates their own order using the exact
		// SAME idempotencyKey value student A used - must succeed as a
		// genuinely new, independent order for student B, never a replay of
		// student A's order.
		HttpResult<OrderResponse> orderB = createOrder(host, studentBTokenValue, courseB.id(), null,
				sharedIdempotencyKey);
		assertThat(orderB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(orderB.getBody().data().id()).isNotEqualTo(orderA.getBody().data().id());

		Long orderCountForA = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ?", Long.class,
				tenant.getId(), studentA.getId());
		Long orderCountForB = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ?", Long.class,
				tenant.getId(), studentB.getId());
		assertThat(orderCountForA).isEqualTo(1L);
		assertThat(orderCountForB).isEqualTo(1L);
	}

	@Test
	void aDifferentIdempotencyKeyStillCreatesAGenuinelyNewOrder() {
		Fixture fixture = seedTenant("order-idem-diff-key");

		HttpResult<OrderResponse> first = createOrder(fixture.host(), fixture.studentToken(), fixture.course().id(),
				null, UUID.randomUUID());
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		// A second order for the SAME course would be rejected as "already
		// enrolled" once the first is confirmed, but this fixture's order
		// is never confirmed - use a second course instead so this proves
		// key-based dedup does NOT collapse two independently-keyed orders,
		// without entangling this test with enrollment-state rules.
		CourseResponse secondCourse = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("order-idem-diff-key-2"), fixture.teacher().getId(), CourseStatus.PUBLIC));

		HttpResult<OrderResponse> second = createOrder(fixture.host(), fixture.studentToken(), secondCourse.id(),
				null, UUID.randomUUID());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getBody().data().id()).isNotEqualTo(first.getBody().data().id());

		Long orderCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ?", Long.class,
				fixture.tenant().getId(), fixture.studentId());
		assertThat(orderCount).isEqualTo(2L);
	}

	@Test
	void noIdempotencyKeySuppliedStillCreatesAGenuinelyNewOrderEveryTime() {
		Fixture fixture = seedTenant("order-idem-no-key");

		HttpResult<OrderResponse> first = createOrder(fixture.host(), fixture.studentToken(), fixture.course().id());
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		CourseResponse secondCourse = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("order-idem-no-key-2"), fixture.teacher().getId(), CourseStatus.PUBLIC));
		HttpResult<OrderResponse> second = createOrder(fixture.host(), fixture.studentToken(), secondCourse.id());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getBody().data().id()).isNotEqualTo(first.getBody().data().id());
	}

	// ------------------------------------------------------------------
	// Fixture.
	// ------------------------------------------------------------------

	private Fixture seedTenant(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		return new Fixture(tenant, host, adminToken, studentToken, teacher, student.getId(), course);
	}

	private record Fixture(Tenant tenant, String host, String adminToken, String studentToken, TenantUser teacher,
			UUID studentId, CourseResponse course) {
	}

}

package com.lms.ledgersettlementmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementStatus;
import com.lms.ledgersettlementmanagement.repository.TeacherRevenueShareRateRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementItemRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementRepository;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.CourseBreakdown;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.RateView;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.SettlementDetail;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.SettlementView;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wave 7 Phase D - teacher settlement foundation. Fixture (tenant timezone
 * UTC, teacher1 rate 40.00% from 2025-01-01):
 *
 * <pre>
 * Course X (teacher1, 100.00): +100 2025-03-05, +100 2025-03-20 (refund 30.00 recorded 2025-04-02)
 * Course Y (teacher1,  40.00): +40  2025-03-25
 * Course Z (teacher2, 250.00): +250 2025-03-10   (other teacher - never in teacher1's statement)
 * March 2025, teacher1: gross 240.00, refunds 0.00, net 240.00, share 40% = 96.00, 3 items
 * April 1-9 2025, teacher1: gross 0.00, refunds 30.00, net -30.00, share -12.00
 * </pre>
 */
@Tag("cross-tenant")
class TeacherSettlementIntegrationTest extends FinanceTestSupport {

	private static final LocalDate MARCH_1 = LocalDate.of(2025, 3, 1);

	private static final LocalDate MARCH_31 = LocalDate.of(2025, 3, 31);

	@Autowired
	private TeacherSettlementRepository settlementRepository;

	@Autowired
	private TeacherSettlementItemRepository itemRepository;

	@Autowired
	private TeacherRevenueShareRateRepository rateRepository;

	@Test
	void calculatesStoresAndNeverDoubleSettles() {
		Fixture f = seedFixture("stl-main");
		FinanceTenant t = f.tenant();
		UUID teacherId = t.teacher().getId();

		HttpResult<SettlementDetail> march = calculate(t, t.financeToken(), teacherId, MARCH_1, MARCH_31);
		assertThat(march.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		SettlementView s = march.getBody().data().settlement();
		assertThat(s.kind()).isEqualTo(TeacherSettlementKind.REGULAR);
		assertThat(s.grossAmount()).isEqualByComparingTo("240.00");
		assertThat(s.refundAmount()).isEqualByComparingTo("0.00");
		assertThat(s.netAmount()).isEqualByComparingTo("240.00");
		assertThat(s.sharePercent()).isEqualByComparingTo("40.00");
		assertThat(s.shareAmount()).isEqualByComparingTo("96.00");
		assertThat(s.effectiveShareAmount()).isEqualByComparingTo("96.00");
		assertThat(s.status()).isEqualTo(TeacherSettlementStatus.CALCULATED);
		assertThat(s.teacherEmail()).isEqualTo("teacher@example.test");
		List<CourseBreakdown> courses = march.getBody().data().courses();
		assertThat(courses).extracting(CourseBreakdown::courseId).containsExactly(f.courseX().id(), f.courseY().id());
		assertThat(courses.get(0).net()).isEqualByComparingTo("200.00");
		assertThat(courses.get(0).entryCount()).isEqualTo(2);
		assertThat(itemCount(t)).isEqualTo(3);
		assertThat(auditCount(s.id(), "teacher_settlement.calculated")).isEqualTo(1);

		// Re-running the same period: 409, zero new rows (idempotency).
		long settlementsBefore = settlementCount(t);
		assertThat(calculate(t, t.financeToken(), teacherId, MARCH_1, MARCH_31).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		// Overlapping period: 409 too.
		assertThat(calculate(t, t.financeToken(), teacherId, LocalDate.of(2025, 3, 15), LocalDate.of(2025, 4, 5))
			.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(settlementCount(t)).isEqualTo(settlementsBefore);
		assertThat(itemCount(t)).isEqualTo(3);

		// A refund recorded after the settled period lands in the next statement.
		SettlementView april = calculate(t, t.financeToken(), teacherId, LocalDate.of(2025, 4, 1),
				LocalDate.of(2025, 4, 9))
			.getBody()
			.data()
			.settlement();
		assertThat(april.grossAmount()).isEqualByComparingTo("0.00");
		assertThat(april.refundAmount()).isEqualByComparingTo("30.00");
		assertThat(april.netAmount()).isEqualByComparingTo("-30.00");
		assertThat(april.shareAmount()).isEqualByComparingTo("-12.00");
	}

	@Test
	void rateHistoryIsSnapshottedAndNeverRecomputed() {
		Fixture f = seedFixture("stl-rate");
		FinanceTenant t = f.tenant();
		UUID teacherId = t.teacher().getId();
		UUID marchId = calculate(t, t.financeToken(), teacherId, MARCH_1, MARCH_31).getBody()
			.data()
			.settlement()
			.id();

		// A later rate row effective inside the already-settled period must not
		// alter the stored statement.
		assertThat(addRate(t, t.financeToken(), teacherId, "75.00", LocalDate.of(2025, 3, 1)).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
		SettlementView reread = getSettlement(t, t.financeToken(), marchId).getBody().data().settlement();
		assertThat(reread.sharePercent()).isEqualByComparingTo("40.00");
		assertThat(reread.shareAmount()).isEqualByComparingTo("96.00");

		// Duplicate effective date for the same teacher: 409.
		assertThat(addRate(t, t.financeToken(), teacherId, "10.00", LocalDate.of(2025, 3, 1)).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		// Rate changing inside a requested period: rejected.
		addRate(t, t.financeToken(), teacherId, "50.00", LocalDate.of(2025, 4, 10));
		assertThat(calculate(t, t.financeToken(), teacherId, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30))
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		// Invalid percents.
		for (String bad : List.of("-1", "100.01", "12.345")) {
			assertThat(addRate(t, t.financeToken(), teacherId, bad, LocalDate.of(2025, 6, 1)).getStatusCode())
				.as("percent %s", bad)
				.isEqualTo(HttpStatus.BAD_REQUEST);
		}
		assertThat(listRates(t, t.auditorToken()).getBody().data()).extracting(RateView::teacherId)
			.containsOnly(teacherId);
	}

	@Test
	void rejectsOpenPeriodsMissingRatesAndNonTeachers() {
		Fixture f = seedFixture("stl-reject");
		FinanceTenant t = f.tenant();
		LocalDate today = LocalDate.now(java.time.ZoneId.of("UTC"));

		assertThat(calculate(t, t.financeToken(), t.teacher().getId(), today.minusDays(3), today).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(calculate(t, t.financeToken(), t.teacher().getId(), MARCH_31, MARCH_1).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// teacher2 has no rate.
		assertThat(calculate(t, t.financeToken(), t.teacher2().getId(), MARCH_1, MARCH_31).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// A non-teacher user id is not a teacher.
		UUID financeUserId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'finance@example.test'", UUID.class,
				t.tenant().getId());
		assertThat(calculate(t, t.financeToken(), financeUserId, MARCH_1, MARCH_31).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		// Period with no ledger entries.
		assertThat(calculate(t, t.financeToken(), t.teacher().getId(), LocalDate.of(2025, 2, 1),
				LocalDate.of(2025, 2, 28))
			.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(settlementCount(t)).isZero();
	}

	@Test
	void adjustmentsAppendAndMarkPaidIsOneWay() {
		Fixture f = seedFixture("stl-adjust");
		FinanceTenant t = f.tenant();
		UUID marchId = calculate(t, t.financeToken(), t.teacher().getId(), MARCH_1, MARCH_31).getBody()
			.data()
			.settlement()
			.id();

		assertThat(adjust(t, t.financeToken(), marchId, "0.00", "zero").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(adjust(t, t.financeToken(), marchId, "5.00", null).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(adjust(t, t.financeToken(), marchId, "1.001", "precision").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);

		HttpResult<SettlementDetail> adjusted = adjust(t, t.financeToken(), marchId, "10.50", "Bonus session");
		assertThat(adjusted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		SettlementDetail detail = adjusted.getBody().data();
		assertThat(detail.settlement().shareAmount()).isEqualByComparingTo("96.00");
		assertThat(detail.settlement().effectiveShareAmount()).isEqualByComparingTo("106.50");
		assertThat(detail.adjustments()).hasSize(1);
		SettlementView adjustment = detail.adjustments().get(0);
		assertThat(adjustment.kind()).isEqualTo(TeacherSettlementKind.ADJUSTMENT);
		assertThat(adjustment.adjustsSettlementId()).isEqualTo(marchId);
		assertThat(adjustment.reason()).isEqualTo("Bonus session");
		assertThat(auditCount(marchId, "teacher_settlement.adjusted")).isEqualTo(1);

		// Adjusting an adjustment is refused - corrections reference the original.
		assertThat(adjust(t, t.financeToken(), adjustment.id(), "1.00", "chain").getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);

		HttpResult<SettlementDetail> paid = markPaid(t, t.financeToken(), marchId, "BANK-TX-42");
		assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(paid.getBody().data().settlement().status()).isEqualTo(TeacherSettlementStatus.PAID);
		assertThat(paid.getBody().data().settlement().payoutReference()).isEqualTo("BANK-TX-42");
		assertThat(paid.getBody().data().settlement().paidByEmail()).isEqualTo("finance@example.test");
		assertThat(markPaid(t, t.financeToken(), marchId, "again").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(auditCount(marchId, "teacher_settlement.marked_paid")).isEqualTo(1);

		// Stored figures are unchanged by every action above.
		SettlementView reread = getSettlement(t, t.auditorToken(), marchId).getBody().data().settlement();
		assertThat(reread.grossAmount()).isEqualByComparingTo("240.00");
		assertThat(reread.shareAmount()).isEqualByComparingTo("96.00");
	}

	@Test
	void permissionsAndCrossTenantIsolation() {
		Fixture a = seedFixture("stl-xt-a");
		FinanceTenant ta = a.tenant();
		FinanceTenant tb = seedFinanceTenant("stl-xt-b");
		UUID settlementA = calculate(ta, ta.adminToken(), ta.teacher().getId(), MARCH_1, MARCH_31).getBody()
			.data()
			.settlement()
			.id();

		// Auditor: read-only.
		assertThat(listSettlements(ta, ta.auditorToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getSettlement(ta, ta.auditorToken(), settlementA).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(markPaid(ta, ta.auditorToken(), settlementA, null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(adjust(ta, ta.auditorToken(), settlementA, "1.00", "x").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(addRate(ta, ta.auditorToken(), ta.teacher().getId(), "5.00", LocalDate.of(2025, 9, 1))
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(calculate(ta, ta.auditorToken(), ta.teacher().getId(), LocalDate.of(2025, 4, 1),
				LocalDate.of(2025, 4, 9))
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// Teachers/students/content managers: no access at all (incl. their own statement).
		for (String token : List.of(ta.teacherToken(), ta.studentToken(), ta.contentToken())) {
			assertThat(listSettlements(ta, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(getSettlement(ta, token, settlementA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(listRates(ta, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		}

		// Tenant B: tenant A's settlement/teacher are invisible (404, never leaked).
		assertThat(getSettlement(tb, tb.adminToken(), settlementA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(markPaid(tb, tb.adminToken(), settlementA, "hostile").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(adjust(tb, tb.adminToken(), settlementA, "-96.00", "hostile").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(addRate(tb, tb.adminToken(), ta.teacher().getId(), "90.00", LocalDate.of(2025, 1, 1))
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(calculate(tb, tb.adminToken(), ta.teacher().getId(), MARCH_1, MARCH_31).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(listSettlements(tb, tb.adminToken()).getBody().data().content()).extracting(SettlementView::id)
			.doesNotContain(settlementA);
		assertThat(listRates(tb, tb.adminToken()).getBody().data()).isEmpty();

		// Tenant A's statement is untouched by B's hostile attempts.
		SettlementView untouched = getSettlement(ta, ta.financeToken(), settlementA).getBody().data().settlement();
		assertThat(untouched.status()).isEqualTo(TeacherSettlementStatus.CALCULATED);
		assertThat(untouched.effectiveShareAmount()).isEqualByComparingTo("96.00");
	}

	@Test
	void schemaEnforcesSingleSettlementPerLedgerEntryAndNoDelete() {
		Fixture f = seedFixture("stl-schema");
		FinanceTenant t = f.tenant();
		UUID settlementId = calculate(t, t.financeToken(), t.teacher().getId(), MARCH_1, MARCH_31).getBody()
			.data()
			.settlement()
			.id();
		Map<String, Object> item = jdbcTemplate.queryForMap(
				"SELECT ledger_entry_id, course_id, amount FROM teacher_settlement_item WHERE settlement_id = ? LIMIT 1",
				settlementId);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO teacher_settlement_item (id, tenant_id, settlement_id, ledger_entry_id, course_id, amount)"
						+ " VALUES (?, ?, ?, ?, ?, ?)",
				UUID.randomUUID(), t.tenant().getId(), settlementId, item.get("ledger_entry_id"),
				item.get("course_id"), item.get("amount")))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO teacher_settlement (id, tenant_id, teacher_id, kind, period_start, period_end,"
						+ " gross_amount, refund_amount, net_amount, share_percent, rate_id, share_amount, currency,"
						+ " status, calculated_by, calculated_at)"
						+ " SELECT gen_random_uuid(), tenant_id, teacher_id, kind, period_start, period_end,"
						+ " gross_amount, refund_amount, net_amount, share_percent, rate_id, share_amount, currency,"
						+ " 'CALCULATED', calculated_by, now() FROM teacher_settlement WHERE id = ?",
				settlementId))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> settlementRepository.deleteById(settlementId))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> itemRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> rateRepository.deleteAllInBatch())
			.isInstanceOf(UnsupportedOperationException.class);

		// No ledger row was written or modified by settlement.
		Integer ledgerRows = jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?",
				Integer.class, t.tenant().getId());
		assertThat(ledgerRows).isEqualTo(5);
	}

	@Test
	void concurrentMarkPaidSerializesExactlyOneWinner() throws Exception {
		Fixture f = seedFixture("stl-race");
		FinanceTenant t = f.tenant();
		UUID id = calculate(t, t.financeToken(), t.teacher().getId(), MARCH_1, MARCH_31).getBody()
			.data()
			.settlement()
			.id();

		java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var results = pool.invokeAll(List.<java.util.concurrent.Callable<HttpStatus>>of(
					() -> {
						barrier.await();
						return markPaid(t, t.financeToken(), id, "REF-A").getStatusCode();
					}, () -> {
						barrier.await();
						return markPaid(t, t.adminToken(), id, "REF-B").getStatusCode();
					}));
			List<HttpStatus> statuses = new java.util.ArrayList<>();
			for (var result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(auditCount(id, "teacher_settlement.marked_paid")).isEqualTo(1);
	}

	@Test
	void payeeListReturnsTenantUserIdsForFinanceRolesOnly() {
		FinanceTenant a = seedFinanceTenant("stl-payee-a");
		FinanceTenant b = seedFinanceTenant("stl-payee-b");
		Map<String, Object> body = Map.of("name", "Ada Lovelace", "email", "ada@example.test", "password",
				RAW_PASSWORD);
		HttpResult<Map> created = parseSingle(perform(authenticated(json(post("/api/v1/teachers"), body), a.host(),
				a.adminToken())), Map.class);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID adaUserId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'ada@example.test'", UUID.class,
				a.tenant().getId());

		HttpResult<List<Map>> finance = parseList(
				perform(authenticated(get("/api/v1/finance/teachers"), a.host(), a.financeToken())), Map.class);
		assertThat(finance.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(finance.getBody().data()).anySatisfy(row -> {
			assertThat(row.get("userId")).isEqualTo(adaUserId.toString());
			assertThat(row.get("name")).isEqualTo("Ada Lovelace");
		});
		assertThat(parseList(perform(authenticated(get("/api/v1/finance/teachers"), a.host(), a.auditorToken())),
				Map.class)
			.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(parseList(perform(authenticated(get("/api/v1/finance/teachers"), a.host(), a.studentToken())),
				Map.class)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<List<Map>> otherTenant = parseList(
				perform(authenticated(get("/api/v1/finance/teachers"), b.host(), b.financeToken())), Map.class);
		assertThat(otherTenant.getBody().data()).noneSatisfy(
				row -> assertThat(row.get("userId")).isEqualTo(adaUserId.toString()));
	}

	// ------------------------------------------------------------------
	// Fixture + HTTP helpers.
	// ------------------------------------------------------------------

	private Fixture seedFixture(String prefix) {
		FinanceTenant t = seedFinanceTenant(prefix);
		CourseResponse courseX = createPricedCourse(t, prefix + "-x", t.teacher().getId(), "100.00");
		CourseResponse courseY = createPricedCourse(t, prefix + "-y", t.teacher().getId(), "40.00");
		CourseResponse courseZ = createPricedCourse(t, prefix + "-z", t.teacher2().getId(), "250.00");
		backdateConfirmedEntry(confirmedPayment(t, courseX.id()), utc("2025-03-05T10:00:00Z"));
		UUID p2 = confirmedPayment(t, courseX.id());
		backdateConfirmedEntry(p2, utc("2025-03-20T10:00:00Z"));
		refund(t, p2, "30.00");
		backdateRefundEntry(p2, utc("2025-04-02T09:00:00Z"));
		backdateConfirmedEntry(confirmedPayment(t, courseY.id()), utc("2025-03-25T10:00:00Z"));
		backdateConfirmedEntry(confirmedPayment(t, courseZ.id()), utc("2025-03-10T10:00:00Z"));
		HttpResult<RateView> rate = addRate(t, t.financeToken(), t.teacher().getId(), "40.00",
				LocalDate.of(2025, 1, 1));
		if (rate.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Rate creation failed: " + rate.getStatusCode() + " " + rate.getBody());
		}
		return new Fixture(t, courseX, courseY, courseZ);
	}

	private record Fixture(FinanceTenant tenant, CourseResponse courseX, CourseResponse courseY,
			CourseResponse courseZ) {
	}

	private HttpResult<RateView> addRate(FinanceTenant t, String token, UUID teacherId, String percent,
			LocalDate effectiveFrom) {
		Map<String, Object> body = new HashMap<>();
		body.put("teacherId", teacherId);
		body.put("sharePercent", new java.math.BigDecimal(percent));
		body.put("effectiveFrom", effectiveFrom.toString());
		return parseSingle(perform(authenticated(json(post("/api/v1/finance/teacher-share-rates"), body), t.host(),
				token)), RateView.class);
	}

	private HttpResult<List<RateView>> listRates(FinanceTenant t, String token) {
		return parseList(perform(authenticated(get("/api/v1/finance/teacher-share-rates"), t.host(), token)),
				RateView.class);
	}

	private HttpResult<SettlementDetail> calculate(FinanceTenant t, String token, UUID teacherId, LocalDate start,
			LocalDate end) {
		Map<String, Object> body = Map.of("teacherId", teacherId, "periodStart", start.toString(), "periodEnd",
				end.toString());
		return parseSingle(perform(authenticated(json(post("/api/v1/finance/teacher-settlements"), body), t.host(),
				token)), SettlementDetail.class);
	}

	private HttpResult<SettlementDetail> getSettlement(FinanceTenant t, String token, UUID id) {
		return parseSingle(perform(authenticated(get("/api/v1/finance/teacher-settlements/{id}", id), t.host(),
				token)), SettlementDetail.class);
	}

	private HttpResult<PageResponse<SettlementView>> listSettlements(FinanceTenant t, String token) {
		return parsePage(perform(authenticated(get("/api/v1/finance/teacher-settlements"), t.host(), token)),
				SettlementView.class);
	}

	private HttpResult<SettlementDetail> markPaid(FinanceTenant t, String token, UUID id, String reference) {
		Map<String, Object> body = new HashMap<>();
		body.put("payoutReference", reference);
		return parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements/{id}/mark-paid", id), body), t.host(), token)),
				SettlementDetail.class);
	}

	private HttpResult<SettlementDetail> adjust(FinanceTenant t, String token, UUID id, String amount,
			String reason) {
		Map<String, Object> body = new HashMap<>();
		body.put("amount", new java.math.BigDecimal(amount));
		body.put("reason", reason);
		return parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements/{id}/adjustments", id), body), t.host(), token)),
				SettlementDetail.class);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private long settlementCount(FinanceTenant t) {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM teacher_settlement WHERE tenant_id = ?",
				Long.class, t.tenant().getId());
		return count == null ? 0 : count;
	}

	private long itemCount(FinanceTenant t) {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM teacher_settlement_item WHERE tenant_id = ?",
				Long.class, t.tenant().getId());
		return count == null ? 0 : count;
	}

	private int auditCount(UUID targetId, String action) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_id = ? AND action = ?", Integer.class, targetId, action);
		return count == null ? 0 : count;
	}

}

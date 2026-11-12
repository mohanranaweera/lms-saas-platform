package com.lms.ledgersettlementmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.SettlementDetail;
import com.lms.ledgersettlementmanagement.service.TeacherSettlementService.SettlementView;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.EntryView;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.PostResult;
import com.lms.ledgersettlementmanagement.service.TeacherWalletService.WalletView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * TEACHER-WALLET-01 / ADR-036: Teachers -> Show Wallet over teacher
 * settlements. Balance = unpaid settlements + POSTED wallet entries.
 */
@Tag("cross-tenant")
class TeacherWalletIntegrationTest extends FinanceTestSupport {

	@Test
	void zeroAndNonZeroOpeningBalancesAreRecordedOnceWithoutCashMovement() {
		FinanceTenant t = seedFinanceTenant("wal-open");
		UUID teacher = t.teacher().getId();
		long ledgerBefore = count("ledger_entry", t);

		WalletView none = getWallet(t, t.financeToken(), teacher).getBody().data();
		assertThat(none.exists()).isFalse();
		assertThat(none.currency()).isNotBlank().hasSize(3);

		HttpResult<WalletView> created = create(t, t.financeToken(), teacher, "0");
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(created.getBody().data().balance()).isEqualByComparingTo("0.00");
		// Retry with the same opening balance: same wallet, no new rows.
		assertThat(create(t, t.financeToken(), teacher, "0.00").getStatusCode()).isEqualTo(HttpStatus.OK);
		// A different opening balance on an existing wallet: 409.
		assertThat(create(t, t.financeToken(), teacher, "5.00").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(walletRows(t)).isEqualTo(1);
		assertThat(entryRows(t, "OPENING")).isEqualTo(1);

		WalletView second = create(t, t.financeToken(), t.teacher2().getId(), "1250.50").getBody().data();
		assertThat(second.balance()).isEqualByComparingTo("1250.50");
		assertThat(second.walletEntries()).isEqualByComparingTo("1250.50");
		assertThat(second.unpaidSettlements()).isEqualByComparingTo("0.00");

		// Invalid opening balances.
		for (String bad : List.of("-1", "1.001")) {
			assertThat(create(t, t.adminToken(), t.teacher2().getId(), bad).getStatusCode())
				.isIn(HttpStatus.BAD_REQUEST, HttpStatus.CONFLICT);
		}
		assertThat(count("ledger_entry", t)).isEqualTo(ledgerBefore);
		assertThat(auditCount("teacher_wallet.created", t)).isEqualTo(2);
	}

	@Test
	void payOutAndPayInAreWalletOnlyExactAndNeverOverdraw() {
		FinanceTenant t = seedFinanceTenant("wal-post");
		UUID teacher = t.teacher().getId();
		create(t, t.financeToken(), teacher, "100.00");
		long ledgerBefore = count("ledger_entry", t);
		long expensesBefore = count("expense", t);

		HttpResult<PostResult> out = payOut(t, t.financeToken(), teacher, "40.25", "March payout", null, null,
				"100.00", key());
		assertThat(out.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		EntryView entry = out.getBody().data().entry();
		assertThat(entry.kind()).isEqualTo(TeacherSettlementKind.PAY_OUT);
		assertThat(entry.amount()).isEqualByComparingTo("-40.25");
		assertThat(entry.balanceBefore()).isEqualByComparingTo("100.00");
		assertThat(entry.balanceAfter()).isEqualByComparingTo("59.75");
		assertThat(out.getBody().data().wallet().balance()).isEqualByComparingTo("59.75");

		HttpResult<PostResult> in = payIn(t, t.financeToken(), teacher, "0.25", "Bonus", null, key());
		assertThat(in.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(in.getBody().data().wallet().balance()).isEqualByComparingTo("60.00");

		// Insufficient balance, bad precision, zero/negative, missing description, wrong currency.
		assertThat(payOut(t, t.financeToken(), teacher, "60.01", "Too much", null, null, null, key())
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payOut(t, t.financeToken(), teacher, "1.001", "Precision", null, null, null, key())
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payIn(t, t.financeToken(), teacher, "0", "Zero", null, key()).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payIn(t, t.financeToken(), teacher, "-5", "Negative", null, key()).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payIn(t, t.financeToken(), teacher, "5", " ", null, key()).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payIn(t, t.financeToken(), teacher, "5", "EUR", "XXX", key()).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// Missing Idempotency-Key.
		assertThat(payIn(t, t.financeToken(), teacher, "5", "No key", null, null).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		// A payout exactly to zero is allowed.
		assertThat(payOut(t, t.financeToken(), teacher, "60.00", "Final", null, null, null, key()).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);

		// Account-linked entries are unavailable (no Finance Accounts) - rejected, not dropped;
		// a category without an account is invalid.
		long before = entryRows(t, null);
		assertThat(payOut(t, t.financeToken(), teacher, "1.00", "Linked", UUID.randomUUID(), null, null, key())
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payIn(t, t.financeToken(), teacher, "1.00", "Linked", null, key(), UUID.randomUUID(), null)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(payOut(t, t.financeToken(), teacher, "1.00", "Category only", null, UUID.randomUUID(), null,
				key())
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(entryRows(t, null)).isEqualTo(before);

		// Per-tab activity.
		List<EntryView> payouts = entries(t, t.auditorToken(), teacher, "PAY_OUT").getBody().data();
		assertThat(payouts).extracting(EntryView::description).containsExactly("Final", "March payout");
		assertThat(entries(t, t.auditorToken(), teacher, "PAY_IN").getBody().data()).hasSize(1);

		// Wallet postings never touch student income, ledger or expenses.
		assertThat(count("ledger_entry", t)).isEqualTo(ledgerBefore);
		assertThat(count("expense", t)).isEqualTo(expensesBefore);
		assertThat(auditCount("teacher_wallet.pay_out_recorded", t)).isEqualTo(2);
		assertThat(auditCount("teacher_wallet.pay_in_recorded", t)).isEqualTo(1);
	}

	@Test
	void adjustmentAppendsTheDifferenceAndRejectsStaleEdits() {
		FinanceTenant t = seedFinanceTenant("wal-adj");
		UUID teacher = t.teacher().getId();
		create(t, t.financeToken(), teacher, "50.00");

		assertThat(adjust(t, t.financeToken(), teacher, "80.00", "50.00", null, key()).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		HttpResult<PostResult> adjusted = adjust(t, t.financeToken(), teacher, "80.00", "50.00", "Recount", key());
		assertThat(adjusted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(adjusted.getBody().data().entry().amount()).isEqualByComparingTo("30.00");
		assertThat(adjusted.getBody().data().wallet().balance()).isEqualByComparingTo("80.00");
		// Stale expected balance: 409, nothing appended.
		long before = entryRows(t, null);
		assertThat(adjust(t, t.financeToken(), teacher, "10.00", "50.00", "Stale", key()).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		assertThat(adjust(t, t.financeToken(), teacher, "80.00", "80.00", "Same", key()).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(entryRows(t, null)).isEqualTo(before);
		assertThat(adjust(t, t.financeToken(), teacher, "0.00", "80.00", "Write-off", key()).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
		// History is intact: opening + two adjustments.
		assertThat(entryRows(t, null)).isEqualTo(3);
		assertThat(auditCount("teacher_wallet.balance_adjusted", t)).isEqualTo(2);
		// Posting before a wallet exists: 409.
		assertThat(payIn(t, t.financeToken(), t.teacher2().getId(), "5", "No wallet", null, key()).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void duplicateSubmissionIsIdempotentAndKeyReuseWithAnotherPayloadIsRejected() {
		FinanceTenant t = seedFinanceTenant("wal-idem");
		UUID teacher = t.teacher().getId();
		create(t, t.financeToken(), teacher, "100.00");
		String key = key();
		HttpResult<PostResult> first = payOut(t, t.financeToken(), teacher, "10.00", "Payout", null, null, null, key);
		HttpResult<PostResult> replay = payOut(t, t.financeToken(), teacher, "10.00", "Payout", null, null, null,
				key);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(replay.getBody().data().entry().id()).isEqualTo(first.getBody().data().entry().id());
		assertThat(payOut(t, t.financeToken(), teacher, "11.00", "Payout", null, null, null, key).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		assertThat(payIn(t, t.financeToken(), teacher, "10.00", "Payout", null, key).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		assertThat(entryRows(t, "PAY_OUT")).isEqualTo(1);
		assertThat(getWallet(t, t.financeToken(), teacher).getBody().data().balance()).isEqualByComparingTo("90.00");
	}

	@Test
	void concurrentPayoutsNeverOverdrawAndConcurrentDuplicatesPostOnce() throws Exception {
		FinanceTenant t = seedFinanceTenant("wal-conc");
		UUID teacher = t.teacher().getId();
		create(t, t.financeToken(), teacher, "100.00");

		List<HttpStatus> statuses = runConcurrently(8,
				i -> payOut(t, t.financeToken(), teacher, "30.00", "Concurrent " + i, null, null, null, key())
					.getStatusCode());
		assertThat(statuses.stream().filter(HttpStatus.CREATED::equals).count()).isEqualTo(3);
		assertThat(getWallet(t, t.financeToken(), teacher).getBody().data().balance()).isEqualByComparingTo("10.00");

		String shared = key();
		List<HttpStatus> dupes = runConcurrently(6,
				i -> payOut(t, t.financeToken(), teacher, "1.00", "Same", null, null, null, shared).getStatusCode());
		assertThat(dupes).doesNotContain(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM teacher_settlement WHERE tenant_id = ? AND idempotency_key = ?", Long.class,
				t.tenant().getId(), shared)).isEqualTo(1);
		assertThat(getWallet(t, t.financeToken(), teacher).getBody().data().balance()).isEqualByComparingTo("9.00");
	}

	@Test
	void concurrentWalletCreationLeavesOneWallet() throws Exception {
		FinanceTenant t = seedFinanceTenant("wal-cc");
		UUID teacher = t.teacher().getId();
		List<HttpStatus> statuses = runConcurrently(6,
				i -> create(t, t.financeToken(), teacher, "25.00").getStatusCode());
		assertThat(statuses).contains(HttpStatus.CREATED).doesNotContain(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(walletRows(t)).isEqualTo(1);
		assertThat(entryRows(t, "OPENING")).isEqualTo(1);
	}

	@Test
	void settlementEarningsAndPayoutsMoveTheBalanceAndAppearOnceInTheMatchingTab() {
		FinanceTenant t = seedFinanceTenant("wal-act");
		UUID teacher = t.teacher().getId();
		UUID march = seedMarchSettlement(t, "wal-act"); // share 40.00
		create(t, t.financeToken(), teacher, "10.00");
		long ledgerBefore = count("ledger_entry", t);
		long walletRowsBefore = entryRows(t, null);
		assertThat(balance(t, teacher)).isEqualByComparingTo("50.00");

		// Earned: Pay In tab, read-only settlement line with its period - never in Pay Out.
		List<EntryView> payIns = entries(t, t.financeToken(), teacher, "PAY_IN").getBody().data();
		assertThat(payIns).singleElement().satisfies(line -> {
			assertThat(line.event()).isEqualTo(EntryView.SETTLEMENT_EARNED);
			assertThat(line.source()).isEqualTo("SETTLEMENT");
			assertThat(line.settlementId()).isEqualTo(march);
			assertThat(line.amount()).isEqualByComparingTo("40.00");
			assertThat(line.periodStart()).isEqualTo("2025-03-01");
			assertThat(line.periodEnd()).isEqualTo("2025-03-31");
		});
		assertThat(entries(t, t.financeToken(), teacher, "PAY_OUT").getBody().data()).isEmpty();

		// Settlement adjustments land in the tab matching their sign.
		UUID bonus = settlementAdjust(t, march, "5.00", "Late bonus");
		settlementAdjust(t, march, "-3.00", "Overcount");
		assertThat(balance(t, teacher)).isEqualByComparingTo("52.00");
		assertThat(entries(t, t.financeToken(), teacher, "PAY_IN").getBody().data())
			.extracting(EntryView::event, line -> line.amount().toPlainString())
			.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(EntryView.SETTLEMENT_EARNED, "40.00"),
					org.assertj.core.groups.Tuple.tuple(EntryView.SETTLEMENT_ADJUSTED, "5.00"));
		assertThat(entries(t, t.financeToken(), teacher, "PAY_OUT").getBody().data())
			.extracting(EntryView::event, line -> line.amount().toPlainString())
			.containsExactly(org.assertj.core.groups.Tuple.tuple(EntryView.SETTLEMENT_ADJUSTED, "-3.00"));

		// Paid on Teacher Payouts: the balance drops by the share automatically and one read-only
		// "paid" line appears in Pay Out; nothing is written to the wallet.
		assertThat(markPaid(t, march, "BANK-1").getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(balance(t, teacher)).isEqualByComparingTo("12.00");
		List<EntryView> payOuts = entries(t, t.financeToken(), teacher, "PAY_OUT").getBody().data();
		assertThat(payOuts).filteredOn(line -> EntryView.SETTLEMENT_PAID.equals(line.event()))
			.singleElement()
			.satisfies(line -> {
				assertThat(line.settlementId()).isEqualTo(march);
				assertThat(line.amount()).isEqualByComparingTo("-40.00");
				assertThat(line.payoutReference()).isEqualTo("BANK-1");
				assertThat(line.balanceBefore()).isNull();
			});
		// The earned line is still listed exactly once in Pay In.
		assertThat(entries(t, t.financeToken(), teacher, "PAY_IN").getBody().data())
			.filteredOn(line -> EntryView.SETTLEMENT_EARNED.equals(line.event()))
			.hasSize(1);

		// Paying the bonus adjustment lowers the balance by it and lists it as paid too.
		assertThat(markPaid(t, bonus, null).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(balance(t, teacher)).isEqualByComparingTo("7.00");
		assertThat(entries(t, t.financeToken(), teacher, "PAY_OUT").getBody().data())
			.filteredOn(line -> EntryView.SETTLEMENT_PAID.equals(line.event()))
			.extracting(line -> line.amount().toPlainString())
			.containsExactlyInAnyOrder("-40.00", "-5.00");

		// Wallet-only rows and the ledger are untouched; a wallet Pay In/Out still lists as a wallet line.
		assertThat(entryRows(t, null)).isEqualTo(walletRowsBefore);
		assertThat(count("ledger_entry", t)).isEqualTo(ledgerBefore);
		payOut(t, t.financeToken(), teacher, "2.00", "Cash advance", null, null, null, key());
		assertThat(entries(t, t.financeToken(), teacher, "PAY_OUT").getBody().data().get(0))
			.satisfies(line -> {
				assertThat(line.source()).isEqualTo("WALLET");
				assertThat(line.event()).isEqualTo("PAY_OUT");
				assertThat(line.balanceAfter()).isEqualByComparingTo("5.00");
			});
		// Activity reads stay tenant-scoped like the rest of the wallet.
		FinanceTenant other = seedFinanceTenant("wal-act-b");
		assertThat(entries(other, other.financeToken(), teacher, "PAY_IN").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unpaidSettlementsCountTowardTheBalanceAndAWalletPayoutBlocksADoubleMarkPaid() {
		FinanceTenant t = seedFinanceTenant("wal-stl");
		UUID teacher = t.teacher().getId();
		UUID settlementId = seedMarchSettlement(t, "wal-stl");
		long ledgerBefore = count("ledger_entry", t);

		WalletView wallet = create(t, t.financeToken(), teacher, "0").getBody().data();
		assertThat(wallet.unpaidSettlements()).isEqualByComparingTo("40.00");
		assertThat(wallet.balance()).isEqualByComparingTo("40.00");

		// Paid from the wallet, then marking the settlement paid would double-pay: 409.
		assertThat(payOut(t, t.financeToken(), teacher, "40.00", "March share", null, null, "40.00", key())
			.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements/{id}/mark-paid", settlementId), Map.of()), t.host(),
				t.financeToken())), SettlementDetail.class)
			.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// The settlement list still shows settlements only (no wallet entries).
		HttpResult<com.lms.common.api.PageResponse<SettlementView>> list = parsePage(perform(
				authenticated(get("/api/v1/finance/teacher-settlements"), t.host(), t.financeToken())),
				SettlementView.class);
		assertThat(list.getBody().data().content()).extracting(SettlementView::kind)
			.containsOnly(TeacherSettlementKind.REGULAR);
		// A wallet entry is never marked paid through the settlement endpoint.
		UUID payoutId = entries(t, t.financeToken(), teacher, "PAY_OUT").getBody().data().get(0).id();
		assertThat(parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements/{id}/mark-paid", payoutId), Map.of()), t.host(),
				t.financeToken())), SettlementDetail.class)
			.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(count("ledger_entry", t)).isEqualTo(ledgerBefore);
	}

	@Test
	void eachWalletActionNeedsItsOwnGrantAndUnauthorizedRolesAreRefused() {
		FinanceTenant t = seedFinanceTenant("wal-perm");
		UUID teacher = t.teacher().getId();
		String operator = delegatedToken(t, Role.OPERATOR);

		assertThat(getWallet(t, operator, teacher).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(create(t, operator, teacher, "10").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		// Teacher portal and content staff have no wallet access; the auditor is read-only.
		assertThat(getWallet(t, t.teacherToken(), teacher).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getWallet(t, t.contentToken(), teacher).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getWallet(t, t.auditorToken(), teacher).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(create(t, t.auditorToken(), teacher, "10").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		setGrants(t, "OPERATOR", true, "TEACHERS_VIEW_WALLET", "TEACHERS_WALLET_CREATE");
		assertThat(getWallet(t, operator, teacher).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(create(t, operator, teacher, "10").getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(payOut(t, operator, teacher, "1", "x", null, null, null, key()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		setGrants(t, "OPERATOR", true, "TEACHERS_WALLET_PAY_OUT");
		assertThat(payOut(t, operator, teacher, "1", "x", null, null, null, key()).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
		assertThat(payIn(t, operator, teacher, "1", "x", null, key()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(adjust(t, operator, teacher, "0", "9.00", "x", key()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		setGrants(t, "OPERATOR", true, "TEACHERS_WALLET_PAY_IN", "TEACHERS_WALLET_ADJUST");
		assertThat(payIn(t, operator, teacher, "1", "x", null, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(adjust(t, operator, teacher, "0", "10.00", "x", key()).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void crossTenantTeacherIdsAreNotFoundAndWriteNothing() {
		FinanceTenant a = seedFinanceTenant("wal-xa");
		FinanceTenant b = seedFinanceTenant("wal-xb");
		UUID teacherB = b.teacher().getId();
		create(b, b.financeToken(), teacherB, "100.00");
		long rowsB = entryRows(b, null);

		assertThat(getWallet(a, a.financeToken(), teacherB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(entries(a, a.financeToken(), teacherB, "PAY_OUT").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(create(a, a.adminToken(), teacherB, "0").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(payOut(a, a.adminToken(), teacherB, "1", "x", null, null, null, key()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(adjust(a, a.adminToken(), teacherB, "0", "100.00", "x", key()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(entryRows(b, null)).isEqualTo(rowsB);
		assertThat(walletRows(a)).isZero();
		// A non-teacher user of the same tenant is not a teacher.
		UUID financeUser = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'finance@example.test'", UUID.class,
				a.tenant().getId());
		assertThat(getWallet(a, a.financeToken(), financeUser).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private interface Call<T> {

		T run(int i) throws Exception;

	}

	private static <T> List<T> runConcurrently(int n, Call<T> call) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int index = i;
				Callable<T> task = () -> {
					start.await();
					return call.run(index);
				};
				futures.add(pool.submit(task));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** Teacher 1, rate 40 % from 2025-01-01, one 100.00 payment on 2025-03-05 -> March share 40.00. */
	private UUID seedMarchSettlement(FinanceTenant t, String prefix) {
		UUID teacher = t.teacher().getId();
		CourseResponse course = createPricedCourse(t, prefix + "-x", teacher, "100.00");
		backdateConfirmedEntry(confirmedPayment(t, course.id()), utc("2025-03-05T10:00:00Z"));
		Map<String, Object> rate = Map.of("teacherId", teacher, "sharePercent", new BigDecimal("40.00"),
				"effectiveFrom", "2025-01-01");
		perform(authenticated(json(post("/api/v1/finance/teacher-share-rates"), rate), t.host(), t.financeToken()));
		HttpResult<SettlementDetail> calc = parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements"),
						Map.of("teacherId", teacher, "periodStart", "2025-03-01", "periodEnd", "2025-03-31")),
				t.host(), t.financeToken())), SettlementDetail.class);
		assertThat(calc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return calc.getBody().data().settlement().id();
	}

	/** Appends a settlement adjustment; returns the new adjustment row's id. */
	private UUID settlementAdjust(FinanceTenant t, UUID settlementId, String amount, String reason) {
		HttpResult<SettlementDetail> result = parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements/{id}/adjustments", settlementId),
						Map.of("amount", new BigDecimal(amount), "reason", reason)),
				t.host(), t.financeToken())), SettlementDetail.class);
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return result.getBody()
			.data()
			.adjustments()
			.stream()
			.filter(a -> a.shareAmount().compareTo(new BigDecimal(amount)) == 0)
			.findFirst()
			.orElseThrow()
			.id();
	}

	private HttpResult<SettlementDetail> markPaid(FinanceTenant t, UUID settlementId, String reference) {
		Map<String, Object> body = new HashMap<>();
		body.put("payoutReference", reference);
		return parseSingle(perform(authenticated(
				json(post("/api/v1/finance/teacher-settlements/{id}/mark-paid", settlementId), body), t.host(),
				t.financeToken())), SettlementDetail.class);
	}

	private BigDecimal balance(FinanceTenant t, UUID teacherId) {
		return getWallet(t, t.financeToken(), teacherId).getBody().data().balance();
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

	private String base(UUID teacherId) {
		return "/api/v1/finance/teachers/" + teacherId + "/wallet";
	}

	private HttpResult<WalletView> getWallet(FinanceTenant t, String token, UUID teacherId) {
		return parseSingle(perform(authenticated(get(base(teacherId)), t.host(), token)), WalletView.class);
	}

	private HttpResult<List<EntryView>> entries(FinanceTenant t, String token, UUID teacherId, String kind) {
		return parseList(perform(authenticated(get(base(teacherId) + "/entries").param("kind", kind), t.host(),
				token)), EntryView.class);
	}

	private HttpResult<WalletView> create(FinanceTenant t, String token, UUID teacherId, String opening) {
		return parseSingle(perform(authenticated(
				json(post(base(teacherId)), Map.of("openingBalance", new BigDecimal(opening))), t.host(), token)),
				WalletView.class);
	}

	private HttpResult<PostResult> payOut(FinanceTenant t, String token, UUID teacherId, String amount,
			String description, UUID accountId, UUID categoryId, String expected, String key) {
		return posting(t, token, base(teacherId) + "/pay-outs", amount, description, null, accountId, categoryId,
				expected, key);
	}

	private HttpResult<PostResult> payIn(FinanceTenant t, String token, UUID teacherId, String amount,
			String description, String currency, String key) {
		return posting(t, token, base(teacherId) + "/pay-ins", amount, description, currency, null, null, null, key);
	}

	private HttpResult<PostResult> payIn(FinanceTenant t, String token, UUID teacherId, String amount,
			String description, UUID categoryId, String key, UUID accountId, String expected) {
		return posting(t, token, base(teacherId) + "/pay-ins", amount, description, null, accountId, categoryId,
				expected, key);
	}

	private HttpResult<PostResult> posting(FinanceTenant t, String token, String path, String amount,
			String description, String currency, UUID accountId, UUID categoryId, String expected, String key) {
		Map<String, Object> body = new HashMap<>();
		body.put("amount", new BigDecimal(amount));
		body.put("description", description);
		body.put("currency", currency);
		body.put("accountId", accountId);
		body.put("categoryId", categoryId);
		body.put("expectedBalance", expected == null ? null : new BigDecimal(expected));
		MockHttpServletRequestBuilder builder = json(post(path), body);
		if (key != null) {
			builder.header("Idempotency-Key", key);
		}
		return parseSingle(perform(authenticated(builder, t.host(), token)), PostResult.class);
	}

	private HttpResult<PostResult> adjust(FinanceTenant t, String token, UUID teacherId, String target,
			String expected, String reason, String key) {
		Map<String, Object> body = new HashMap<>();
		body.put("targetBalance", new BigDecimal(target));
		body.put("expectedBalance", new BigDecimal(expected));
		body.put("reason", reason);
		return parseSingle(perform(authenticated(
				json(post(base(teacherId) + "/adjustments"), body).header("Idempotency-Key", key), t.host(), token)),
				PostResult.class);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private String delegatedToken(FinanceTenant t, Role role) {
		String email = role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8)
				+ "@example.test";
		seedTenantUser(t.tenant().getId(), email, RAW_PASSWORD, role);
		return loginAndGetToken(t.host(), email);
	}

	private void setGrants(FinanceTenant t, String role, boolean granted, String... capabilities) {
		var current = perform(authenticated(get("/api/v1/staff/permission-matrix"), t.host(), t.adminToken()));
		long version = objectMapper.readTree(rawContent(current)).path("data").path("version").asLong();
		List<Map<String, Object>> changes = java.util.Arrays.stream(capabilities)
			.map(capability -> Map.<String, Object>of("roleCode", role, "capabilityKey", capability, "granted",
					granted))
			.toList();
		var saved = perform(authenticated(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.put("/api/v1/staff/permission-matrix"), Map.of("expectedVersion", version, "changes", changes)), t.host(),
				t.adminToken()));
		assertThat(saved.getResponse().getStatus()).as(rawContent(saved)).isEqualTo(200);
	}

	private long count(String table, FinanceTenant t) {
		Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class,
				t.tenant().getId());
		return n == null ? 0 : n;
	}

	private long walletRows(FinanceTenant t) {
		return count("teacher_wallet", t);
	}

	private long entryRows(FinanceTenant t, String kind) {
		Long n = kind == null
				? jdbcTemplate.queryForObject("SELECT count(*) FROM teacher_settlement WHERE tenant_id = ? "
						+ "AND kind IN ('OPENING','PAY_IN','PAY_OUT','BALANCE_ADJ')", Long.class, t.tenant().getId())
				: jdbcTemplate.queryForObject(
						"SELECT count(*) FROM teacher_settlement WHERE tenant_id = ? AND kind = ?", Long.class,
						t.tenant().getId(), kind);
		return n == null ? 0 : n;
	}

	private long auditCount(String action, FinanceTenant t) {
		Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Long.class, t.tenant().getId(), action);
		return n == null ? 0 : n;
	}

}

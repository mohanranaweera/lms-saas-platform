package com.lms.financeexpensemanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import tools.jackson.databind.JsonNode;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.financeexpensemanagement.service.FinanceScheduleJob;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FIN-01 Finance account book (docs/specs/finance-module-klass-parity.md).
 * Proves the posting contract end to end: accounts, routing, adjustments,
 * student payments posting once, refunds following the original account,
 * unposted handling, manual postings and reversals, expense void routing, the
 * linked wallet payout, schedules, and tenant isolation on every surface.
 */
@Tag("cross-tenant")
class FinanceAccountBookIntegrationTest extends FinanceTestSupport {

	private static final String ACCOUNTS = "/api/v1/finance/accounts";

	private static final String TRANSACTIONS = "/api/v1/finance/transactions";

	private static final String SCHEDULES = "/api/v1/finance/schedules";

	@Autowired
	private FinanceScheduleJob scheduleJob;

	// ------------------------------------------------------------------
	// 1. Accounts, routing, adjustments, archive guards, permissions.
	// ------------------------------------------------------------------

	@Test
	void accountsRoutingAdjustmentsAndArchiveGuards() {
		FinanceTenant t = seedFinanceTenant("fin-acc");
		UUID cash = createAccount(t, t.adminToken(), "Cash", "0");
		UUID bank = createAccount(t, t.financeToken(), "Bank", "1000.50");
		assertThat(balance(t, cash)).isEqualByComparingTo("0.00");
		assertThat(balance(t, bank)).isEqualByComparingTo("1000.50");
		assertThat(status(createAccountRaw(t, t.adminToken(), "bank", "0"))).isEqualTo(409);
		assertThat(status(createAccountRaw(t, t.adminToken(), "Bad", "-1"))).isEqualTo(400);
		assertThat(status(createAccountRaw(t, t.adminToken(), "Bad", "1.001"))).isEqualTo(400);
		assertThat(status(createAccountRaw(t, t.auditorToken(), "Audit", "0"))).isEqualTo(403);
		assertThat(status(call(t.host(), t.auditorToken(), get(ACCOUNTS)))).isEqualTo(200);
		assertThat(status(call(t.host(), t.studentToken(), get(ACCOUNTS)))).isEqualTo(403);

		// Routing: Tenant Admin only by default; Bank/Online writes three real codes.
		assertThat(status(routing(t, t.financeToken(), cash, null, bank, null))).isEqualTo(403);
		assertThat(status(routing(t, t.adminToken(), cash, null, bank, null))).isEqualTo(200);
		JsonNode routing = data(call(t.host(), t.financeToken(), get(ACCOUNTS + "/routing")));
		assertThat(routing.path("cashAccountId").asString()).isEqualTo(cash.toString());
		assertThat(routing.path("bankOnlineAccountId").asString()).isEqualTo(bank.toString());
		assertThat(routing.path("methods").path("GATEWAY").asString()).isEqualTo(bank.toString());
		assertThat(routing.path("methods").path("MANUAL_SLIP").asString()).isEqualTo(bank.toString());
		assertThat(routing.path("methods").path("BANK_TRANSFER").asString()).isEqualTo(bank.toString());
		assertThat(routing.path("methods").has("CARD")).isFalse();
		assertThat(status(routing(t, t.adminToken(), UUID.randomUUID(), null, null, null))).isEqualTo(404);

		// Metadata edit.
		MvcResult renamed = send(t.host(), t.adminToken(), put(ACCOUNTS + "/" + bank),
				Map.of("name", "Main bank", "description", "Commercial Bank"));
		assertThat(status(renamed)).isEqualTo(200);
		assertThat(data(renamed).path("name").asString()).isEqualTo("Main bank");
		assertThat(data(renamed).path("balance").decimalValue()).isEqualByComparingTo("1000.50");

		// The balance pencil is a reasoned adjustment with a stale check.
		assertThat(status(adjust(t, t.financeToken(), bank, "1200.00", "999.00", "Count", key()))).isEqualTo(409);
		assertThat(status(adjust(t, t.financeToken(), bank, "1200.00", "1000.50", " ", key()))).isEqualTo(400);
		String adjustKey = key();
		MvcResult adjusted = adjust(t, t.financeToken(), bank, "1200.00", "1000.50", "Cash count", adjustKey);
		assertThat(status(adjusted)).as(rawContent(adjusted)).isEqualTo(201);
		assertThat(data(adjusted).path("type").asString()).isEqualTo("ADJUSTMENT");
		assertThat(data(adjusted).path("balanceBefore").decimalValue()).isEqualByComparingTo("1000.50");
		assertThat(data(adjusted).path("balanceAfter").decimalValue()).isEqualByComparingTo("1200.00");
		assertThat(balance(t, bank)).isEqualByComparingTo("1200.00");
		MvcResult replay = adjust(t, t.financeToken(), bank, "1200.00", "1000.50", "Cash count", adjustKey);
		assertThat(status(replay)).isEqualTo(201);
		assertThat(data(replay).path("id").asString()).isEqualTo(data(adjusted).path("id").asString());
		assertThat(txCount(t, "ADJUSTMENT")).isEqualTo(1);
		assertThat(status(adjust(t, t.auditorToken(), bank, "1.00", null, "No", key()))).isEqualTo(403);

		// Archive guards: mapped, non-zero balance; archived accounts leave the default list.
		assertThat(status(call(t.host(), t.adminToken(), post(ACCOUNTS + "/" + bank + "/archive")))).isEqualTo(409);
		UUID petty = createAccount(t, t.adminToken(), "Petty cash", "0");
		assertThat(status(call(t.host(), t.adminToken(), post(ACCOUNTS + "/" + petty + "/archive")))).isEqualTo(200);
		assertThat(accountIds(t, false)).doesNotContain(petty.toString());
		assertThat(accountIds(t, true)).contains(petty.toString());
		assertThat(status(routing(t, t.adminToken(), cash, null, null, null))).isEqualTo(200);
		assertThat(status(call(t.host(), t.adminToken(), post(ACCOUNTS + "/" + bank + "/archive")))).isEqualTo(409);
		assertThat(status(call(t.host(), t.adminToken(), post(ACCOUNTS + "/" + petty + "/unarchive")))).isEqualTo(200);

		// Reconciliation: opening + every posting = reported balance, for every account.
		for (UUID account : List.of(cash, bank, petty)) {
			assertThat(balance(t, account)).isEqualByComparingTo(sqlBalance(t, account));
		}
		assertThat(auditCount("finance_account.created", t)).isEqualTo(3);
		assertThat(auditCount("finance_routing.changed", t)).isEqualTo(2);
		assertThat(auditCount("finance_account.archived", t)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// 2. Student payments post once; refunds follow the original account.
	// ------------------------------------------------------------------

	@Test
	void studentPaymentsPostOnceAndRefundsFollowTheOriginalAccount() {
		FinanceTenant t = seedFinanceTenant("fin-pay");
		UUID bankA = createAccount(t, t.adminToken(), "Bank A", "0");
		UUID bankB = createAccount(t, t.adminToken(), "Bank B", "0");
		assertThat(status(routing(t, t.adminToken(), null, null, bankA, null))).isEqualTo(200);
		CourseResponse course = createPricedCourse(t, "fin-pay", t.teacher().getId(), "2500.00");

		// A pending payment (initiated, never confirmed) posts nothing.
		OrderResponse pending = createOrderOrFail(t.host(), t.studentToken(), course.id());
		initiatePaymentOrFail(t.host(), t.studentToken(), pending.id());
		assertThat(txCount(t, null)).isEqualTo(2); // two opening rows only

		// A multi-course composer order is one payment -> exactly one posting with the full amount.
		UUID multiPayment = payMultiCourseOrder(t, "fin-multi");
		BigDecimal multiAmount = jdbcTemplate.queryForObject(
				"SELECT amount FROM ledger_entry WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				BigDecimal.class, multiPayment);
		assertThat(multiAmount).isEqualByComparingTo("7000.00");
		List<Map<String, Object>> multiRows = jdbcTemplate.queryForList(
				"SELECT t.amount, t.status, t.to_account_id FROM finance_transaction t JOIN ledger_entry l "
						+ "ON l.id = t.source_id WHERE t.tenant_id = ? AND t.source_type = 'LEDGER_ENTRY' "
						+ "AND l.payment_id = ?",
				t.tenant().getId(), multiPayment);
		assertThat(multiRows).hasSize(1);
		assertThat((BigDecimal) multiRows.get(0).get("amount")).isEqualByComparingTo(multiAmount);
		assertThat(multiRows.get(0).get("status")).isEqualTo("POSTED");
		assertThat(multiRows.get(0).get("to_account_id").toString()).isEqualTo(bankA.toString());

		// A single-course gateway payment, then routing moves to Bank B, then a partial refund.
		UUID payment = confirmedPayment(t, course.id());
		assertThat(balance(t, bankA)).isEqualByComparingTo(multiAmount.add(new BigDecimal("2500.00")));
		assertThat(status(routing(t, t.adminToken(), null, null, bankB, null))).isEqualTo(200);
		refund(t, payment, "500.00");
		Map<String, Object> refundRow = jdbcTemplate.queryForMap(
				"SELECT t.type, t.status, t.from_account_id, t.reverses_transaction_id FROM finance_transaction t "
						+ "JOIN ledger_entry l ON l.id = t.source_id WHERE t.tenant_id = ? AND l.payment_id = ? "
						+ "AND l.entry_type = 'REFUND'",
				t.tenant().getId(), payment);
		assertThat(refundRow.get("type")).isEqualTo("REFUND");
		assertThat(refundRow.get("status")).isEqualTo("POSTED");
		assertThat(refundRow.get("from_account_id").toString()).isEqualTo(bankA.toString());
		assertThat(refundRow.get("reverses_transaction_id")).isNotNull();
		assertThat(balance(t, bankA)).isEqualByComparingTo(multiAmount.add(new BigDecimal("2000.00")));
		assertThat(balance(t, bankB)).isEqualByComparingTo("0.00");

		// Finance cannot reverse a payment posting; Payments owns that correction.
		String incomeId = refundRow.get("reverses_transaction_id").toString();
		MvcResult reverse = send(t.host(), t.adminToken(), post(TRANSACTIONS + "/" + incomeId + "/reverse"),
				Map.of("reason", "nope"));
		assertThat(status(reverse)).isEqualTo(409);
		JsonNode incomeView = data(call(t.host(), t.financeToken(), get(TRANSACTIONS + "/" + incomeId)));
		assertThat(incomeView.path("reversibleHere").asBoolean()).isFalse();
		assertThat(incomeView.path("correctionOwner").asString()).isEqualTo("PAYMENTS");

		// Populations: Student Income (ledger) and Budget (account book) agree here because everything
		// was routed; the response says where they can differ.
		JsonNode income = data(call(t.host(), t.financeToken(),
				get("/api/v1/finance/student-income").param("year", String.valueOf(LocalDate.now(ZoneOffset.UTC).getYear()))));
		assertThat(income.path("totals").path("grossIncome").decimalValue())
			.isEqualByComparingTo(multiAmount.add(new BigDecimal("2500.00")));
		assertThat(income.path("totals").path("refunds").decimalValue()).isEqualByComparingTo("500.00");
		assertThat(income.path("totals").path("paymentCount").asLong()).isEqualTo(2);
		assertThat(income.path("methods").path("onlineCount").asLong()).isEqualTo(2);
		assertThat(income.path("accountBook").path("postedStudentIncome").decimalValue())
			.isEqualByComparingTo(multiAmount.add(new BigDecimal("2000.00")));
		assertThat(income.path("accountBook").path("unpostedCount").asLong()).isZero();
		assertThat(income.path("wallet").isNull()).isTrue();
		assertThat(income.path("months")).hasSize(12);
		JsonNode budget = data(call(t.host(), t.financeToken(), get("/api/v1/finance/budget")));
		assertThat(budget.path("incomeForPeriod").decimalValue())
			.isEqualByComparingTo(multiAmount.add(new BigDecimal("2000.00")));
		assertThat(budget.path("totalBalance").decimalValue())
			.isEqualByComparingTo(multiAmount.add(new BigDecimal("2000.00")));
		assertThat(budget.path("incomeByCategory").get(0).path("label").asString()).isEqualTo("Student payments");
		assertThat(status(call(t.host(), t.studentToken(), get("/api/v1/finance/budget")))).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// 3. No routing: payments wait as UNPOSTED and are posted once.
	// ------------------------------------------------------------------

	@Test
	void unroutedPaymentsWaitAsUnpostedAndPostOnce() {
		FinanceTenant t = seedFinanceTenant("fin-unp");
		UUID bank = createAccount(t, t.adminToken(), "Bank", "10");
		CourseResponse course = createPricedCourse(t, "fin-unp", t.teacher().getId(), "1000.00");
		long ledgerBefore = count("ledger_entry", t);

		UUID payment = confirmedPayment(t, course.id());
		assertThat(count("ledger_entry", t)).isEqualTo(ledgerBefore + 1);
		JsonNode unposted = data(call(t.host(), t.financeToken(), get(TRANSACTIONS + "/unposted")));
		assertThat(unposted).hasSize(1);
		String incomeId = unposted.get(0).path("id").asString();
		assertThat(unposted.get(0).path("status").asString()).isEqualTo("UNPOSTED");
		assertThat(balance(t, bank)).isEqualByComparingTo("10.00");
		assertThat(data(call(t.host(), t.financeToken(), get("/api/v1/finance/budget"))).path("incomeForPeriod")
			.decimalValue()).isEqualByComparingTo("0.00");

		// A refund before routing waits with its payment.
		refund(t, payment, "100.00");
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS + "/unposted")))).hasSize(2);

		// Posting the payment posts the waiting refund to the same account; a second post is refused.
		assertThat(status(send(t.host(), t.auditorToken(), post(TRANSACTIONS + "/" + incomeId + "/post"),
				Map.of("accountId", bank)))).isEqualTo(403);
		MvcResult posted = send(t.host(), t.financeToken(), post(TRANSACTIONS + "/" + incomeId + "/post"),
				Map.of("accountId", bank));
		assertThat(status(posted)).as(rawContent(posted)).isEqualTo(200);
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS + "/unposted")))).isEmpty();
		assertThat(balance(t, bank)).isEqualByComparingTo("910.00");
		assertThat(status(send(t.host(), t.financeToken(), post(TRANSACTIONS + "/" + incomeId + "/post"),
				Map.of("accountId", bank)))).isEqualTo(409);
		assertThat(txCount(t, "INCOME")).isEqualTo(1);
		assertThat(txCount(t, "REFUND")).isEqualTo(1);

		// Opt-in reconcile is idempotent and Tenant Admin only: wipe the postings (test-only) and rebuild.
		jdbcTemplate.update("DELETE FROM finance_transaction WHERE tenant_id = ? AND source_type = 'LEDGER_ENTRY'",
				t.tenant().getId());
		assertThat(status(send(t.host(), t.financeToken(), post(TRANSACTIONS + "/reconcile"),
				Map.of("from", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString())))).isEqualTo(403);
		assertThat(status(routing(t, t.adminToken(), null, null, bank, null))).isEqualTo(200);
		MvcResult reconciled = send(t.host(), t.adminToken(), post(TRANSACTIONS + "/reconcile"),
				Map.of("from", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()));
		assertThat(status(reconciled)).as(rawContent(reconciled)).isEqualTo(200);
		assertThat(data(reconciled).path("posted").asInt()).isEqualTo(2);
		assertThat(balance(t, bank)).isEqualByComparingTo("910.00");
		MvcResult again = send(t.host(), t.adminToken(), post(TRANSACTIONS + "/reconcile"),
				Map.of("from", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()));
		assertThat(data(again).path("posted").asInt()).isZero();
		assertThat(data(again).path("skipped").asInt()).isEqualTo(2);
		assertThat(txCount(t, "INCOME")).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// 4. Manual income / expense / transfer, reversals, expense void routing.
	// ------------------------------------------------------------------

	@Test
	void manualPostingsTransfersReversalsAndExpenseVoidStayConsistent() {
		FinanceTenant t = seedFinanceTenant("fin-man");
		UUID cash = createAccount(t, t.adminToken(), "Cash", "100");
		UUID bank = createAccount(t, t.adminToken(), "Bank", "50");
		assertThat(status(routing(t, t.adminToken(), cash, null, bank, null))).isEqualTo(200);
		UUID rent = createCategoryOrFail(t.host(), t.adminToken(), "Rent").id();
		String today = LocalDate.now(ZoneOffset.UTC).toString();

		// Income: category required, positive amount, not in the future, idempotent on the key.
		String k1 = key();
		MvcResult income = record(t, t.financeToken(), txBody("INCOME", "200.00", today, "Hall rental", rent, cash,
				null, null), k1);
		assertThat(status(income)).as(rawContent(income)).isEqualTo(201);
		String incomeId = data(income).path("id").asString();
		MvcResult replay = record(t, t.financeToken(), txBody("INCOME", "200.00", today, "Hall rental", rent, cash,
				null, null), k1);
		assertThat(status(replay)).isEqualTo(200);
		assertThat(data(replay).path("id").asString()).isEqualTo(incomeId);
		assertThat(status(record(t, t.financeToken(), txBody("INCOME", "200.00", today, "No category", null, cash,
				null, null), null))).isEqualTo(400);
		assertThat(status(record(t, t.financeToken(), txBody("INCOME", "0", today, "Zero", rent, cash, null, null),
				null))).isEqualTo(400);
		assertThat(status(record(t, t.financeToken(),
				txBody("INCOME", "5.00", LocalDate.now(ZoneOffset.UTC).plusDays(2).toString(), "Future", rent, cash,
						null, null),
				null))).isEqualTo(400);
		assertThat(status(record(t, t.auditorToken(), txBody("INCOME", "5.00", today, "Auditor", rent, cash, null,
				null), null))).isEqualTo(403);

		// Expense: an expense row (method from the account's routing slot) plus its posting.
		long expensesBefore = count("expense", t);
		MvcResult expense = record(t, t.financeToken(), txBody("EXPENSE", "30.00", today, "Cleaning", rent, cash,
				null, null), key());
		assertThat(status(expense)).as(rawContent(expense)).isEqualTo(201);
		assertThat(data(expense).path("sourceType").asString()).isEqualTo("EXPENSE");
		assertThat(count("expense", t)).isEqualTo(expensesBefore + 1);
		assertThat(jdbcTemplate.queryForObject("SELECT method FROM expense WHERE id = ?", String.class,
				UUID.fromString(data(expense).path("sourceId").asString()))).isEqualTo("CASH");

		// Transfer: both balances move, period income/expenses do not; invalid transfers post nothing.
		JsonNode before = data(call(t.host(), t.financeToken(), get("/api/v1/finance/budget")));
		MvcResult transfer = record(t, t.financeToken(), txBody("TRANSFER", "25.00", today, "To bank", null, null,
				cash, bank), key());
		assertThat(status(transfer)).as(rawContent(transfer)).isEqualTo(201);
		assertThat(balance(t, cash)).isEqualByComparingTo("245.00");
		assertThat(balance(t, bank)).isEqualByComparingTo("75.00");
		JsonNode after = data(call(t.host(), t.financeToken(), get("/api/v1/finance/budget")));
		assertThat(after.path("incomeForPeriod").decimalValue())
			.isEqualByComparingTo(before.path("incomeForPeriod").decimalValue());
		assertThat(after.path("expensesForPeriod").decimalValue())
			.isEqualByComparingTo(before.path("expensesForPeriod").decimalValue());
		assertThat(after.path("netActivity").decimalValue()).isEqualByComparingTo("170.00");
		assertThat(after.path("transfersForPeriod").decimalValue()).isEqualByComparingTo("25.00");
		assertThat(after.path("totalBalance").decimalValue()).isEqualByComparingTo("320.00");
		long rows = txCount(t, null);
		assertThat(status(record(t, t.financeToken(), txBody("TRANSFER", "1.00", today, "Same", null, null, cash,
				cash), null))).isEqualTo(400);
		UUID old = createAccount(t, t.adminToken(), "Old", "0");
		assertThat(status(call(t.host(), t.adminToken(), post(ACCOUNTS + "/" + old + "/archive")))).isEqualTo(200);
		assertThat(status(record(t, t.financeToken(), txBody("TRANSFER", "1.00", today, "Archived", null, null, cash,
				old), null))).isEqualTo(400);
		assertThat(status(record(t, t.financeToken(), txBody("TRANSFER", "1.00", today, "Foreign", null, null, cash,
				UUID.randomUUID()), null))).isEqualTo(404);
		assertThat(txCount(t, null)).isEqualTo(rows + 1); // only the "Old" opening row

		// Reversal: a mirror row; the original becomes REVERSED; never twice; never an opening.
		MvcResult reversed = send(t.host(), t.financeToken(), post(TRANSACTIONS + "/" + incomeId + "/reverse"),
				Map.of("reason", "Entered twice"));
		assertThat(status(reversed)).as(rawContent(reversed)).isEqualTo(201);
		assertThat(data(reversed).path("type").asString()).isEqualTo("REVERSAL");
		assertThat(data(reversed).path("fromAccountId").asString()).isEqualTo(cash.toString());
		assertThat(balance(t, cash)).isEqualByComparingTo("45.00");
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS + "/" + incomeId))).path("status")
			.asString()).isEqualTo("REVERSED");
		assertThat(status(send(t.host(), t.financeToken(), post(TRANSACTIONS + "/" + incomeId + "/reverse"),
				Map.of("reason", "Again")))).isEqualTo(409);
		String openingId = jdbcTemplate.queryForObject(
				"SELECT id FROM finance_transaction WHERE tenant_id = ? AND type = 'OPENING' AND to_account_id = ?",
				String.class, t.tenant().getId(), cash);
		assertThat(status(send(t.host(), t.financeToken(), post(TRANSACTIONS + "/" + openingId + "/reverse"),
				Map.of("reason", "No")))).isEqualTo(409);
		assertThat(status(send(t.host(), t.auditorToken(), post(TRANSACTIONS + "/" + openingId + "/reverse"),
				Map.of("reason", "No")))).isEqualTo(403);

		// Expense corrections go through the owning workflow in both directions.
		String expenseTxId = data(expense).path("id").asString();
		UUID expenseId = UUID.fromString(data(expense).path("sourceId").asString());
		MvcResult voidViaFinance = send(t.host(), t.financeToken(), post(TRANSACTIONS + "/" + expenseTxId + "/reverse"),
				Map.of("reason", "Duplicate receipt"));
		assertThat(status(voidViaFinance)).as(rawContent(voidViaFinance)).isEqualTo(201);
		assertThat(data(voidViaFinance).path("status").asString()).isEqualTo("REVERSED");
		assertThat(jdbcTemplate.queryForObject("SELECT voided_at IS NOT NULL FROM expense WHERE id = ?", Boolean.class,
				expenseId)).isTrue();
		assertThat(balance(t, cash)).isEqualByComparingTo("75.00");
		MvcResult expense2 = record(t, t.financeToken(), txBody("EXPENSE", "10.00", today, "Stationery", rent, cash,
				null, null), key());
		UUID expense2Id = UUID.fromString(data(expense2).path("sourceId").asString());
		MvcResult voided = send(t.host(), t.adminToken(), post("/api/v1/finance/expenses/" + expense2Id + "/void"),
				Map.of("reason", "Returned"));
		assertThat(status(voided)).as(rawContent(voided)).isEqualTo(200);
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS + "/" + data(expense2).path("id").asString())))
			.path("status").asString()).isEqualTo("REVERSED");
		assertThat(balance(t, cash)).isEqualByComparingTo("75.00");
		assertThat(txCount(t, "REVERSAL")).isEqualTo(3);

		// Metadata edit and server-side filters.
		String transferId = data(transfer).path("id").asString();
		assertThat(status(send(t.host(), t.financeToken(), patch(TRANSACTIONS + "/" + transferId),
				Map.of("note", "Moved to bank")))).isEqualTo(200);
		assertThat(status(send(t.host(), t.financeToken(), patch(TRANSACTIONS + "/" + transferId),
				Map.of("categoryId", rent)))).isEqualTo(400);
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS).param("type", "TRANSFER"))).path("content"))
			.hasSize(1);
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS).param("search", "moved to"))).path("content"))
			.hasSize(1);
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS).param("accountId", bank.toString())))
			.path("content")).hasSize(2);
		assertThat(data(call(t.host(), t.financeToken(), get(TRANSACTIONS).param("categoryId", rent.toString())))
			.path("totalElements").asLong()).isEqualTo(6);
		for (UUID account : List.of(cash, bank, old)) {
			assertThat(balance(t, account)).isEqualByComparingTo(sqlBalance(t, account));
		}
		assertThat(auditCount("finance_transaction.reversed", t)).isEqualTo(3);
	}

	// ------------------------------------------------------------------
	// 5. Linked wallet payout: one Finance outflow, student income untouched.
	// ------------------------------------------------------------------

	@Test
	void linkedWalletPayoutPostsOnceAndLeavesStudentIncomeUntouched() {
		FinanceTenant t = seedFinanceTenant("fin-wal");
		UUID cash = createAccount(t, t.adminToken(), "Cash", "500");
		UUID teacher = t.teacher().getId();
		String walletBase = "/api/v1/finance/teachers/" + teacher + "/wallet";
		assertThat(status(send(t.host(), t.financeToken(), post(walletBase), Map.of("openingBalance", "100.00"))))
			.isEqualTo(201);
		long ledgerBefore = count("ledger_entry", t);
		long walletRowsBefore = walletEntryRows(t);
		String year = String.valueOf(LocalDate.now(ZoneOffset.UTC).getYear());
		BigDecimal incomeBefore = data(call(t.host(), t.financeToken(), get("/api/v1/finance/student-income")
			.param("year", year))).path("totals").path("grossIncome").decimalValue();

		String k = key();
		MvcResult payout = walletPost(t, t.financeToken(), walletBase + "/pay-outs", "40.00", "March payout", cash,
				null, k);
		assertThat(status(payout)).as(rawContent(payout)).isEqualTo(201);
		assertThat(data(payout).path("wallet").path("balance").decimalValue()).isEqualByComparingTo("60.00");
		assertThat(txCount(t, "EXPENSE")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT source_type FROM finance_transaction WHERE tenant_id = ? AND type = 'EXPENSE'", String.class,
				t.tenant().getId())).isEqualTo("WALLET_ENTRY");
		assertThat(balance(t, cash)).isEqualByComparingTo("460.00");

		// Replay of the same request posts nothing new (the wallet answers 200 for a replay).
		assertThat(status(walletPost(t, t.financeToken(), walletBase + "/pay-outs", "40.00", "March payout", cash,
				null, k))).isEqualTo(200);
		assertThat(txCount(t, "EXPENSE")).isEqualTo(1);
		assertThat(walletEntryRows(t)).isEqualTo(walletRowsBefore + 1);

		// A bad account rolls the whole wallet entry back; a linked Pay In is still refused.
		assertThat(status(walletPost(t, t.financeToken(), walletBase + "/pay-outs", "1.00", "Bad account",
				UUID.randomUUID(), null, key()))).isEqualTo(404);
		assertThat(walletEntryRows(t)).isEqualTo(walletRowsBefore + 1);
		assertThat(status(walletPost(t, t.financeToken(), walletBase + "/pay-ins", "1.00", "Linked in", cash, null,
				key()))).isEqualTo(400);
		assertThat(txCount(t, null)).isEqualTo(2); // opening + the one payout

		// Never student income, never the ledger; the Finance side cannot reverse it.
		BigDecimal incomeAfter = data(call(t.host(), t.financeToken(), get("/api/v1/finance/student-income")
			.param("year", year))).path("totals").path("grossIncome").decimalValue();
		assertThat(incomeAfter).isEqualByComparingTo(incomeBefore);
		assertThat(count("ledger_entry", t)).isEqualTo(ledgerBefore);
		String payoutTxId = jdbcTemplate.queryForObject(
				"SELECT id FROM finance_transaction WHERE tenant_id = ? AND type = 'EXPENSE'", String.class,
				t.tenant().getId());
		assertThat(status(send(t.host(), t.adminToken(), post(TRANSACTIONS + "/" + payoutTxId + "/reverse"),
				Map.of("reason", "No")))).isEqualTo(409);
		JsonNode budget = data(call(t.host(), t.financeToken(), get("/api/v1/finance/budget")));
		assertThat(budget.path("expensesForPeriod").decimalValue()).isEqualByComparingTo("40.00");
		assertThat(budget.path("expensesByCategory").get(0).path("label").asString()).isEqualTo("Teacher payouts");
	}

	// ------------------------------------------------------------------
	// 6. Schedules: materialise, post once, cancel, fail and retry.
	// ------------------------------------------------------------------

	@Test
	void schedulesMaterializePostOnceCancelAndRetry() {
		FinanceTenant t = seedFinanceTenant("fin-sch");
		UUID cash = createAccount(t, t.adminToken(), "Cash", "1000");
		UUID bank = createAccount(t, t.adminToken(), "Bank", "0");
		UUID rent = createCategoryOrFail(t.host(), t.adminToken(), "Rent").id();
		LocalDate today = LocalDate.now(ZoneOffset.UTC);

		// Read-only roles may look but not schedule.
		assertThat(status(send(t.host(), t.auditorToken(), post(SCHEDULES),
				scheduleBody("Audit", "EXPENSE", "1.00", cash, null, rent, "ONE_TIME", today, null)))).isEqualTo(403);
		assertThat(status(call(t.host(), t.auditorToken(), get(SCHEDULES)))).isEqualTo(200);

		// One-time expense due today: one occurrence however often the list is refreshed.
		MvcResult oneTime = send(t.host(), t.financeToken(), post(SCHEDULES),
				scheduleBody("Hall rent", "EXPENSE", "100.00", cash, null, rent, "ONE_TIME", today, null));
		assertThat(status(oneTime)).as(rawContent(oneTime)).isEqualTo(201);
		String oneTimeId = data(oneTime).path("id").asString();
		assertThat(data(oneTime).path("openOccurrences").asLong()).isEqualTo(1);
		assertThat(data(oneTime).path("nextDueDate").asString()).isEqualTo(today.toString());
		call(t.host(), t.financeToken(), get(SCHEDULES));
		call(t.host(), t.financeToken(), get(SCHEDULES + "/due"));
		assertThat(occurrenceRows(t, oneTimeId)).isEqualTo(1);
		JsonNode due = data(call(t.host(), t.financeToken(), get(SCHEDULES + "/due")));
		String occurrenceId = due.get(0).path("id").asString();
		long expensesBefore = count("expense", t);
		assertThat(status(call(t.host(), t.auditorToken(), post(SCHEDULES + "/occurrences/" + occurrenceId + "/post"))))
			.isEqualTo(403);
		MvcResult posted = call(t.host(), t.financeToken(), post(SCHEDULES + "/occurrences/" + occurrenceId + "/post"));
		assertThat(status(posted)).as(rawContent(posted)).isEqualTo(201);
		assertThat(data(posted).path("status").asString()).isEqualTo("POSTED");
		assertThat(data(posted).path("transactionId").isNull()).isFalse();
		assertThat(balance(t, cash)).isEqualByComparingTo("900.00");
		assertThat(count("expense", t)).isEqualTo(expensesBefore + 1);
		assertThat(status(call(t.host(), t.financeToken(), post(SCHEDULES + "/occurrences/" + occurrenceId + "/post"))))
			.isEqualTo(409);
		assertThat(data(call(t.host(), t.financeToken(), get(SCHEDULES + "/" + oneTimeId))).path("status").asString())
			.isEqualTo("COMPLETED");

		// Monthly transfer on today's day of month: due now; a 31st schedule reports its skipped months.
		MvcResult monthly = send(t.host(), t.financeToken(), post(SCHEDULES), scheduleBody("Sweep", "TRANSFER",
				"10.00", cash, bank, null, "MONTHLY", null, today.getDayOfMonth()));
		assertThat(status(monthly)).as(rawContent(monthly)).isEqualTo(201);
		String monthlyId = data(monthly).path("id").asString();
		assertThat(data(monthly).path("openOccurrences").asLong()).isEqualTo(1);
		String sweepOccurrence = data(call(t.host(), t.financeToken(), get(SCHEDULES + "/" + monthlyId + "/occurrences")))
			.get(0).path("id").asString();
		assertThat(status(call(t.host(), t.financeToken(), post(SCHEDULES + "/occurrences/" + sweepOccurrence + "/post"))))
			.isEqualTo(201);
		assertThat(balance(t, bank)).isEqualByComparingTo("10.00");
		MvcResult thirtyFirst = send(t.host(), t.financeToken(), post(SCHEDULES),
				scheduleBody("Salaries", "EXPENSE", "5.00", cash, null, rent, "MONTHLY", null, 31));
		assertThat(status(thirtyFirst)).isEqualTo(201);
		assertThat(data(thirtyFirst).path("skippedMonths")).isNotEmpty();
		assertThat(status(send(t.host(), t.financeToken(), post(SCHEDULES),
				scheduleBody("Bad", "EXPENSE", "5.00", cash, null, rent, "MONTHLY", null, 32)))).isEqualTo(400);
		assertThat(status(send(t.host(), t.financeToken(), post(SCHEDULES),
				scheduleBody("Bad", "TRANSFER", "5.00", cash, cash, null, "MONTHLY", null, 1)))).isEqualTo(400);

		// Failure + retry: the category is archived after scheduling.
		UUID temp = createCategoryOrFail(t.host(), t.adminToken(), "Temporary").id();
		MvcResult failing = send(t.host(), t.financeToken(), post(SCHEDULES),
				scheduleBody("Temp cost", "EXPENSE", "7.00", cash, null, temp, "ONE_TIME", today, null));
		String failingOccurrence = data(call(t.host(), t.financeToken(),
				get(SCHEDULES + "/" + data(failing).path("id").asString() + "/occurrences"))).get(0).path("id").asString();
		assertThat(status(archiveCategory(t.host(), t.adminToken(), temp).getStatusCode().value())).isEqualTo(200);
		MvcResult failed = call(t.host(), t.financeToken(), post(SCHEDULES + "/occurrences/" + failingOccurrence + "/post"));
		assertThat(status(failed)).as(rawContent(failed)).isEqualTo(409);
		assertThat(data(failed).path("status").asString()).isEqualTo("FAILED");
		JsonNode failedView = data(call(t.host(), t.financeToken(),
				get(SCHEDULES + "/" + data(failing).path("id").asString() + "/occurrences"))).get(0);
		assertThat(failedView.path("status").asString()).isEqualTo("FAILED");
		assertThat(failedView.path("attempts").asInt()).isEqualTo(1);
		assertThat(failedView.path("lastError").asString()).isNotBlank();
		assertThat(balance(t, cash)).isEqualByComparingTo("890.00");
		assertThat(status(call(t.host(), t.adminToken(),
				post("/api/v1/finance/expense-categories/" + temp + "/unarchive")))).isEqualTo(200);
		MvcResult retried = call(t.host(), t.financeToken(), post(SCHEDULES + "/occurrences/" + failingOccurrence + "/post"));
		assertThat(status(retried)).as(rawContent(retried)).isEqualTo(201);
		assertThat(data(retried).path("attempts").asInt()).isEqualTo(2);
		assertThat(balance(t, cash)).isEqualByComparingTo("883.00");

		// Cancelling keeps completed occurrences and skips open ones.
		MvcResult cancelled = call(t.host(), t.financeToken(), post(SCHEDULES + "/" + monthlyId + "/cancel"));
		assertThat(status(cancelled)).isEqualTo(200);
		assertThat(data(cancelled).path("status").asString()).isEqualTo("CANCELLED");
		assertThat(data(cancelled).path("postedOccurrences").asLong()).isEqualTo(1);
		assertThat(balance(t, bank)).isEqualByComparingTo("10.00");
		assertThat(status(call(t.host(), t.financeToken(), post(SCHEDULES + "/occurrences/" + sweepOccurrence + "/post"))))
			.isEqualTo(409);
		assertThat(status(call(t.host(), t.financeToken(), post(SCHEDULES + "/" + monthlyId + "/cancel")))).isEqualTo(409);

		// A future one-time schedule has no occurrence yet and an account used by it cannot be archived.
		MvcResult future = send(t.host(), t.financeToken(), post(SCHEDULES), scheduleBody("Deposit", "INCOME",
				"20.00", null, bank, rent, "ONE_TIME", today.plusDays(10), null));
		assertThat(status(future)).isEqualTo(201);
		assertThat(data(future).path("openOccurrences").asLong()).isZero();
		assertThat(data(future).path("nextDueDate").asString()).isEqualTo(today.plusDays(10).toString());
		assertThat(status(record(t, t.financeToken(), txBody("TRANSFER", "10.00", today.toString(), "Empty bank",
				null, null, bank, cash), key()))).isEqualTo(201);
		assertThat(status(call(t.host(), t.adminToken(), post(ACCOUNTS + "/" + bank + "/archive")))).isEqualTo(409);

		// The job materialises per tenant without a request context and is idempotent: a missing due
		// occurrence (test-only delete) is recreated once; a second run creates nothing.
		MvcResult missed = send(t.host(), t.financeToken(), post(SCHEDULES), scheduleBody("Missed", "INCOME",
				"3.00", null, bank, rent, "ONE_TIME", today, null));
		String missedId = data(missed).path("id").asString();
		jdbcTemplate.update("DELETE FROM finance_scheduled_occurrence WHERE schedule_id = ?", UUID.fromString(missedId));
		long allBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM finance_scheduled_occurrence", Long.class);
		scheduleJob.materializeAll();
		assertThat(occurrenceRows(t, missedId)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM finance_scheduled_occurrence", Long.class))
			.isEqualTo(allBefore + 1);
		scheduleJob.materializeAll();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM finance_scheduled_occurrence", Long.class))
			.isEqualTo(allBefore + 1);
		assertThat(auditCount("finance_schedule.occurrence_posted", t)).isEqualTo(3);
		assertThat(auditCount("finance_schedule.occurrence_failed", t)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// 7. Tenant isolation on every surface.
	// ------------------------------------------------------------------

	@Test
	void tenantsAreIsolatedAcrossEveryFinanceSurface() {
		FinanceTenant a = seedFinanceTenant("fin-ta");
		FinanceTenant b = seedFinanceTenant("fin-tb");
		UUID aCash = createAccount(a, a.adminToken(), "Cash", "300");
		UUID aRent = createCategoryOrFail(a.host(), a.adminToken(), "Rent").id();
		String today = LocalDate.now(ZoneOffset.UTC).toString();
		String aIncome = data(record(a, a.financeToken(), txBody("INCOME", "40.00", today, "A income", aRent, aCash,
				null, null), key())).path("id").asString();
		String aSchedule = data(send(a.host(), a.financeToken(), post(SCHEDULES), scheduleBody("A rent", "EXPENSE",
				"5.00", aCash, null, aRent, "ONE_TIME", LocalDate.now(ZoneOffset.UTC), null))).path("id").asString();
		String aOccurrence = data(call(a.host(), a.financeToken(), get(SCHEDULES + "/" + aSchedule + "/occurrences")))
			.get(0).path("id").asString();
		assertThat(status(routing(a, a.adminToken(), aCash, null, null, null))).isEqualTo(200);

		// Reads: nothing of A is visible or addressable from B.
		assertThat(data(call(b.host(), b.adminToken(), get(ACCOUNTS)))).isEmpty();
		assertThat(status(call(b.host(), b.adminToken(), get(ACCOUNTS + "/" + aCash)))).isEqualTo(404);
		assertThat(data(call(b.host(), b.adminToken(), get(TRANSACTIONS))).path("content")).isEmpty();
		assertThat(status(call(b.host(), b.adminToken(), get(TRANSACTIONS + "/" + aIncome)))).isEqualTo(404);
		assertThat(data(call(b.host(), b.adminToken(), get(SCHEDULES))).path("content")).isEmpty();
		assertThat(status(call(b.host(), b.adminToken(), get(SCHEDULES + "/" + aSchedule)))).isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), get(SCHEDULES + "/" + aSchedule + "/occurrences"))))
			.isEqualTo(404);
		assertThat(data(call(b.host(), b.adminToken(), get(SCHEDULES + "/due")))).isEmpty();
		assertThat(data(call(b.host(), b.adminToken(), get(TRANSACTIONS + "/unposted")))).isEmpty();
		JsonNode bRouting = data(call(b.host(), b.adminToken(), get(ACCOUNTS + "/routing")));
		assertThat(bRouting.path("cashAccountId").isNull()).isTrue();
		assertThat(data(call(b.host(), b.adminToken(), get("/api/v1/finance/budget"))).path("totalBalance")
			.decimalValue()).isEqualByComparingTo("0.00");
		assertThat(data(call(b.host(), b.adminToken(), get("/api/v1/finance/budget"))).path("accountBalances"))
			.isEmpty();

		// Writes: every mutation addressed at A's rows is a 404 from B and changes nothing.
		long aRows = txCount(a, null);
		assertThat(status(send(b.host(), b.adminToken(), put(ACCOUNTS + "/" + aCash), Map.of("name", "Hijack"))))
			.isEqualTo(404);
		assertThat(status(adjust(b, b.adminToken(), aCash, "0.00", null, "Hijack", key()))).isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), post(ACCOUNTS + "/" + aCash + "/archive")))).isEqualTo(404);
		assertThat(status(routing(b, b.adminToken(), aCash, null, null, null))).isEqualTo(404);
		assertThat(status(record(b, b.adminToken(), txBody("INCOME", "1.00", today, "Hijack", aRent, aCash, null,
				null), key()))).isEqualTo(404);
		assertThat(status(send(b.host(), b.adminToken(), post(TRANSACTIONS + "/" + aIncome + "/reverse"),
				Map.of("reason", "Hijack")))).isEqualTo(404);
		assertThat(status(send(b.host(), b.adminToken(), patch(TRANSACTIONS + "/" + aIncome),
				Map.of("note", "Hijack")))).isEqualTo(404);
		assertThat(status(send(b.host(), b.adminToken(), post(TRANSACTIONS + "/" + aIncome + "/post"),
				Map.of("accountId", aCash)))).isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), post(SCHEDULES + "/" + aSchedule + "/cancel")))).isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), post(SCHEDULES + "/occurrences/" + aOccurrence + "/post"))))
			.isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), post(SCHEDULES + "/occurrences/" + aOccurrence + "/skip"))))
			.isEqualTo(404);
		assertThat(status(send(b.host(), b.adminToken(), post(SCHEDULES), scheduleBody("B rent", "EXPENSE", "5.00",
				aCash, null, aRent, "ONE_TIME", LocalDate.now(ZoneOffset.UTC), null)))).isEqualTo(404);
		assertThat(txCount(a, null)).isEqualTo(aRows);
		assertThat(balance(a, aCash)).isEqualByComparingTo("340.00");
		assertThat(data(call(a.host(), a.financeToken(), get(ACCOUNTS + "/" + aCash))).path("name").asString())
			.isEqualTo("Cash");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM finance_payment_routing WHERE tenant_id = ?", Long.class, b.tenant().getId()))
			.isZero();
	}

	// ------------------------------------------------------------------
	// Fixtures / helpers.
	// ------------------------------------------------------------------

	/** Two monthly courses, one covered lesson each, one composer order for both, paid online. */
	private UUID payMultiCourseOrder(FinanceTenant t, String prefix) {
		UUID courseA = monthlyCourse(t, prefix + "-a");
		UUID courseB = monthlyCourse(t, prefix + "-b");
		assertThat(status(call(t.host(), t.studentToken(), post("/api/v1/enrollments/my/courses/{courseId}", courseA))))
			.isEqualTo(200);
		assertThat(status(call(t.host(), t.studentToken(), post("/api/v1/enrollments/my/courses/{courseId}", courseB))))
			.isEqualTo(200);
		List<Map<String, Object>> items = List.of(item(courseA), item(courseB));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", courseA);
		body.put("items", items);
		MvcResult quote = send(t.host(), t.studentToken(), post("/api/v1/checkout/quote"), body);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		body.put("quoteToken", data(quote).path("quoteToken").asString());
		MvcResult order = send(t.host(), t.studentToken(), post("/api/v1/checkout/orders"), body);
		assertThat(status(order)).as(rawContent(order)).isEqualTo(201);
		UUID orderId = UUID.fromString(data(order).path("id").asString());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(t.host(), t.studentToken(), orderId);
		assertThat(sendPaymentWebhook(initiation.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		return initiation.paymentId();
	}

	private UUID monthlyCourse(FinanceTenant t, String prefix) {
		String slug = uniqueSlug(prefix);
		CourseResponse course = createCourseOrFail(t.host(), t.adminToken(),
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal("3500.00"), null, null, CourseStatus.PUBLIC, t.teacher().getId(), null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null));
		UUID moduleId = createModuleOrFail(t.host(), t.adminToken(), course.id(), "Module", 1).id();
		UUID lessonId = createLessonOrFail(t.host(), t.adminToken(), course.id(), moduleId, "October class", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", 2026, 10, lessonId);
		return course.id();
	}

	private static Map<String, Object> item(UUID courseId) {
		Map<String, Object> item = new HashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", 2026);
		item.put("month", 10);
		return item;
	}

	private UUID createAccount(FinanceTenant t, String token, String name, String opening) {
		MvcResult result = createAccountRaw(t, token, name, opening);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(data(result).path("id").asString());
	}

	private MvcResult createAccountRaw(FinanceTenant t, String token, String name, String opening) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("description", name + " account");
		body.put("openingBalance", new BigDecimal(opening));
		return send(t.host(), token, post(ACCOUNTS), body);
	}

	private MvcResult routing(FinanceTenant t, String token, UUID cash, UUID card, UUID bankOnline, UUID other) {
		Map<String, Object> body = new HashMap<>();
		body.put("cashAccountId", cash);
		body.put("cardAccountId", card);
		body.put("bankOnlineAccountId", bankOnline);
		body.put("otherAccountId", other);
		return send(t.host(), token, put(ACCOUNTS + "/routing"), body);
	}

	private MvcResult adjust(FinanceTenant t, String token, UUID account, String target, String expected,
			String reason, String key) {
		Map<String, Object> body = new HashMap<>();
		body.put("targetBalance", new BigDecimal(target));
		if (expected != null) {
			body.put("expectedBalance", new BigDecimal(expected));
		}
		body.put("reason", reason);
		MockHttpServletRequestBuilder builder = post(ACCOUNTS + "/" + account + "/adjustments");
		if (key != null) {
			builder.header("Idempotency-Key", key);
		}
		return send(t.host(), token, builder, body);
	}

	private static Map<String, Object> txBody(String type, String amount, String date, String note, UUID categoryId,
			UUID accountId, UUID fromAccountId, UUID toAccountId) {
		Map<String, Object> body = new HashMap<>();
		body.put("type", type);
		body.put("amount", new BigDecimal(amount));
		body.put("transactionDate", date);
		body.put("note", note);
		body.put("categoryId", categoryId);
		body.put("accountId", accountId);
		body.put("fromAccountId", fromAccountId);
		body.put("toAccountId", toAccountId);
		return body;
	}

	private MvcResult record(FinanceTenant t, String token, Map<String, Object> body, String key) {
		MockHttpServletRequestBuilder builder = post(TRANSACTIONS);
		if (key != null) {
			builder.header("Idempotency-Key", key);
		}
		return send(t.host(), token, builder, body);
	}

	private static Map<String, Object> scheduleBody(String name, String type, String amount, UUID from, UUID to,
			UUID categoryId, String scheduleType, LocalDate scheduledDate, Integer dayOfMonth) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("type", type);
		body.put("amount", new BigDecimal(amount));
		body.put("fromAccountId", from);
		body.put("toAccountId", to);
		body.put("categoryId", categoryId);
		body.put("note", name + " note");
		body.put("scheduleType", scheduleType);
		body.put("scheduledDate", scheduledDate == null ? null : scheduledDate.toString());
		body.put("dayOfMonth", dayOfMonth);
		return body;
	}

	private MvcResult walletPost(FinanceTenant t, String token, String path, String amount, String description,
			UUID accountId, UUID categoryId, String key) {
		Map<String, Object> body = new HashMap<>();
		body.put("amount", new BigDecimal(amount));
		body.put("description", description);
		body.put("accountId", accountId);
		body.put("categoryId", categoryId);
		MockHttpServletRequestBuilder builder = post(path);
		if (key != null) {
			builder.header("Idempotency-Key", key);
		}
		return send(t.host(), token, builder, body);
	}

	private BigDecimal balance(FinanceTenant t, UUID accountId) {
		MvcResult result = call(t.host(), t.adminToken(), get(ACCOUNTS + "/" + accountId));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result).path("balance").decimalValue();
	}

	private List<String> accountIds(FinanceTenant t, boolean includeArchived) {
		List<String> ids = new ArrayList<>();
		data(call(t.host(), t.adminToken(), get(ACCOUNTS).param("includeArchived", String.valueOf(includeArchived))))
			.forEach(node -> ids.add(node.path("id").asString()));
		return ids;
	}

	private BigDecimal sqlBalance(FinanceTenant t, UUID accountId) {
		BigDecimal value = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(CASE WHEN to_account_id = ? THEN amount ELSE 0 END), 0) "
						+ "- COALESCE(SUM(CASE WHEN from_account_id = ? THEN amount ELSE 0 END), 0) "
						+ "FROM finance_transaction WHERE tenant_id = ? AND status <> 'UNPOSTED'",
				BigDecimal.class, accountId, accountId, t.tenant().getId());
		return value == null ? BigDecimal.ZERO : value;
	}

	private long txCount(FinanceTenant t, String type) {
		Long n = type == null
				? jdbcTemplate.queryForObject("SELECT count(*) FROM finance_transaction WHERE tenant_id = ?", Long.class,
						t.tenant().getId())
				: jdbcTemplate.queryForObject(
						"SELECT count(*) FROM finance_transaction WHERE tenant_id = ? AND type = ?", Long.class,
						t.tenant().getId(), type);
		return n == null ? 0 : n;
	}

	private long occurrenceRows(FinanceTenant t, String scheduleId) {
		Long n = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM finance_scheduled_occurrence WHERE tenant_id = ? AND schedule_id = ?", Long.class,
				t.tenant().getId(), UUID.fromString(scheduleId));
		return n == null ? 0 : n;
	}

	private long walletEntryRows(FinanceTenant t) {
		Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM teacher_settlement WHERE tenant_id = ? "
				+ "AND kind IN ('OPENING','PAY_IN','PAY_OUT','BALANCE_ADJ')", Long.class, t.tenant().getId());
		return n == null ? 0 : n;
	}

	private long count(String table, FinanceTenant t) {
		Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class,
				t.tenant().getId());
		return n == null ? 0 : n;
	}

	private long auditCount(String action, FinanceTenant t) {
		Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Long.class, t.tenant().getId(), action);
		return n == null ? 0 : n;
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		return call(host, token, builder);
	}

	private JsonNode data(MvcResult result) {
		return objectMapper.readTree(rawContent(result)).path("data");
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private static int status(int value) {
		return value;
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}

package com.lms.financeexpensemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.financeexpensemanagement.domain.Expense;
import com.lms.financeexpensemanagement.repository.ExpenseRepository;
import com.lms.financeexpensemanagement.repository.FinanceTransactionRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi.ShareRate;
import com.lms.ledgersettlementmanagement.api.TeacherWalletApi;
import com.lms.ledgersettlementmanagement.api.TeacherWalletApi.WalletSummary;
import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.PaymentReportApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIN-01 Student Income tab. <b>Population: the payment ledger</b> (confirmed
 * cash payments - GATEWAY, MANUAL_SLIP, MANUAL_RECEIPT - and their refunds,
 * dated by the ledger entry's tenant-local date). FREE / STAFF_GRANTED
 * confirmations move no money and are excluded; pending / rejected payments
 * have no ledger entry and never count. Teacher share and institute
 * commission follow the RPT-01 rule (settled snapshot, else the share rate
 * effective on the entry date; facility fees + postage are institute-only
 * service fees). This is a live view; the Teacher Income report is the
 * authoritative statement.
 */
@Service
public class StudentIncomeService {

	static final int MAX_ENTRIES = 50_000;

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final TeacherIncomeLedgerApi ledgerApi;

	private final PaymentReportApi paymentReportApi;

	private final CourseLookupApi courseLookupApi;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final TeacherWalletApi teacherWalletApi;

	private final ExpenseRepository expenseRepository;

	private final FinanceTransactionRepository transactionRepository;

	private final FinancePeriodResolver periodResolver;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	public StudentIncomeService(TeacherIncomeLedgerApi ledgerApi, PaymentReportApi paymentReportApi,
			CourseLookupApi courseLookupApi, TeacherLookupApi teacherLookupApi,
			UserProvisioningApi userProvisioningApi, TeacherWalletApi teacherWalletApi,
			ExpenseRepository expenseRepository, FinanceTransactionRepository transactionRepository,
			FinancePeriodResolver periodResolver, PermissionCheckService permissionCheckService,
			TenantContext tenantContext) {
		this.ledgerApi = ledgerApi;
		this.paymentReportApi = paymentReportApi;
		this.courseLookupApi = courseLookupApi;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.teacherWalletApi = teacherWalletApi;
		this.expenseRepository = expenseRepository;
		this.transactionRepository = transactionRepository;
		this.periodResolver = periodResolver;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public StudentIncomeView year(Integer year) {
		permissionCheckService.requireCapability(Capability.FINANCE_VIEW_INCOME_SUMMARY);
		ZoneId zone = periodResolver.tenantZone();
		int y = year == null ? LocalDate.now(zone).getYear() : year;
		if (y < 2000 || y > 2100) {
			throw new FieldValidationException("Invalid year",
					List.of(new FieldError("year", "must be between 2000 and 2100")));
		}
		LocalDate from = LocalDate.of(y, 1, 1);
		LocalDate to = LocalDate.of(y, 12, 31);
		Computation c = compute(from, to, zone);

		// Monthly series (12 buckets, always present).
		Map<Integer, Expense> ignored = Map.of();
		Map<Integer, BigDecimal> expensesByMonth = new HashMap<>();
		BigDecimal expensesTotal = ZERO;
		for (Expense expense : expenseRepository.findActiveDatedBetween(from, to)) {
			int m = expense.getExpenseDate().getMonthValue();
			expensesByMonth.merge(m, expense.getAmount(), BigDecimal::add);
			expensesTotal = expensesTotal.add(expense.getAmount());
		}
		List<MonthPoint> months = new ArrayList<>();
		for (int m = 1; m <= 12; m++) {
			MonthAccumulator acc = c.months.getOrDefault(m, new MonthAccumulator());
			months.add(new MonthPoint(YearMonth.of(y, m).toString(),
					Month.of(m).getDisplayName(TextStyle.SHORT, Locale.ENGLISH), acc.gross, acc.refunds,
					acc.gross.subtract(acc.refunds), expensesByMonth.getOrDefault(m, ZERO), acc.commission,
					acc.teacherShare, acc.paymentCount));
		}

		// Wallet: only the signed-in user's own wallet, and only with the wallet view capability.
		WalletSummary wallet = null;
		if (permissionCheckService.hasCapability(Capability.TEACHERS_VIEW_WALLET)
				&& AuthenticatedPrincipalHolder.isSet()) {
			wallet = teacherWalletApi.findWalletOwnedBy(AuthenticatedPrincipalHolder.get().userId(), 5).orElse(null);
		}

		// Account-book comparison for the population note.
		Object[] posted = transactionRepository.postedStudentIncome(tenantContext.getTenantId(), from, to).get(0);
		BigDecimal postedIncome = ((BigDecimal) posted[0]).setScale(2, RoundingMode.HALF_UP);
		long unposted = transactionRepository.countUnposted(tenantContext.getTenantId());

		BigDecimal net = c.gross.subtract(c.refunds);
		BigDecimal average = c.paymentCount == 0 ? ZERO
				: c.gross.divide(BigDecimal.valueOf(c.paymentCount), 2, RoundingMode.HALF_UP);
		Totals totals = new Totals(c.gross, c.refunds, net, c.teacherShare, c.commission, c.serviceFees,
				c.unratedRevenue, expensesTotal, net.subtract(expensesTotal), c.paymentCount, average);
		List<String> notes = List.of(
				"Collected income counts confirmed cash payments on the payment ledger (online gateway, approved bank slips and staff-recorded receipts) by the date they were confirmed, net of refunds in the month they were recorded. Pending and rejected payments are never included; free and staff-granted enrolments move no money.",
				"Institute commission = income - teacher share, using the settled share where a settlement exists and otherwise the teacher's share rate effective on the payment date (facility fees and postage are institute-only service fees). The Teacher Income report is the authoritative statement.",
				"The payment-method breakdown and the monthly chart use the same ledger population as the cards. Recent and pending payments come from the Payments dashboard. The Budget dashboard reads the Finance account book instead, which only includes payments already routed to an account.");
		return new StudentIncomeView(y, postedTenantCurrency(c), zone.getId(), Instant.now(), totals, months,
				c.methods(), c.teacherRows(), wallet, new BookComparison(postedIncome, unposted), notes);
	}

	@Transactional(readOnly = true)
	public MonthView month(String yearMonth) {
		permissionCheckService.requireCapability(Capability.FINANCE_VIEW_INCOME_SUMMARY);
		YearMonth ym;
		try {
			ym = YearMonth.parse(yearMonth);
		}
		catch (DateTimeParseException e) {
			throw new FieldValidationException("Invalid month",
					List.of(new FieldError("month", "must be formatted yyyy-MM")));
		}
		ZoneId zone = periodResolver.tenantZone();
		Computation c = compute(ym.atDay(1), ym.atEndOfMonth(), zone);
		BigDecimal net = c.gross.subtract(c.refunds);
		BigDecimal average = c.paymentCount == 0 ? ZERO
				: c.gross.divide(BigDecimal.valueOf(c.paymentCount), 2, RoundingMode.HALF_UP);
		BigDecimal expenses = expenseRepository.findActiveDatedBetween(ym.atDay(1), ym.atEndOfMonth())
			.stream()
			.map(Expense::getAmount)
			.reduce(ZERO, BigDecimal::add);
		Totals totals = new Totals(c.gross, c.refunds, net, c.teacherShare, c.commission, c.serviceFees,
				c.unratedRevenue, expenses, net.subtract(expenses), c.paymentCount, average);
		return new MonthView(ym.toString(), postedTenantCurrency(c), zone.getId(), totals, c.methods(),
				c.teacherRows(), c.payments);
	}

	private String postedTenantCurrency(Computation c) {
		return c.currency != null ? c.currency : periodResolver.tenantCurrency();
	}

	// ------------------------------------------------------------------
	// Core computation (shared by year and month).
	// ------------------------------------------------------------------

	private Computation compute(LocalDate from, LocalDate to, ZoneId zone) {
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		List<ReportLedgerEntry> entries;
		try {
			entries = ledgerApi.findEntriesCreatedIn(List.of(new InstantRange(start, end)), MAX_ENTRIES);
		}
		catch (ReportScopeTooLargeException e) {
			throw new ConflictException(
					"Too many payments in this period for the live view - use the Teacher Income report");
		}
		List<ReportLedgerEntry> cash = entries.stream().filter(StudentIncomeService::isCash).toList();

		Set<UUID> courseIds = new HashSet<>();
		Set<UUID> teacherIds = new HashSet<>();
		Set<UUID> receiptPaymentIds = new HashSet<>();
		for (ReportLedgerEntry entry : cash) {
			for (ReportCourseAllocation allocation : entry.courses()) {
				courseIds.add(allocation.courseId());
				if (allocation.settlement() != null) {
					teacherIds.add(allocation.settlement().teacherId());
				}
			}
			if (entry.method() == PaymentMethod.MANUAL_RECEIPT && entry.paymentId() != null) {
				receiptPaymentIds.add(entry.paymentId());
			}
		}
		Map<UUID, UUID> teacherByCourse = courseIds.isEmpty() ? Map.of()
				: courseLookupApi.getTeacherIdsByCourseId(courseIds);
		teacherIds.addAll(teacherByCourse.values());
		Map<UUID, List<ShareRate>> rates = new HashMap<>();
		for (ShareRate rate : ledgerApi.findShareRates(teacherIds)) {
			rates.computeIfAbsent(rate.teacherId(), k -> new ArrayList<>()).add(rate);
		}
		rates.values().forEach(list -> list.sort(Comparator.comparing(ShareRate::effectiveFrom)));
		Map<UUID, String> receiptMethods = receiptPaymentIds.isEmpty() ? Map.of()
				: paymentReportApi.findManualReceiptsByPaymentIds(receiptPaymentIds)
					.stream()
					.collect(Collectors.toMap(ManualReceiptFact::paymentId, ManualReceiptFact::paymentMethod,
							(a, b) -> a));

		Computation c = new Computation();
		for (ReportLedgerEntry entry : cash) {
			LocalDate date = entry.createdAt().atZone(zone).toLocalDate();
			int month = date.getMonthValue();
			MonthAccumulator acc = c.months.computeIfAbsent(month, m -> new MonthAccumulator());
			BigDecimal amount = entry.amount();
			if (c.currency == null) {
				c.currency = entry.currency();
			}
			if (entry.refund()) {
				c.refunds = c.refunds.add(amount.abs());
				acc.refunds = acc.refunds.add(amount.abs());
			}
			else {
				c.gross = c.gross.add(amount);
				acc.gross = acc.gross.add(amount);
				c.paymentCount++;
				acc.paymentCount++;
				String code = methodCode(entry, receiptMethods);
				MethodAccumulator m = c.methods.computeIfAbsent(code, MethodAccumulator::new);
				m.count++;
				m.total = m.total.add(amount);
			}
			BigDecimal allocated = ZERO;
			for (ReportCourseAllocation allocation : entry.courses()) {
				BigDecimal share = allocation.amount();
				allocated = allocated.add(share);
				UUID teacherId;
				BigDecimal percent;
				if (allocation.settlement() != null) {
					teacherId = allocation.settlement().teacherId();
					percent = allocation.settlement().sharePercent();
				}
				else {
					teacherId = teacherByCourse.get(allocation.courseId());
					percent = teacherId == null ? null : rateOn(rates.get(teacherId), date);
				}
				TeacherMonthAccumulator row = c.teacherMonths
					.computeIfAbsent(new TeacherMonthKey(teacherId, YearMonth.from(date)), k -> new TeacherMonthAccumulator());
				row.income = row.income.add(share);
				if (percent == null) {
					c.unratedRevenue = c.unratedRevenue.add(share);
					row.rateMissing = true;
					continue;
				}
				BigDecimal teacherShare = share.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
				BigDecimal commission = share.subtract(teacherShare);
				row.teacherShare = row.teacherShare.add(teacherShare);
				row.commission = row.commission.add(commission);
				c.teacherShare = c.teacherShare.add(teacherShare);
				c.commission = c.commission.add(commission);
				acc.teacherShare = acc.teacherShare.add(teacherShare);
				acc.commission = acc.commission.add(commission);
			}
			c.serviceFees = c.serviceFees.add(amount.subtract(allocated));
			c.payments.add(new PaymentRow(entry.entryId(), entry.orderId(), entry.paymentId(), entry.refund(),
					amount, entry.currency(), entry.method() == null ? null : entry.method().name(),
					methodCode(entry, receiptMethods), date, entry.createdAt(), entry.courses().size()));
		}
		c.payments.sort(Comparator.comparing(PaymentRow::recordedAt).reversed());
		c.teacherNames = teacherNames(c.teacherMonths.keySet().stream().map(TeacherMonthKey::teacherId).toList());
		return c;
	}

	private static boolean isCash(ReportLedgerEntry entry) {
		return entry.amount() != null && entry.amount().signum() != 0
				&& (entry.method() == PaymentMethod.GATEWAY || entry.method() == PaymentMethod.MANUAL_SLIP
						|| entry.method() == PaymentMethod.MANUAL_RECEIPT);
	}

	private static String methodCode(ReportLedgerEntry entry, Map<UUID, String> receiptMethods) {
		if (entry.method() == PaymentMethod.MANUAL_RECEIPT) {
			String code = receiptMethods.get(entry.paymentId());
			return code == null ? "MANUAL_RECEIPT" : code;
		}
		return entry.method() == null ? "UNKNOWN" : entry.method().name();
	}

	private static BigDecimal rateOn(List<ShareRate> teacherRates, LocalDate date) {
		if (teacherRates == null) {
			return null;
		}
		BigDecimal percent = null;
		for (ShareRate rate : teacherRates) {
			if (!rate.effectiveFrom().isAfter(date)) {
				percent = rate.sharePercent();
			}
		}
		return percent;
	}

	private Map<UUID, String> teacherNames(List<UUID> teacherIds) {
		Set<UUID> ids = teacherIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>();
		for (TeacherLookupApi.TeacherSummary teacher : teacherLookupApi.listTeachers()) {
			if (ids.contains(teacher.userId())) {
				names.put(teacher.userId(), teacher.name() != null ? teacher.name() : teacher.email());
			}
		}
		Set<UUID> missing = ids.stream().filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
		if (!missing.isEmpty()) {
			for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(missing)) {
				names.put(summary.userId(), summary.email());
			}
		}
		return names;
	}

	// ------------------------------------------------------------------
	// Accumulators and views.
	// ------------------------------------------------------------------

	private record TeacherMonthKey(UUID teacherId, YearMonth month) {

	}

	private static final class MonthAccumulator {

		BigDecimal gross = ZERO;

		BigDecimal refunds = ZERO;

		BigDecimal commission = ZERO;

		BigDecimal teacherShare = ZERO;

		long paymentCount;

	}

	private static final class MethodAccumulator {

		final String code;

		long count;

		BigDecimal total = ZERO;

		MethodAccumulator(String code) {
			this.code = code;
		}

	}

	private static final class TeacherMonthAccumulator {

		BigDecimal income = ZERO;

		BigDecimal commission = ZERO;

		BigDecimal teacherShare = ZERO;

		boolean rateMissing;

	}

	private static final class Computation {

		String currency;

		BigDecimal gross = ZERO;

		BigDecimal refunds = ZERO;

		BigDecimal teacherShare = ZERO;

		BigDecimal commission = ZERO;

		BigDecimal serviceFees = ZERO;

		BigDecimal unratedRevenue = ZERO;

		long paymentCount;

		final Map<Integer, MonthAccumulator> months = new HashMap<>();

		final Map<String, MethodAccumulator> methods = new LinkedHashMap<>();

		final Map<TeacherMonthKey, TeacherMonthAccumulator> teacherMonths = new LinkedHashMap<>();

		final List<PaymentRow> payments = new ArrayList<>();

		Map<UUID, String> teacherNames = Map.of();

		MethodBreakdown methods() {
			List<MethodTotal> byCode = methods.values()
				.stream()
				.map(m -> new MethodTotal(m.code, label(m.code), m.code.equals("GATEWAY"), m.count, m.total))
				.sorted(Comparator.comparing(MethodTotal::total).reversed())
				.toList();
			long onlineCount = 0;
			BigDecimal onlineTotal = ZERO;
			long manualCount = 0;
			BigDecimal manualTotal = ZERO;
			for (MethodTotal m : byCode) {
				if (m.online()) {
					onlineCount += m.count();
					onlineTotal = onlineTotal.add(m.total());
				}
				else {
					manualCount += m.count();
					manualTotal = manualTotal.add(m.total());
				}
			}
			BigDecimal all = onlineTotal.add(manualTotal);
			BigDecimal onlineShare = all.signum() == 0 ? ZERO
					: onlineTotal.multiply(HUNDRED).divide(all, 1, RoundingMode.HALF_UP);
			return new MethodBreakdown(onlineCount, onlineTotal, manualCount, manualTotal, onlineShare, byCode);
		}

		List<TeacherMonthRow> teacherRows() {
			return teacherMonths.entrySet()
				.stream()
				.map(e -> new TeacherMonthRow(e.getKey().teacherId(),
						e.getKey().teacherId() == null ? "Unassigned course"
								: teacherNames.getOrDefault(e.getKey().teacherId(), "Teacher"),
						e.getKey().month().toString(),
						e.getKey().month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
						e.getValue().income, e.getValue().rateMissing ? null : e.getValue().commission,
						e.getValue().rateMissing ? null : e.getValue().teacherShare, e.getValue().rateMissing))
				.sorted(Comparator.comparing(TeacherMonthRow::month).thenComparing(TeacherMonthRow::teacherName))
				.toList();
		}

		private static String label(String code) {
			return switch (code) {
				case "GATEWAY" -> "Online (gateway)";
				case "MANUAL_SLIP" -> "Bank slip";
				case "CASH" -> "Cash";
				case "CARD" -> "Card";
				case "BANK_TRANSFER" -> "Bank transfer";
				case "MANUAL_RECEIPT" -> "Staff receipt";
				default -> code;
			};
		}

	}

	public record StudentIncomeView(int year, String currency, String timezone, Instant generatedAt, Totals totals,
			List<MonthPoint> months, MethodBreakdown methods, List<TeacherMonthRow> teacherRows, WalletSummary wallet,
			BookComparison accountBook, List<String> notes) {

	}

	public record MonthView(String month, String currency, String timezone, Totals totals, MethodBreakdown methods,
			List<TeacherMonthRow> teacherRows, List<PaymentRow> payments) {

	}

	public record Totals(BigDecimal grossIncome, BigDecimal refunds, BigDecimal netIncome, BigDecimal teacherShare,
			BigDecimal instituteCommission, BigDecimal serviceFees, BigDecimal unratedRevenue, BigDecimal expenses,
			BigDecimal netAfterExpenses, long paymentCount, BigDecimal averagePayment) {

	}

	public record MonthPoint(String month, String label, BigDecimal income, BigDecimal refunds, BigDecimal netIncome,
			BigDecimal expenses, BigDecimal commission, BigDecimal teacherShare, long paymentCount) {

	}

	public record MethodBreakdown(long onlineCount, BigDecimal onlineTotal, long manualCount, BigDecimal manualTotal,
			BigDecimal onlinePercent, List<MethodTotal> byCode) {

	}

	public record MethodTotal(String code, String label, boolean online, long count, BigDecimal total) {

	}

	public record TeacherMonthRow(UUID teacherId, String teacherName, String month, String monthLabel,
			BigDecimal income, BigDecimal forInstitute, BigDecimal forTeacher, boolean rateMissing) {

	}

	public record PaymentRow(UUID ledgerEntryId, UUID orderId, UUID paymentId, boolean refund, BigDecimal amount,
			String currency, String method, String methodCode, LocalDate date, Instant recordedAt, int courseCount) {

	}

	/** How much of this population has reached the account book (Budget dashboard). */
	public record BookComparison(BigDecimal postedStudentIncome, long unpostedCount) {

	}

}

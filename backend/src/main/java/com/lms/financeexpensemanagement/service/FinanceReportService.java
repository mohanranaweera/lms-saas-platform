package com.lms.financeexpensemanagement.service;

import com.lms.common.money.PlatformCurrency;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.financeexpensemanagement.domain.Expense;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.financeexpensemanagement.repository.ExpenseRepository;
import com.lms.financeexpensemanagement.service.FinancePeriodResolver.DateRange;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueApi;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 7 (PAR-23-02/04) - finance reports.
 *
 * <p><b>Income is derived exclusively from the authoritative ledger</b>
 * ({@link LedgerRevenueApi}, i.e. {@code ledger_entry} {@code
 * PAYMENT_CONFIRMED}/{@code REFUND} rows). This domain stores no income rows
 * and offers no way to enter or edit income, so there is no second,
 * manually-editable source of payment truth. Expenses come from this domain's
 * own append-only {@code expense} table, voided rows excluded.
 *
 * <p>Sign conventions in every report: {@code gross} = sum of confirmed
 * payments; {@code refunds} = sum of refund magnitudes (a positive number);
 * {@code net = gross - refunds}. A refund counts in the period it was
 * recorded (wave-07-plan.md §10 judgment call 6). Teacher revenue attributes
 * each course's revenue to that course's CURRENT teacher (judgment call 4).
 */
@Service
@Transactional(readOnly = true)
public class FinanceReportService {

	private final LedgerRevenueApi ledgerRevenueApi;

	private final ExpenseRepository expenseRepository;

	private final ExpenseCategoryRepository categoryRepository;

	private final CourseLookupApi courseLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	private final FinancePeriodResolver periodResolver;

	public FinanceReportService(LedgerRevenueApi ledgerRevenueApi, ExpenseRepository expenseRepository,
			ExpenseCategoryRepository categoryRepository, CourseLookupApi courseLookupApi,
			UserProvisioningApi userProvisioningApi, PermissionCheckService permissionCheckService,
			FinancePeriodResolver periodResolver) {
		this.ledgerRevenueApi = ledgerRevenueApi;
		this.expenseRepository = expenseRepository;
		this.categoryRepository = categoryRepository;
		this.courseLookupApi = courseLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
		this.periodResolver = periodResolver;
	}

	public FinanceSummary summary(LocalDate from, LocalDate to) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		DateRange range = periodResolver.resolve(from, to);
		IncomeSummary income = incomeOf(ledgerEntries(range));
		ExpenseSummary expenses = expenseSummaryOf(expenseRepository.findActiveDatedBetween(range.from(),
				range.to()));
		return new FinanceSummary(range.from(), range.to(), range.zone().getId(), PlatformCurrency.DEFAULT_CURRENCY,
				income, expenses, income.net().subtract(expenses.total()));
	}

	public CourseRevenueReport courseRevenue(LocalDate from, LocalDate to) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		DateRange range = periodResolver.resolve(from, to);
		List<CourseRevenueRow> rows = courseRows(ledgerEntries(range));
		return new CourseRevenueReport(range.from(), range.to(), PlatformCurrency.DEFAULT_CURRENCY, rows,
				incomeOfRows(rows));
	}

	public TeacherRevenueReport teacherRevenue(LocalDate from, LocalDate to) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		DateRange range = periodResolver.resolve(from, to);
		List<CourseRevenueRow> courseRows = courseRows(ledgerEntries(range));

		Map<UUID, List<CourseRevenueRow>> byTeacher = new LinkedHashMap<>();
		for (CourseRevenueRow row : courseRows) {
			byTeacher.computeIfAbsent(row.teacherId(), ignored -> new ArrayList<>()).add(row);
		}
		Set<UUID> teacherIds = byTeacher.keySet().stream().filter(Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, String> emails = userProvisioningApi.findTenantUserSummaries(teacherIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));

		List<TeacherRevenueRow> rows = byTeacher.entrySet().stream().map(entry -> {
			List<CourseRevenueRow> courses = entry.getValue();
			BigDecimal gross = sum(courses.stream().map(CourseRevenueRow::gross).toList());
			BigDecimal refunds = sum(courses.stream().map(CourseRevenueRow::refunds).toList());
			int payments = courses.stream().mapToInt(CourseRevenueRow::paymentCount).sum();
			int refundCount = courses.stream().mapToInt(CourseRevenueRow::refundCount).sum();
			return new TeacherRevenueRow(entry.getKey(), emails.get(entry.getKey()), courses.size(), gross, refunds,
					gross.subtract(refunds), payments, refundCount);
		}).sorted(Comparator.comparing(TeacherRevenueRow::net).reversed()).toList();
		return new TeacherRevenueReport(range.from(), range.to(), PlatformCurrency.DEFAULT_CURRENCY, rows,
				incomeOfRows(courseRows));
	}

	/** Calendar-month buckets (tenant zone), clipped to the requested range. */
	public PeriodReport periods(LocalDate from, LocalDate to) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		DateRange range = periodResolver.resolve(from, to);
		List<LedgerRevenueEntry> entries = ledgerEntries(range);
		List<Expense> expenses = expenseRepository.findActiveDatedBetween(range.from(), range.to());

		Map<YearMonth, List<LedgerRevenueEntry>> entriesByMonth = entries.stream()
			.collect(Collectors.groupingBy(entry -> YearMonth.from(entry.createdAt().atZone(range.zone()))));
		Map<YearMonth, List<Expense>> expensesByMonth = expenses.stream()
			.collect(Collectors.groupingBy(expense -> YearMonth.from(expense.getExpenseDate())));

		List<PeriodRow> rows = new ArrayList<>();
		YearMonth month = YearMonth.from(range.from());
		YearMonth last = YearMonth.from(range.to());
		while (!month.isAfter(last)) {
			LocalDate start = max(month.atDay(1), range.from());
			LocalDate end = min(month.atEndOfMonth(), range.to());
			IncomeSummary income = incomeOf(entriesByMonth.getOrDefault(month, List.of()));
			BigDecimal expenseTotal = sum(
					expensesByMonth.getOrDefault(month, List.of()).stream().map(Expense::getAmount).toList());
			rows.add(new PeriodRow(month.toString(), start, end, income.gross(), income.refunds(), income.net(),
					expenseTotal, income.net().subtract(expenseTotal)));
			month = month.plusMonths(1);
		}
		return new PeriodReport(range.from(), range.to(), range.zone().getId(), PlatformCurrency.DEFAULT_CURRENCY,
				rows);
	}

	private List<LedgerRevenueEntry> ledgerEntries(DateRange range) {
		return ledgerRevenueApi.findRevenueEntries(range.startInclusive(), range.endExclusive());
	}

	private List<CourseRevenueRow> courseRows(List<LedgerRevenueEntry> entries) {
		Map<UUID, List<LedgerRevenueEntry>> byCourse = entries.stream()
			.filter(entry -> entry.courseId() != null)
			.collect(Collectors.groupingBy(LedgerRevenueEntry::courseId));
		if (byCourse.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> titles = courseLookupApi.getCourseSummaries(byCourse.keySet())
			.stream()
			.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));
		Map<UUID, UUID> teachers = courseLookupApi.getTeacherIdsByCourseId(byCourse.keySet());
		return byCourse.entrySet().stream().map(entry -> {
			IncomeSummary income = incomeOf(entry.getValue());
			return new CourseRevenueRow(entry.getKey(), titles.get(entry.getKey()), teachers.get(entry.getKey()),
					income.gross(), income.refunds(), income.net(), income.paymentCount(), income.refundCount());
		}).sorted(Comparator.comparing(CourseRevenueRow::net).reversed()).toList();
	}

	static IncomeSummary incomeOf(List<LedgerRevenueEntry> entries) {
		BigDecimal gross = BigDecimal.ZERO.setScale(2);
		BigDecimal refunds = BigDecimal.ZERO.setScale(2);
		int payments = 0;
		int refundCount = 0;
		for (LedgerRevenueEntry entry : entries) {
			if (entry.refund()) {
				refunds = refunds.add(entry.amount().abs());
				refundCount++;
			}
			else {
				gross = gross.add(entry.amount());
				payments++;
			}
		}
		return new IncomeSummary(gross, refunds, gross.subtract(refunds), payments, refundCount);
	}

	private static IncomeSummary incomeOfRows(List<CourseRevenueRow> rows) {
		BigDecimal gross = sum(rows.stream().map(CourseRevenueRow::gross).toList());
		BigDecimal refunds = sum(rows.stream().map(CourseRevenueRow::refunds).toList());
		return new IncomeSummary(gross, refunds, gross.subtract(refunds),
				rows.stream().mapToInt(CourseRevenueRow::paymentCount).sum(),
				rows.stream().mapToInt(CourseRevenueRow::refundCount).sum());
	}

	private ExpenseSummary expenseSummaryOf(List<Expense> expenses) {
		Map<UUID, List<Expense>> byCategory = expenses.stream().collect(Collectors.groupingBy(Expense::getCategoryId));
		Map<UUID, String> categoryNames = new HashMap<>();
		if (!byCategory.isEmpty()) {
			for (ExpenseCategory category : categoryRepository.findAllById(byCategory.keySet())) {
				categoryNames.put(category.getId(), category.getName());
			}
		}
		List<CategoryTotal> categoryTotals = byCategory.entrySet()
			.stream()
			.map(entry -> new CategoryTotal(entry.getKey(), categoryNames.get(entry.getKey()),
					sum(entry.getValue().stream().map(Expense::getAmount).toList()), entry.getValue().size()))
			.sorted(Comparator.comparing(CategoryTotal::total).reversed())
			.toList();

		Map<ExpenseMethod, List<Expense>> byMethod = new EnumMap<>(ExpenseMethod.class);
		for (Expense expense : expenses) {
			byMethod.computeIfAbsent(expense.getMethod(), ignored -> new ArrayList<>()).add(expense);
		}
		List<MethodTotal> methodTotals = byMethod.entrySet()
			.stream()
			.map(entry -> new MethodTotal(entry.getKey(),
					sum(entry.getValue().stream().map(Expense::getAmount).toList()), entry.getValue().size()))
			.toList();
		return new ExpenseSummary(sum(expenses.stream().map(Expense::getAmount).toList()), expenses.size(),
				categoryTotals, methodTotals);
	}

	private static BigDecimal sum(List<BigDecimal> values) {
		return values.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
	}

	private static LocalDate max(LocalDate a, LocalDate b) {
		return a.isAfter(b) ? a : b;
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}

	public record IncomeSummary(BigDecimal gross, BigDecimal refunds, BigDecimal net, int paymentCount,
			int refundCount) {

	}

	public record CategoryTotal(UUID categoryId, String categoryName, BigDecimal total, int count) {

	}

	public record MethodTotal(ExpenseMethod method, BigDecimal total, int count) {

	}

	public record ExpenseSummary(BigDecimal total, int count, List<CategoryTotal> byCategory,
			List<MethodTotal> byMethod) {

	}

	public record FinanceSummary(LocalDate from, LocalDate to, String timezone, String currency,
			IncomeSummary income, ExpenseSummary expenses, BigDecimal netResult) {

	}

	public record CourseRevenueRow(UUID courseId, String courseTitle, UUID teacherId, BigDecimal gross,
			BigDecimal refunds, BigDecimal net, int paymentCount, int refundCount) {

	}

	public record CourseRevenueReport(LocalDate from, LocalDate to, String currency, List<CourseRevenueRow> rows,
			IncomeSummary totals) {

	}

	public record TeacherRevenueRow(UUID teacherId, String teacherEmail, int courseCount, BigDecimal gross,
			BigDecimal refunds, BigDecimal net, int paymentCount, int refundCount) {

	}

	public record TeacherRevenueReport(LocalDate from, LocalDate to, String currency, List<TeacherRevenueRow> rows,
			IncomeSummary totals) {

	}

	public record PeriodRow(String period, LocalDate from, LocalDate to, BigDecimal incomeGross, BigDecimal refunds,
			BigDecimal incomeNet, BigDecimal expenses, BigDecimal netResult) {

	}

	public record PeriodReport(LocalDate from, LocalDate to, String timezone, String currency,
			List<PeriodRow> rows) {

	}

}

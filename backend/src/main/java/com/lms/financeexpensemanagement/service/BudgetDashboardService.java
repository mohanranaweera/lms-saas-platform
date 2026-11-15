package com.lms.financeexpensemanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
import com.lms.financeexpensemanagement.domain.FinanceAccount;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.financeexpensemanagement.repository.FinanceAccountRepository;
import com.lms.financeexpensemanagement.repository.FinanceTransactionRepository;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIN-01 Budget Dashboard. Reads the <b>account book</b> only: balances are
 * current (as of now, all active accounts); income, expenses and the trend are
 * filtered by {@code transaction_date} inside the selected period. Transfers
 * and opening balances are excluded from period income/expenses; adjustments
 * are reported separately; refunds reduce income in the period they are
 * recorded. "Net activity" = period income - period expenses (a cash view,
 * not an accrual profit).
 */
@Service
public class BudgetDashboardService {

	static final int DAILY_TREND_MAX_DAYS = 92;

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final FinanceTransactionRepository transactionRepository;

	private final FinanceAccountRepository accountRepository;

	private final ExpenseCategoryRepository categoryRepository;

	private final FinancePostingService postingService;

	private final FinancePeriodResolver periodResolver;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	public BudgetDashboardService(FinanceTransactionRepository transactionRepository,
			FinanceAccountRepository accountRepository, ExpenseCategoryRepository categoryRepository,
			FinancePostingService postingService, FinancePeriodResolver periodResolver,
			PermissionCheckService permissionCheckService, TenantContext tenantContext) {
		this.transactionRepository = transactionRepository;
		this.accountRepository = accountRepository;
		this.categoryRepository = categoryRepository;
		this.postingService = postingService;
		this.periodResolver = periodResolver;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public BudgetView budget(LocalDate from, LocalDate to) {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_ANNUAL_STATS);
		FinancePeriodResolver.DateRange range = periodResolver.resolve(from, to);
		UUID tenantId = tenantContext.getTenantId();

		// Balances: current, every active account.
		Map<UUID, BigDecimal> balances = postingService.balances();
		List<AccountBalance> accountBalances = new ArrayList<>();
		BigDecimal total = ZERO;
		for (FinanceAccount account : accountRepository.findAllOrdered(false)) {
			BigDecimal balance = balances.getOrDefault(account.getId(), ZERO);
			accountBalances.add(new AccountBalance(account.getId(), account.getName(), account.getDescription(),
					balance));
			total = total.add(balance);
		}

		// Period activity.
		Map<UUID, String> categoryNames = new HashMap<>();
		for (ExpenseCategory category : categoryRepository.findAll()) {
			categoryNames.put(category.getId(), category.getName());
		}
		BigDecimal income = ZERO;
		BigDecimal refunds = ZERO;
		BigDecimal expenses = ZERO;
		BigDecimal adjustments = ZERO;
		BigDecimal transfers = ZERO;
		long incomeCount = 0;
		long expenseCount = 0;
		Map<String, CategoryAccumulator> incomeByCategory = new LinkedHashMap<>();
		Map<String, CategoryAccumulator> expensesByCategory = new LinkedHashMap<>();
		for (Object[] row : transactionRepository.activityByTypeAndCategory(tenantId, range.from(), range.to())) {
			String type = String.valueOf(row[0]);
			UUID categoryId = row[1] == null ? null : FinancePostingService.uuid(row[1]);
			String sourceType = String.valueOf(row[2]);
			int sign = ((Number) row[3]).intValue();
			boolean credit = Boolean.TRUE.equals(row[4]);
			BigDecimal sum = ((BigDecimal) row[5]).multiply(BigDecimal.valueOf(sign));
			long count = ((Number) row[6]).longValue() * sign;
			switch (type) {
				case "INCOME" -> {
					income = income.add(sum);
					incomeCount += count;
					accumulate(incomeByCategory, categoryLabel(categoryId, sourceType, categoryNames), categoryId,
							sum, count);
				}
				case "REFUND" -> {
					refunds = refunds.add(sum);
					accumulate(incomeByCategory, categoryLabel(categoryId, sourceType, categoryNames), categoryId,
							sum.negate(), 0);
				}
				case "EXPENSE" -> {
					expenses = expenses.add(sum);
					expenseCount += count;
					accumulate(expensesByCategory, categoryLabel(categoryId, sourceType, categoryNames), categoryId,
							sum, count);
				}
				case "ADJUSTMENT" -> adjustments = adjustments.add(credit ? sum : sum.negate());
				case "TRANSFER" -> transfers = transfers.add(sum);
				default -> {
					// OPENING: excluded from period activity by contract.
				}
			}
		}
		BigDecimal netIncome = income.subtract(refunds);

		// Trend.
		boolean daily = ChronoUnit.DAYS.between(range.from(), range.to()) < DAILY_TREND_MAX_DAYS;
		Map<String, TrendAccumulator> trend = new LinkedHashMap<>();
		if (daily) {
			for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) {
				trend.put(d.toString(), new TrendAccumulator(d.toString()));
			}
		}
		else {
			for (YearMonth m = YearMonth.from(range.from()); !m.isAfter(YearMonth.from(range.to())); m = m
				.plusMonths(1)) {
				trend.put(m.toString(), new TrendAccumulator(m.toString()));
			}
		}
		for (Object[] row : transactionRepository.activityByDate(tenantId, range.from(), range.to())) {
			LocalDate date = row[0] instanceof Date sqlDate ? sqlDate.toLocalDate()
					: LocalDate.parse(String.valueOf(row[0]));
			String type = String.valueOf(row[1]);
			int sign = ((Number) row[2]).intValue();
			BigDecimal sum = ((BigDecimal) row[4]).multiply(BigDecimal.valueOf(sign));
			String bucket = daily ? date.toString() : YearMonth.from(date).toString();
			TrendAccumulator acc = trend.computeIfAbsent(bucket, TrendAccumulator::new);
			switch (type) {
				case "INCOME" -> acc.income = acc.income.add(sum);
				case "REFUND" -> acc.income = acc.income.subtract(sum);
				case "EXPENSE" -> acc.expenses = acc.expenses.add(sum);
				default -> {
				}
			}
		}
		List<TrendPoint> points = trend.values()
			.stream()
			.map(a -> new TrendPoint(a.period, a.income, a.expenses, a.income.subtract(a.expenses)))
			.toList();

		List<String> notes = List.of(
				"Balances are current (all active accounts); income, expenses and the trend are filtered by the selected period.",
				"Income and expenses come from the Finance account book: student payments appear once they are routed to an account (see Transactions - Unposted). Student Income reads the payment ledger directly, so the two can differ.",
				"Transfers and opening balances are excluded from period income and expenses; balance adjustments are shown separately; refunds reduce income in the period they are recorded.",
				"Net activity = period income - period expenses. It is a cash movement figure, not an accrual profit.");

		return new BudgetView(range.from(), range.to(), range.zone().getId(), postingService.tenantCurrency(), total,
				netIncome, income, refunds, expenses, netIncome.subtract(expenses), adjustments, transfers,
				incomeCount, expenseCount, views(incomeByCategory), views(expensesByCategory), accountBalances,
				daily ? "DAY" : "MONTH", points, notes);
	}

	private static String categoryLabel(UUID categoryId, String sourceType, Map<UUID, String> names) {
		if (categoryId != null && names.containsKey(categoryId)) {
			return names.get(categoryId);
		}
		return switch (sourceType) {
			case "LEDGER_ENTRY" -> "Student payments";
			case "WALLET_ENTRY" -> "Teacher payouts";
			default -> "Uncategorised";
		};
	}

	private static void accumulate(Map<String, CategoryAccumulator> target, String label, UUID categoryId,
			BigDecimal amount, long count) {
		CategoryAccumulator acc = target.computeIfAbsent(label, l -> new CategoryAccumulator(categoryId, l));
		acc.total = acc.total.add(amount);
		acc.count += count;
	}

	private static List<CategoryTotal> views(Map<String, CategoryAccumulator> accumulators) {
		return accumulators.values()
			.stream()
			.map(a -> new CategoryTotal(a.categoryId, a.label, a.total, a.count))
			.sorted((a, b) -> b.total().compareTo(a.total()))
			.toList();
	}

	private static final class CategoryAccumulator {

		final UUID categoryId;

		final String label;

		BigDecimal total = ZERO;

		long count;

		CategoryAccumulator(UUID categoryId, String label) {
			this.categoryId = categoryId;
			this.label = label;
		}

	}

	private static final class TrendAccumulator {

		final String period;

		BigDecimal income = ZERO;

		BigDecimal expenses = ZERO;

		TrendAccumulator(String period) {
			this.period = period;
		}

	}

	public record BudgetView(LocalDate from, LocalDate to, String timezone, String currency, BigDecimal totalBalance,
			BigDecimal incomeForPeriod, BigDecimal grossIncomeForPeriod, BigDecimal refundsForPeriod,
			BigDecimal expensesForPeriod, BigDecimal netActivity, BigDecimal adjustmentsForPeriod,
			BigDecimal transfersForPeriod, long incomeCount, long expenseCount, List<CategoryTotal> incomeByCategory,
			List<CategoryTotal> expensesByCategory, List<AccountBalance> accountBalances, String trendGranularity,
			List<TrendPoint> cashFlowTrend, List<String> notes) {

	}

	public record CategoryTotal(UUID categoryId, String label, BigDecimal total, long count) {

	}

	public record AccountBalance(UUID accountId, String name, String description, BigDecimal balance) {

	}

	public record TrendPoint(String period, BigDecimal income, BigDecimal expenses, BigDecimal net) {

	}

}

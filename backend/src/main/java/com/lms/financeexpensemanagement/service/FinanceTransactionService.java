package com.lms.financeexpensemanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.financeexpensemanagement.domain.Expense;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import com.lms.financeexpensemanagement.domain.FinanceAccount;
import com.lms.financeexpensemanagement.domain.FinanceEnums.RoutingMethod;
import com.lms.financeexpensemanagement.domain.FinanceEnums.SourceType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import com.lms.financeexpensemanagement.domain.FinancePaymentRouting;
import com.lms.financeexpensemanagement.domain.FinanceTransaction;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.financeexpensemanagement.repository.FinanceAccountRepository;
import com.lms.financeexpensemanagement.repository.FinancePaymentRoutingRepository;
import com.lms.financeexpensemanagement.repository.FinanceTransactionRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi;
import com.lms.paymentmanagement.api.ManualReceiptFact;
import com.lms.paymentmanagement.api.PaymentReportApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIN-01 Transactions tab. Record Transaction has Income, Expense and Transfer
 * modes; corrections are reversals (new rows) and, for source-linked rows, are
 * routed to the owning workflow so the source and the book cannot disagree.
 */
@Service
public class FinanceTransactionService {

	static final int MAX_RECONCILE_ENTRIES = 20_000;

	private final FinanceTransactionRepository transactionRepository;

	private final FinanceAccountRepository accountRepository;

	private final FinancePaymentRoutingRepository routingRepository;

	private final ExpenseCategoryRepository categoryRepository;

	private final FinancePostingService postingService;

	private final ExpenseService expenseService;

	private final PermissionCheckService permissionCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final TeacherIncomeLedgerApi reportLedgerApi;

	private final PaymentReportApi paymentReportApi;

	private final FinancePeriodResolver periodResolver;

	public FinanceTransactionService(FinanceTransactionRepository transactionRepository,
			FinanceAccountRepository accountRepository, FinancePaymentRoutingRepository routingRepository,
			ExpenseCategoryRepository categoryRepository, FinancePostingService postingService,
			ExpenseService expenseService, PermissionCheckService permissionCheckService,
			UserProvisioningApi userProvisioningApi, TeacherIncomeLedgerApi reportLedgerApi,
			PaymentReportApi paymentReportApi, FinancePeriodResolver periodResolver) {
		this.transactionRepository = transactionRepository;
		this.accountRepository = accountRepository;
		this.routingRepository = routingRepository;
		this.categoryRepository = categoryRepository;
		this.postingService = postingService;
		this.expenseService = expenseService;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.reportLedgerApi = reportLedgerApi;
		this.paymentReportApi = paymentReportApi;
		this.periodResolver = periodResolver;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public Page<FinanceTransactionView> search(String search, TransactionType type, UUID accountId, UUID categoryId,
			TransactionStatus status, LocalDate from, LocalDate to, int page, int size) {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS);
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
				Sort.by(Sort.Direction.DESC, "transactionDate").and(Sort.by(Sort.Direction.DESC, "createdAt")));
		Page<FinanceTransaction> result = transactionRepository.search(search, type, accountId, categoryId, status,
				from, to, pageable);
		List<FinanceTransactionView> views = toViews(result.getContent());
		return new org.springframework.data.domain.PageImpl<>(views, pageable, result.getTotalElements());
	}

	@Transactional(readOnly = true)
	public FinanceTransactionView get(UUID transactionId) {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS);
		return toViews(List.of(load(transactionId))).get(0);
	}

	@Transactional(readOnly = true)
	public List<FinanceTransactionView> unposted() {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS);
		return toViews(transactionRepository.findUnposted());
	}

	List<FinanceTransactionView> toViews(Collection<FinanceTransaction> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> accountNames = new HashMap<>();
		for (FinanceAccount account : accountRepository.findAll()) {
			accountNames.put(account.getId(), account.getName());
		}
		Map<UUID, String> categoryNames = new HashMap<>();
		for (ExpenseCategory category : categoryRepository.findAll()) {
			categoryNames.put(category.getId(), category.getName());
		}
		Set<UUID> actors = rows.stream().map(FinanceTransaction::getCreatedBy).filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, String> emails = actors.isEmpty() ? Map.of()
				: userProvisioningApi.findTenantUserSummaries(actors)
					.stream()
					.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
		Map<UUID, UUID> reversedBy = new HashMap<>();
		for (FinanceTransaction row : rows) {
			if (row.getStatus() == TransactionStatus.REVERSED) {
				transactionRepository.findReversalsOf(row.getId())
					.stream()
					.filter(r -> r.getType() == TransactionType.REVERSAL)
					.findFirst()
					.ifPresent(r -> reversedBy.put(row.getId(), r.getId()));
			}
		}
		return rows.stream()
			.map(row -> FinanceTransactionView.of(row, accountNames, categoryNames, emails, reversedBy.get(row.getId())))
			.toList();
	}

	// ------------------------------------------------------------------
	// Record Transaction.
	// ------------------------------------------------------------------

	public record RecordCommand(TransactionType type, BigDecimal amount, LocalDate transactionDate, String note,
			UUID categoryId, UUID accountId, UUID fromAccountId, UUID toAccountId, String currency) {

	}

	@Transactional
	public RecordResult record(RecordCommand command, String idempotencyKey) {
		permissionCheckService.requireCapability(Capability.FINANCE_RECORD_TRANSACTION);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		if (command.type() == null || !(command.type() == TransactionType.INCOME
				|| command.type() == TransactionType.EXPENSE || command.type() == TransactionType.TRANSFER)) {
			throw new FieldValidationException("Invalid type",
					List.of(new FieldError("type", "must be INCOME, EXPENSE or TRANSFER")));
		}
		String key = FinancePostingService.optionalKey(idempotencyKey);
		Optional<FinanceTransaction> replayed = postingService.replay(key);
		if (replayed.isPresent()) {
			return new RecordResult(toViews(List.of(replayed.get())).get(0), false);
		}
		BigDecimal amount = FinancePostingService.normalizeAmount(command.amount(), "amount", false);
		LocalDate date = postingService.requireDate(command.transactionDate(), "transactionDate");
		postingService.requireCurrency(command.currency(), "currency");
		String note = FinancePostingService.optionalText(command.note(), "note");

		FinanceTransaction posted = switch (command.type()) {
			case INCOME -> {
				ExpenseCategory category = postingService.requireActiveCategory(command.categoryId(), "categoryId");
				yield postingService.postIncome(command.accountId(), amount, date, note, category.getId(),
						SourceType.MANUAL, null, key, actorId);
			}
			case EXPENSE -> postExpenseWithRecord(command.accountId(), amount, date, note, command.categoryId(),
					SourceType.MANUAL, null, key, actorId);
			case TRANSFER -> postingService.postTransfer(command.fromAccountId(), command.toAccountId(), amount,
					date, note, SourceType.MANUAL, null, key, actorId);
			default -> throw new IllegalStateException("unreachable");
		};
		return new RecordResult(toViews(List.of(posted)).get(0), true);
	}

	/**
	 * A Finance expense is an {@code expense} row (the existing append-only
	 * expense population every report already reads) plus its posting. The
	 * expense method is derived from the account's routing slot.
	 */
	FinanceTransaction postExpenseWithRecord(UUID accountId, BigDecimal amount, LocalDate date, String note,
			UUID categoryId, SourceType sourceType, UUID sourceId, String idempotencyKey, UUID actorId) {
		FinanceAccount account = postingService.requireActiveAccount(accountId, "accountId");
		ExpenseCategory category = postingService.requireActiveCategory(categoryId, "categoryId");
		String description = note == null ? "Finance expense (" + account.getName() + ")" : note;
		Expense expense = expenseService.recordForFinance(category.getId(), date, description, amount,
				expenseMethodFor(account.getId()), actorId);
		// The posting's source is the expense row; the schedule occurrence (if any) is kept on the
		// occurrence itself (transaction_id), so each fact is still posted exactly once.
		String key = sourceType == SourceType.SCHEDULE ? null : idempotencyKey;
		return postingService.postExpense(account.getId(), amount, date, note, category.getId(), SourceType.EXPENSE,
				expense.getId(), key, actorId);
	}

	ExpenseMethod expenseMethodFor(UUID accountId) {
		List<FinancePaymentRouting> mapped = routingRepository.findAllForAccount(accountId);
		Set<RoutingMethod> codes = new HashSet<>();
		mapped.forEach(r -> codes.add(r.getMethodCode()));
		if (codes.contains(RoutingMethod.CASH)) {
			return ExpenseMethod.CASH;
		}
		if (codes.contains(RoutingMethod.CARD)) {
			return ExpenseMethod.CARD;
		}
		if (codes.contains(RoutingMethod.BANK_TRANSFER) || codes.contains(RoutingMethod.GATEWAY)
				|| codes.contains(RoutingMethod.MANUAL_SLIP)) {
			return ExpenseMethod.BANK_TRANSFER;
		}
		return ExpenseMethod.OTHER;
	}

	@Transactional
	public FinanceTransactionView editMetadata(UUID transactionId, String note, UUID categoryId) {
		permissionCheckService.requireCapability(Capability.FINANCE_RECORD_TRANSACTION);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceTransaction transaction = load(transactionId);
		if (transaction.getType() == TransactionType.TRANSFER || transaction.getType() == TransactionType.OPENING
				|| transaction.getType() == TransactionType.ADJUSTMENT) {
			if (categoryId != null) {
				throw new FieldValidationException("Category not applicable",
						List.of(new FieldError("categoryId", "this transaction type has no category")));
			}
		}
		else if (transaction.getSourceType() == SourceType.MANUAL || transaction.getSourceType() == SourceType.SCHEDULE
				|| transaction.getSourceType() == SourceType.EXPENSE) {
			postingService.requireActiveCategory(categoryId, "categoryId");
		}
		else if (categoryId != null) {
			postingService.requireActiveCategory(categoryId, "categoryId");
		}
		postingService.editMetadata(transaction, FinancePostingService.optionalText(note, "note"), categoryId,
				actorId);
		return toViews(List.of(transaction)).get(0);
	}

	// ------------------------------------------------------------------
	// Corrections.
	// ------------------------------------------------------------------

	@Transactional
	public FinanceTransactionView reverse(UUID transactionId, String reason) {
		permissionCheckService.requireCapability(Capability.FINANCE_REVERSE_TRANSACTION);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		String trimmedReason = FinancePostingService.requireText(reason, "reason");
		FinanceTransaction transaction = load(transactionId);
		switch (transaction.getSourceType()) {
			case LEDGER_ENTRY -> throw new ConflictException(
					"Student payments are corrected in Payments (refund / undo), which reverses this posting");
			case WALLET_ENTRY -> throw new ConflictException(
					"Teacher wallet entries are append-only (ADR-036) - record a wallet Pay In instead");
			case EXPENSE -> {
				// Owning workflow: voiding the expense reverses this posting in the same transaction.
				expenseService.voidExpense(transaction.getSourceId(), trimmedReason);
				return toViews(List.of(load(transactionId))).get(0);
			}
			default -> {
				FinanceTransaction mirror = postingService.reverse(transaction, trimmedReason, actorId);
				return toViews(List.of(mirror)).get(0);
			}
		}
	}

	@Transactional
	public FinanceTransactionView postUnposted(UUID transactionId, UUID accountId) {
		permissionCheckService.requireCapability(Capability.FINANCE_RECORD_TRANSACTION);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceTransaction transaction = load(transactionId);
		if (accountId == null) {
			throw new FieldValidationException("Account is required",
					List.of(new FieldError("accountId", "is required")));
		}
		FinanceTransaction posted = postingService.postUnposted(transaction, accountId, actorId);
		return toViews(List.of(posted)).get(0);
	}

	/**
	 * Opt-in cutover/backfill: posts every confirmed cash payment (and its
	 * refunds) recorded on/after {@code from} that has no posting yet, through
	 * the normal routing (UNPOSTED when unrouted). Idempotent: already-posted
	 * entries are skipped, so opening balances and history cannot count twice
	 * as long as {@code from} is the opening-balance cutover date.
	 */
	@Transactional
	public ReconcileResult reconcile(LocalDate from) {
		permissionCheckService.requireCapability(Capability.FINANCE_ROUTING_CONFIGURE);
		LocalDate start = from == null ? periodResolver.today().withDayOfMonth(1) : from;
		FinancePeriodResolver.DateRange range = periodResolver.resolve(start, periodResolver.today());
		List<ReportLedgerEntry> entries;
		try {
			entries = reportLedgerApi.findEntriesCreatedIn(
					List.of(new InstantRange(range.startInclusive(), range.endExclusive())), MAX_RECONCILE_ENTRIES);
		}
		catch (ReportScopeTooLargeException e) {
			throw new ConflictException("Too many ledger entries in this range - reconcile a shorter range");
		}
		Set<UUID> receiptIds = entries.stream()
			.filter(e -> e.method() == PaymentMethod.MANUAL_RECEIPT)
			.map(ReportLedgerEntry::paymentId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, String> receiptMethods = receiptIds.isEmpty() ? Map.of()
				: paymentReportApi.findManualReceiptsByPaymentIds(receiptIds)
					.stream()
					.collect(Collectors.toMap(ManualReceiptFact::paymentId, ManualReceiptFact::paymentMethod,
							(a, b) -> a));
		int posted = 0;
		int unposted = 0;
		int skipped = 0;
		List<ReportLedgerEntry> confirmations = new ArrayList<>();
		List<ReportLedgerEntry> refunds = new ArrayList<>();
		for (ReportLedgerEntry entry : entries) {
			boolean cash = entry.method() == PaymentMethod.GATEWAY || entry.method() == PaymentMethod.MANUAL_SLIP
					|| entry.method() == PaymentMethod.MANUAL_RECEIPT;
			if (!cash || entry.amount().signum() == 0) {
				skipped++;
				continue;
			}
			(entry.refund() ? refunds : confirmations).add(entry);
		}
		for (ReportLedgerEntry entry : confirmations) {
			if (transactionRepository.findBySource(SourceType.LEDGER_ENTRY, entry.entryId()).isPresent()) {
				skipped++;
				continue;
			}
			RoutingMethod method = switch (entry.method()) {
				case GATEWAY -> RoutingMethod.GATEWAY;
				case MANUAL_SLIP -> RoutingMethod.MANUAL_SLIP;
				case MANUAL_RECEIPT -> switch (String.valueOf(receiptMethods.get(entry.paymentId()))) {
					case "CASH" -> RoutingMethod.CASH;
					case "CARD" -> RoutingMethod.CARD;
					case "BANK_TRANSFER" -> RoutingMethod.BANK_TRANSFER;
					default -> RoutingMethod.OTHER;
				};
				default -> RoutingMethod.OTHER;
			};
			FinanceTransaction row = postingService.recordLedgerPayment(entry.entryId(), entry.amount(),
					entry.currency(), method, entry.createdAt(), "Student payment (reconciled)");
			if (row.getStatus() == TransactionStatus.UNPOSTED) {
				unposted++;
			}
			else {
				posted++;
			}
		}
		for (ReportLedgerEntry entry : refunds) {
			if (transactionRepository.findBySource(SourceType.LEDGER_ENTRY, entry.entryId()).isPresent()) {
				skipped++;
				continue;
			}
			Optional<FinanceTransaction> row = postingService.recordLedgerRefund(entry.entryId(),
					entry.reversesEntryId(), entry.amount().abs(), entry.currency(), entry.createdAt(),
					"Student payment refund (reconciled)");
			if (row.isEmpty()) {
				skipped++;
			}
			else if (row.get().getStatus() == TransactionStatus.UNPOSTED) {
				unposted++;
			}
			else {
				posted++;
			}
		}
		return new ReconcileResult(range.from(), range.to(), posted, unposted, skipped);
	}

	private FinanceTransaction load(UUID transactionId) {
		return transactionRepository.findById(transactionId)
			.orElseThrow(() -> new NotFoundException("Transaction not found"));
	}

	public record RecordResult(FinanceTransactionView transaction, boolean created) {

	}

	public record ReconcileResult(LocalDate from, LocalDate to, int posted, int unposted, int skipped) {

	}

}

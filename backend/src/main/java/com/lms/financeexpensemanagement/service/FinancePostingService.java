package com.lms.financeexpensemanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIN-01: the single writer of {@code finance_transaction}. Every posting path
 * (manual, student payment, refund, expense, wallet payout, schedule,
 * adjustment, reversal) goes through here so the invariants live in one place:
 * positive amounts at 2 dp in the tenant currency, active same-tenant accounts,
 * one row per fact (unique source link), reversals as new rows, and derived
 * balances. It performs no permission checks - the calling service does.
 */
@Service
public class FinancePostingService {

	private static final Logger log = LoggerFactory.getLogger(FinancePostingService.class);

	static final String TARGET_TRANSACTION = "finance_transaction";

	static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

	static final int MAX_TEXT = 500;

	static final int MAX_KEY = 100;

	private final FinanceTransactionRepository transactionRepository;

	private final FinanceAccountRepository accountRepository;

	private final FinancePaymentRoutingRepository routingRepository;

	private final ExpenseCategoryRepository categoryRepository;

	private final FinancePeriodResolver periodResolver;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	private final EntityManager entityManager;

	public FinancePostingService(FinanceTransactionRepository transactionRepository,
			FinanceAccountRepository accountRepository, FinancePaymentRoutingRepository routingRepository,
			ExpenseCategoryRepository categoryRepository, FinancePeriodResolver periodResolver,
			TenantContext tenantContext, AuditLogApi auditLogApi, EntityManager entityManager) {
		this.transactionRepository = transactionRepository;
		this.accountRepository = accountRepository;
		this.routingRepository = routingRepository;
		this.categoryRepository = categoryRepository;
		this.periodResolver = periodResolver;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
		this.entityManager = entityManager;
	}

	// ------------------------------------------------------------------
	// Validation helpers shared by the finance services.
	// ------------------------------------------------------------------

	/** Tenant-scoped load: 404 for unknown/foreign ids, 400 when archived. */
	FinanceAccount requireActiveAccount(UUID accountId, String field) {
		if (accountId == null) {
			throw new FieldValidationException("Account is required", List.of(new FieldError(field, "is required")));
		}
		FinanceAccount account = accountRepository.findById(accountId)
			.orElseThrow(() -> new NotFoundException("Finance account not found"));
		if (account.isArchived()) {
			throw new FieldValidationException("Account is archived",
					List.of(new FieldError(field, "account '" + account.getName() + "' is archived")));
		}
		return account;
	}

	ExpenseCategory requireActiveCategory(UUID categoryId, String field) {
		if (categoryId == null) {
			throw new FieldValidationException("Category is required",
					List.of(new FieldError(field, "is required")));
		}
		ExpenseCategory category = categoryRepository.findById(categoryId)
			.orElseThrow(() -> new NotFoundException("Category not found"));
		if (category.isArchived()) {
			throw new FieldValidationException("Category is archived",
					List.of(new FieldError(field, "category '" + category.getName() + "' is archived")));
		}
		return category;
	}

	static BigDecimal normalizeAmount(BigDecimal value, String field, boolean allowZero) {
		if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError(field, allowZero ? "must be zero or more" : "must be greater than zero")));
		}
		BigDecimal scaled;
		try {
			scaled = value.setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException e) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError(field, "must have at most 2 decimal places")));
		}
		if (scaled.compareTo(MAX_AMOUNT) > 0) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError(field, "must not exceed " + MAX_AMOUNT.toPlainString())));
		}
		return scaled;
	}

	static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new FieldValidationException("Invalid " + field, List.of(new FieldError(field, "is required")));
		}
		return boundedText(value, field);
	}

	static String optionalText(String value, String field) {
		return (value == null || value.isBlank()) ? null : boundedText(value, field);
	}

	private static String boundedText(String value, String field) {
		String trimmed = value.trim();
		if (trimmed.length() > MAX_TEXT) {
			throw new FieldValidationException("Invalid " + field,
					List.of(new FieldError(field, "must be at most " + MAX_TEXT + " characters")));
		}
		return trimmed;
	}

	static String optionalKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		String trimmed = key.trim();
		if (trimmed.length() > MAX_KEY) {
			throw new FieldValidationException("Invalid Idempotency-Key",
					List.of(new FieldError("Idempotency-Key", "must be at most " + MAX_KEY + " characters")));
		}
		return trimmed;
	}

	LocalDate requireDate(LocalDate date, String field) {
		if (date == null) {
			throw new FieldValidationException("Date is required", List.of(new FieldError(field, "is required")));
		}
		if (date.isAfter(periodResolver.today())) {
			throw new FieldValidationException("Date is in the future",
					List.of(new FieldError(field, "must not be in the future")));
		}
		return date;
	}

	String tenantCurrency() {
		return periodResolver.tenantCurrency();
	}

	java.time.ZoneId tenantZone() {
		return periodResolver.tenantZone();
	}

	void requireCurrency(String requested, String field) {
		String tenantCurrency = tenantCurrency();
		if (requested != null && !requested.isBlank() && !requested.trim().equalsIgnoreCase(tenantCurrency)) {
			throw new FieldValidationException("Currency mismatch",
					List.of(new FieldError(field, "must be " + tenantCurrency)));
		}
	}

	static UUID currentActorOrNull() {
		return AuthenticatedPrincipalHolder.isSet() ? AuthenticatedPrincipalHolder.get().userId() : null;
	}

	// ------------------------------------------------------------------
	// Balances.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public BigDecimal balanceOf(UUID accountId) {
		BigDecimal balance = transactionRepository.balanceOf(tenantContext.getTenantId(), accountId);
		return balance == null ? BigDecimal.ZERO.setScale(2) : balance.setScale(2, RoundingMode.UNNECESSARY);
	}

	@Transactional(readOnly = true)
	public Map<UUID, BigDecimal> balances() {
		Map<UUID, BigDecimal> result = new HashMap<>();
		for (Object[] row : transactionRepository.balancesByAccount(tenantContext.getTenantId())) {
			result.put(uuid(row[0]), ((BigDecimal) row[1]).setScale(2, RoundingMode.UNNECESSARY));
		}
		return result;
	}

	static UUID uuid(Object value) {
		return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
	}

	/** Pessimistic lock on the account row: adjustments, archive and transfers serialize per account. */
	FinanceAccount lockAccount(UUID accountId, String field) {
		FinanceAccount account = requireActiveAccount(accountId, field);
		entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
		if (account.isArchived()) {
			throw new FieldValidationException("Account is archived",
					List.of(new FieldError(field, "account '" + account.getName() + "' is archived")));
		}
		return account;
	}

	// ------------------------------------------------------------------
	// Postings.
	// ------------------------------------------------------------------

	Optional<FinanceTransaction> replay(String idempotencyKey) {
		return idempotencyKey == null ? Optional.empty() : transactionRepository.findByIdempotencyKey(idempotencyKey);
	}

	@Transactional
	public FinanceTransaction postOpening(FinanceAccount account, BigDecimal openingBalance, UUID actorId) {
		FinanceTransaction row = FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.OPENING,
				openingBalance, account.getCurrency(), null, account.getId(), null, periodResolver.today(),
				Instant.now(), "Opening balance", SourceType.MANUAL, null, null, null, null, actorId);
		return saveAndAudit(row, actorId, null);
	}

	@Transactional
	public FinanceTransaction postIncome(UUID toAccountId, BigDecimal amount, LocalDate date, String note,
			UUID categoryId, SourceType sourceType, UUID sourceId, String idempotencyKey, UUID actorId) {
		FinanceAccount account = lockAccount(toAccountId, "accountId");
		FinanceTransaction row = FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.INCOME, amount,
				account.getCurrency(), null, account.getId(), categoryId, date, Instant.now(), note, sourceType,
				sourceId, null, idempotencyKey, null, actorId);
		return saveAndAudit(row, actorId, null);
	}

	@Transactional
	public FinanceTransaction postExpense(UUID fromAccountId, BigDecimal amount, LocalDate date, String note,
			UUID categoryId, SourceType sourceType, UUID sourceId, String idempotencyKey, UUID actorId) {
		FinanceAccount account = lockAccount(fromAccountId, "accountId");
		FinanceTransaction row = FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.EXPENSE, amount,
				account.getCurrency(), account.getId(), null, categoryId, date, Instant.now(), note, sourceType,
				sourceId, null, idempotencyKey, null, actorId);
		return saveAndAudit(row, actorId, null);
	}

	/** Both legs are one row, so a transfer can never be half-posted. */
	@Transactional
	public FinanceTransaction postTransfer(UUID fromAccountId, UUID toAccountId, BigDecimal amount, LocalDate date,
			String note, SourceType sourceType, UUID sourceId, String idempotencyKey, UUID actorId) {
		if (fromAccountId != null && fromAccountId.equals(toAccountId)) {
			throw new FieldValidationException("Transfer needs two different accounts",
					List.of(new FieldError("toAccountId", "must differ from the source account")));
		}
		// Lock in a stable order so two opposite transfers cannot deadlock.
		FinanceAccount first;
		FinanceAccount second;
		if (fromAccountId != null && toAccountId != null && fromAccountId.compareTo(toAccountId) < 0) {
			first = lockAccount(fromAccountId, "fromAccountId");
			second = lockAccount(toAccountId, "toAccountId");
		}
		else {
			second = lockAccount(toAccountId, "toAccountId");
			first = lockAccount(fromAccountId, "fromAccountId");
		}
		FinanceAccount from = first.getId().equals(fromAccountId) ? first : second;
		FinanceAccount to = from == first ? second : first;
		FinanceTransaction row = FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.TRANSFER,
				amount, from.getCurrency(), from.getId(), to.getId(), null, date, Instant.now(), note, sourceType,
				sourceId, null, idempotencyKey, null, actorId);
		return saveAndAudit(row, actorId, null);
	}

	/**
	 * The balance pencil: the user states the balance they saw and the target;
	 * the signed difference is appended with a reason. Runs under the account
	 * lock so two concurrent adjustments can never both apply.
	 */
	@Transactional
	public FinanceTransaction adjust(UUID accountId, BigDecimal targetBalance, BigDecimal expectedBalance,
			String reason, String idempotencyKey, UUID actorId) {
		FinanceAccount account = lockAccount(accountId, "accountId");
		Optional<FinanceTransaction> replayed = replay(idempotencyKey);
		if (replayed.isPresent()) {
			return replayed.get();
		}
		BigDecimal before = balanceOf(account.getId());
		if (expectedBalance != null && before.compareTo(expectedBalance) != 0) {
			throw new ConflictException("The account balance changed to " + before.toPlainString()
					+ " since you opened it - review the new balance and try again");
		}
		BigDecimal difference = targetBalance.subtract(before);
		if (difference.signum() == 0) {
			throw new FieldValidationException("Nothing to adjust",
					List.of(new FieldError("targetBalance", "is already the current balance")));
		}
		FinanceTransaction row = FinanceTransaction.adjustment(tenantContext.getTenantId(), difference,
				account.getCurrency(), account.getId(), periodResolver.today(), Instant.now(), before, reason,
				idempotencyKey, actorId);
		return saveAndAudit(row, actorId, reason);
	}

	/** Appends the mirror row and marks the original REVERSED; refused twice. */
	@Transactional
	public FinanceTransaction reverse(FinanceTransaction original, String reason, UUID actorId) {
		entityManager.refresh(original, LockModeType.PESSIMISTIC_WRITE);
		if (original.getStatus() == TransactionStatus.UNPOSTED) {
			throw new ConflictException("An unposted row has nothing to reverse - post it or leave it");
		}
		if (original.getStatus() == TransactionStatus.REVERSED
				|| !transactionRepository.findReversalsOf(original.getId())
					.stream()
					.filter(r -> r.getType() == TransactionType.REVERSAL)
					.toList()
					.isEmpty()) {
			throw new ConflictException("This transaction was already reversed");
		}
		if (original.getType() == TransactionType.OPENING || original.getType() == TransactionType.REVERSAL) {
			throw new ConflictException("An opening balance or a reversal cannot be reversed");
		}
		Instant now = Instant.now();
		FinanceTransaction mirror = FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.REVERSAL,
				original.getAmount(), original.getCurrency(), original.getToAccountId(), original.getFromAccountId(),
				original.getCategoryId(), periodResolver.today(), now, original.getNote(), SourceType.MANUAL, null,
				original.getId(), null, reason, actorId);
		mirror = transactionRepository.saveAndFlush(mirror);
		original.markReversed(actorId, now);
		transactionRepository.saveAndFlush(original);
		audit(mirror, actorId, "finance_transaction.reversed", reason);
		return mirror;
	}

	/** Reverses the posting of a source fact, if one exists (e.g. an expense being voided). */
	@Transactional
	public Optional<FinanceTransaction> reverseBySource(SourceType sourceType, UUID sourceId, String reason,
			UUID actorId) {
		return transactionRepository.findBySource(sourceType, sourceId)
			.filter(t -> t.getStatus() == TransactionStatus.POSTED)
			.map(t -> reverse(t, reason, actorId));
	}

	@Transactional
	public void editMetadata(FinanceTransaction transaction, String note, UUID categoryId, UUID actorId) {
		transaction.editMetadata(note, categoryId);
		transactionRepository.saveAndFlush(transaction);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("type", transaction.getType().name());
		metadata.put("categoryId", categoryId == null ? "" : categoryId.toString());
		metadata.put("hasNote", note != null);
		auditLogApi.record(new AuditLogEntry(actorId, "finance_transaction.updated", TARGET_TRANSACTION,
				transaction.getId(), null, metadata));
	}

	// ------------------------------------------------------------------
	// Student payments (ledger-driven).
	// ------------------------------------------------------------------

	/** Routing lookup: the exact method code, then OTHER; an archived target counts as unrouted. */
	Optional<FinanceAccount> routeFor(RoutingMethod method) {
		return routingRepository.findByMethod(method)
			.or(() -> routingRepository.findByMethod(RoutingMethod.OTHER))
			.map(FinancePaymentRouting::getAccountId)
			.flatMap(accountRepository::findById)
			.filter(account -> !account.isArchived());
	}

	/**
	 * One INCOME row per confirmed cash payment. Without routing the row is
	 * saved UNPOSTED (listed for staff), never to an arbitrary account.
	 * Idempotent on the ledger entry id.
	 */
	@Transactional
	public FinanceTransaction recordLedgerPayment(UUID ledgerEntryId, BigDecimal amount, String currency,
			RoutingMethod method, Instant recordedAt, String note) {
		Optional<FinanceTransaction> existing = transactionRepository.findBySource(SourceType.LEDGER_ENTRY,
				ledgerEntryId);
		if (existing.isPresent()) {
			return existing.get();
		}
		LocalDate date = recordedAt.atZone(periodResolver.tenantZone()).toLocalDate();
		UUID actorId = currentActorOrNull();
		Optional<FinanceAccount> target = routeFor(method);
		FinanceTransaction row = target
			.map(account -> FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.INCOME, amount,
					currency, null, account.getId(), null, date, Instant.now(), note, SourceType.LEDGER_ENTRY,
					ledgerEntryId, null, null, null, actorId))
			.orElseGet(() -> FinanceTransaction.unposted(tenantContext.getTenantId(), TransactionType.INCOME,
					amount, currency, date, note, ledgerEntryId, null, actorId));
		FinanceTransaction saved = transactionRepository.saveAndFlush(row);
		if (saved.getStatus() == TransactionStatus.UNPOSTED) {
			log.atWarn()
				.setMessage("finance.payment_unposted")
				.addKeyValue("tenantId", tenantContext.getTenantId())
				.addKeyValue("ledgerEntryId", ledgerEntryId)
				.addKeyValue("method", method)
				.log();
		}
		return saved;
	}

	/**
	 * A REFUND row debits the ORIGINAL posting's account (the ledger link, not
	 * current routing). When the original is UNPOSTED the refund waits with it
	 * and is posted together with it. No original posting (pre-cutover payment)
	 * -> nothing is posted, so the book never shows a refund without its income.
	 */
	@Transactional
	public Optional<FinanceTransaction> recordLedgerRefund(UUID refundEntryId, UUID reversedEntryId,
			BigDecimal amount, String currency, Instant recordedAt, String note) {
		Optional<FinanceTransaction> existing = transactionRepository.findBySource(SourceType.LEDGER_ENTRY,
				refundEntryId);
		if (existing.isPresent()) {
			return existing;
		}
		Optional<FinanceTransaction> original = transactionRepository.findBySource(SourceType.LEDGER_ENTRY,
				reversedEntryId);
		if (original.isEmpty()) {
			log.atInfo()
				.setMessage("finance.refund_not_posted_no_original")
				.addKeyValue("tenantId", tenantContext.getTenantId())
				.addKeyValue("ledgerEntryId", refundEntryId)
				.log();
			return Optional.empty();
		}
		FinanceTransaction income = original.get();
		LocalDate date = recordedAt.atZone(periodResolver.tenantZone()).toLocalDate();
		UUID actorId = currentActorOrNull();
		FinanceTransaction row;
		if (income.getStatus() == TransactionStatus.UNPOSTED) {
			row = FinanceTransaction.unposted(tenantContext.getTenantId(), TransactionType.REFUND, amount, currency,
					date, note, refundEntryId, income.getId(), actorId);
		}
		else {
			row = FinanceTransaction.posted(tenantContext.getTenantId(), TransactionType.REFUND, amount, currency,
					income.getToAccountId(), null, income.getCategoryId(), date, Instant.now(), note,
					SourceType.LEDGER_ENTRY, refundEntryId, income.getId(), null, null, actorId);
		}
		return Optional.of(transactionRepository.saveAndFlush(row));
	}

	/** Posts an UNPOSTED student payment (and any refund waiting on it) once. */
	@Transactional
	public FinanceTransaction postUnposted(FinanceTransaction transaction, UUID accountId, UUID actorId) {
		entityManager.refresh(transaction, LockModeType.PESSIMISTIC_WRITE);
		if (transaction.getStatus() != TransactionStatus.UNPOSTED) {
			throw new ConflictException("This transaction is already posted");
		}
		if (transaction.getType() != TransactionType.INCOME) {
			throw new ConflictException("Post the original payment; its refund is posted with it");
		}
		FinanceAccount account = lockAccount(accountId, "accountId");
		Instant now = Instant.now();
		transaction.post(null, account.getId(), now);
		transactionRepository.saveAndFlush(transaction);
		audit(transaction, actorId, "finance_transaction.posted", null);
		for (FinanceTransaction refund : transactionRepository.findReversalsOf(transaction.getId())) {
			if (refund.getStatus() == TransactionStatus.UNPOSTED) {
				refund.post(account.getId(), null, now);
				transactionRepository.saveAndFlush(refund);
				audit(refund, actorId, "finance_transaction.posted", null);
			}
		}
		return transaction;
	}

	// ------------------------------------------------------------------
	// Internals.
	// ------------------------------------------------------------------

	private FinanceTransaction saveAndAudit(FinanceTransaction row, UUID actorId, String reason) {
		FinanceTransaction saved = transactionRepository.saveAndFlush(row);
		audit(saved, actorId, "finance_transaction.posted", reason);
		return saved;
	}

	/**
	 * Audit needs an actor (AuditLogEntry contract). System postings driven by a
	 * gateway webhook have none; the row itself keeps the source link, and the
	 * payment confirmation is audited by the payments module.
	 */
	private void audit(FinanceTransaction t, UUID actorId, String action, String reason) {
		if (actorId == null) {
			return;
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("type", t.getType().name());
		metadata.put("status", t.getStatus().name());
		metadata.put("amount", t.getAmount().toPlainString());
		metadata.put("currency", t.getCurrency());
		metadata.put("transactionDate", t.getTransactionDate().toString());
		metadata.put("sourceType", t.getSourceType().name());
		if (t.getSourceId() != null) {
			metadata.put("sourceId", t.getSourceId().toString());
		}
		if (t.getFromAccountId() != null) {
			metadata.put("fromAccountId", t.getFromAccountId().toString());
		}
		if (t.getToAccountId() != null) {
			metadata.put("toAccountId", t.getToAccountId().toString());
		}
		if (t.getReversesTransactionId() != null) {
			metadata.put("reversesTransactionId", t.getReversesTransactionId().toString());
		}
		if (t.getBalanceBefore() != null) {
			metadata.put("balanceBefore", t.getBalanceBefore().toPlainString());
			metadata.put("balanceAfter", t.getBalanceAfter().toPlainString());
		}
		auditLogApi.record(new AuditLogEntry(actorId, action, TARGET_TRANSACTION, t.getId(),
				(reason == null || reason.isBlank()) ? null : reason, metadata));
	}

}

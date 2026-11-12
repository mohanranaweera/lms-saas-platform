package com.lms.ledgersettlementmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlement;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.domain.TeacherWallet;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherWalletRepository;
import com.lms.tenantmanagement.api.TenantConfigApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TEACHER-WALLET-01 / ADR-036: Teachers -> Show Wallet, layered over teacher
 * settlements (no parallel ledger).
 *
 * <p><b>Balance</b> = the amount payable to the teacher = unpaid (CALCULATED)
 * settlements and settlement adjustments + every POSTED wallet entry
 * (OPENING, PAY_IN, PAY_OUT, BALANCE_ADJ). Entries are append-only {@code
 * teacher_settlement} rows; none writes a {@code ledger_entry}, moves money or
 * touches student income/commission.
 *
 * <p><b>Consistency</b>: every balance-changing write - here and in {@link
 * TeacherSettlementService} - first locks the teacher's {@code teacher_wallet}
 * row ({@code SELECT ... FOR UPDATE}), then reads the balance, so per-teacher
 * writes serialize and {@code balance_before/after} are exact. Pay In / Pay Out /
 * Adjust require an {@code Idempotency-Key}; a replay with the same payload
 * returns the original entry, a different payload is 409.
 *
 * <p><b>Finance accounts</b>: tenant Finance Accounts do not exist yet (Wave 7
 * deferred bank/cash accounts), so an account- or category-linked entry is
 * rejected server-side rather than silently dropped.
 */
@Service
@Transactional
public class TeacherWalletService {

	static final String TARGET_WALLET = "teacher_wallet";

	static final String ACCOUNT_LINK_UNAVAILABLE = "Finance accounts are not available yet - record a wallet-only "
			+ "entry (leave Expense Account empty)";

	private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

	private static final int MAX_TEXT = 500;

	private static final int MAX_KEY = 100;

	private final TeacherWalletRepository walletRepository;

	private final TeacherSettlementRepository settlementRepository;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final EntityManager entityManager;

	public TeacherWalletService(TeacherWalletRepository walletRepository,
			TeacherSettlementRepository settlementRepository, UserProvisioningApi userProvisioningApi,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantConfigApi tenantConfigApi,
			TenantContext tenantContext, EntityManager entityManager) {
		this.walletRepository = walletRepository;
		this.settlementRepository = settlementRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.entityManager = entityManager;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public WalletView get(UUID teacherId) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		requireTeacher(teacherId);
		return viewOf(teacherId, walletRepository.findByTeacher(teacherId).orElse(null));
	}

	@Transactional(readOnly = true)
	public List<EntryView> entries(UUID teacherId, TeacherSettlementKind kind, int limit) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		requireTeacher(teacherId);
		Collection<TeacherSettlementKind> kinds = kind == null ? List.of(TeacherSettlementKind.OPENING,
				TeacherSettlementKind.PAY_IN, TeacherSettlementKind.PAY_OUT, TeacherSettlementKind.BALANCE_ADJ)
				: List.of(kind);
		if (kind != null && !kind.isWalletEntry()) {
			throw new FieldValidationException("Invalid entry type",
					List.of(new FieldError("kind", "must be a wallet entry type")));
		}
		int size = Math.max(1, Math.min(limit, 50));
		List<TeacherSettlement> walletRows = settlementRepository
			.findWalletEntries(teacherId, kinds,
					PageRequest.of(0, size,
							Sort.by(Sort.Direction.DESC, "calculatedAt").and(Sort.by(Sort.Direction.DESC, "id"))))
			.getContent();

		// The Pay In / Pay Out tabs also show settlement activity, read-only and derived from the
		// settlement rows (never copied into the wallet): a settlement raises the balance by its
		// share when calculated and lowers it by the same amount when marked paid. Each event sits
		// in the tab matching its effect, so a negative share or adjustment lands in Pay Out.
		List<SettlementEvent> events = new ArrayList<>();
		if (kind == TeacherSettlementKind.PAY_IN || kind == TeacherSettlementKind.PAY_OUT) {
			boolean increases = kind == TeacherSettlementKind.PAY_IN;
			settlementRepository.findSettlementActivity(teacherId, false, increases, size)
				.forEach(s -> events.add(new SettlementEvent(s, false)));
			settlementRepository.findSettlementActivity(teacherId, true, !increases, size)
				.forEach(s -> events.add(new SettlementEvent(s, true)));
		}

		Set<UUID> actors = new HashSet<>();
		walletRows.forEach(entry -> actors.add(entry.getCalculatedBy()));
		events.forEach(event -> actors.add(event.actor()));
		Map<UUID, String> emails = emailsOf(actors);
		List<EntryView> views = new ArrayList<>();
		walletRows.forEach(entry -> views.add(EntryView.from(entry, emails)));
		events.forEach(event -> views.add(event.view(emails)));
		views.sort(Comparator.comparing(EntryView::createdAt).reversed());
		return views.size() > size ? List.copyOf(views.subList(0, size)) : views;
	}

	/** One settlement-driven balance change: earned (at calculation) or paid (at mark-paid). */
	private record SettlementEvent(TeacherSettlement settlement, boolean paid) {

		UUID actor() {
			return paid ? settlement.getPaidBy() : settlement.getCalculatedBy();
		}

		EntryView view(Map<UUID, String> emails) {
			TeacherSettlement s = settlement;
			String event = paid ? EntryView.SETTLEMENT_PAID
					: s.getKind() == TeacherSettlementKind.ADJUSTMENT ? EntryView.SETTLEMENT_ADJUSTED
							: EntryView.SETTLEMENT_EARNED;
			BigDecimal effect = paid ? s.getShareAmount().negate() : s.getShareAmount();
			return new EntryView(s.getId(), s.getKind(), event, "SETTLEMENT", effect, s.getCurrency(),
					s.getReason(), null, null, paid ? s.getPaidAt() : s.getCalculatedAt(), actor(),
					emails.get(actor()), null, null, s.getId(), s.getPeriodStart(), s.getPeriodEnd(),
					paid ? s.getPayoutReference() : null);
		}

	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	/**
	 * Creates the wallet and records the opening balance once (an OPENING entry - not a cash
	 * movement). A retry with the same opening balance returns the existing wallet ({@code
	 * created = false}); a different one is 409. A concurrent creation race loses on {@code
	 * uq_teacher_wallet_teacher} (409, transaction rolled back).
	 */
	public CreateResult create(UUID teacherId, BigDecimal openingBalance, String currency) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		requireTeacher(teacherId);
		BigDecimal opening = normalizeMoney(openingBalance, "openingBalance", true);
		String tenantCurrency = tenantCurrency();
		requireCurrency(currency, tenantCurrency);

		Optional<TeacherWallet> existing = walletRepository.findByTeacher(teacherId);
		if (existing.isPresent()) {
			TeacherSettlement openingEntry = settlementRepository
				.findWalletEntries(teacherId, List.of(TeacherSettlementKind.OPENING), PageRequest.of(0, 1))
				.stream()
				.findFirst()
				.orElse(null);
			if (openingEntry != null && openingEntry.getShareAmount().compareTo(opening) == 0) {
				return new CreateResult(viewOf(teacherId, existing.get()), false);
			}
			throw new ConflictException("This teacher already has a wallet");
		}

		Instant now = Instant.now();
		TeacherWallet wallet = walletRepository
			.saveAndFlush(new TeacherWallet(tenantContext.getTenantId(), teacherId, tenantCurrency, actorId, now));
		BigDecimal before = balanceOf(teacherId);
		TeacherSettlement entry = settlementRepository.saveAndFlush(TeacherSettlement.walletEntry(wallet,
				TeacherSettlementKind.OPENING, opening, "Opening balance", before, null, null, actorId, now));

		Map<String, Object> metadata = entryMetadata(entry);
		metadata.put("origin", "WALLET_CREATION");
		auditLogApi.record(new AuditLogEntry(actorId, "teacher_wallet.created", TARGET_WALLET, wallet.getId(), null,
				metadata));
		return new CreateResult(viewOf(teacherId, wallet), true);
	}

	public PostResult payIn(UUID teacherId, PostingRequest request, String idempotencyKey) {
		return post(teacherId, TeacherSettlementKind.PAY_IN, request, idempotencyKey);
	}

	public PostResult payOut(UUID teacherId, PostingRequest request, String idempotencyKey) {
		return post(teacherId, TeacherSettlementKind.PAY_OUT, request, idempotencyKey);
	}

	/**
	 * The balance pencil: the caller states the target balance and the balance they saw; the
	 * difference is appended as a BALANCE_ADJ entry with the mandatory reason. A stale {@code
	 * expectedBalance} is 409 - nothing is overwritten.
	 */
	public PostResult adjust(UUID teacherId, BigDecimal targetBalance, BigDecimal expectedBalance, String reason,
			String idempotencyKey) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		requireTeacher(teacherId);
		BigDecimal target = normalizeMoney(targetBalance, "targetBalance", true);
		BigDecimal expected = normalizeSigned(expectedBalance, "expectedBalance");
		String trimmedReason = requireText(reason, "reason");
		String key = requireKey(idempotencyKey);
		String hash = hash("BALANCE_ADJ", teacherId, target, expected, trimmedReason, null, null, null);

		TeacherWallet wallet = lockExisting(teacherId);
		Optional<PostResult> replay = replay(key, hash, teacherId);
		if (replay.isPresent()) {
			return replay.get();
		}
		BigDecimal before = balanceOf(teacherId);
		if (before.compareTo(expected) != 0) {
			throw new ConflictException("The wallet balance changed to " + before.toPlainString()
					+ " since you opened it - review the new balance and try again");
		}
		BigDecimal difference = target.subtract(before);
		if (difference.signum() == 0) {
			throw new FieldValidationException("Nothing to adjust",
					List.of(new FieldError("targetBalance", "is already the current balance")));
		}
		TeacherSettlement entry = settlementRepository.saveAndFlush(TeacherSettlement.walletEntry(wallet,
				TeacherSettlementKind.BALANCE_ADJ, difference, trimmedReason, before, key, hash, actorId,
				Instant.now()));
		auditLogApi.record(new AuditLogEntry(actorId, "teacher_wallet.balance_adjusted", TARGET_WALLET,
				wallet.getId(), trimmedReason, entryMetadata(entry)));
		return new PostResult(viewOf(teacherId, wallet), EntryView.from(entry, emailsOf(List.of(actorId))), true);
	}

	/**
	 * Locks the teacher's wallet row when one exists, so settlement writes that change the
	 * balance serialize with wallet postings. Package-private: no permission check.
	 */
	Optional<TeacherWallet> lockIfExists(UUID teacherId) {
		Optional<TeacherWallet> wallet = walletRepository.findByTeacher(teacherId);
		wallet.ifPresent(w -> entityManager.refresh(w, LockModeType.PESSIMISTIC_WRITE));
		return wallet;
	}

	/** Current amount payable to the teacher (see class doc). */
	BigDecimal balanceOf(UUID teacherId) {
		return settlementRepository.findPayableAmounts(teacherId)
			.stream()
			.reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
	}

	// ------------------------------------------------------------------
	// Internals.
	// ------------------------------------------------------------------

	private PostResult post(UUID teacherId, TeacherSettlementKind kind, PostingRequest request,
			String idempotencyKey) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		requireTeacher(teacherId);
		BigDecimal amount = normalizeMoney(request.amount(), "amount", false);
		String description = requireText(request.description(), "description");
		requireCurrency(request.currency(), tenantCurrency());
		if (request.categoryId() != null && request.accountId() == null) {
			throw new FieldValidationException("Invalid category",
					List.of(new FieldError("categoryId", "select an expense account before a category")));
		}
		if (request.accountId() != null) {
			throw new FieldValidationException("Account-linked wallet entries are not available",
					List.of(new FieldError("accountId", ACCOUNT_LINK_UNAVAILABLE)));
		}
		BigDecimal expected = request.expectedBalance() == null ? null
				: normalizeSigned(request.expectedBalance(), "expectedBalance");
		String key = requireKey(idempotencyKey);
		String hash = hash(kind.name(), teacherId, amount, expected, description, request.currency(),
				request.accountId(), request.categoryId());

		TeacherWallet wallet = lockExisting(teacherId);
		Optional<PostResult> replay = replay(key, hash, teacherId);
		if (replay.isPresent()) {
			return replay.get();
		}
		BigDecimal before = balanceOf(teacherId);
		if (expected != null && before.compareTo(expected) != 0) {
			throw new ConflictException("The wallet balance changed to " + before.toPlainString()
					+ " since you opened it - review the new balance and try again");
		}
		BigDecimal signed = kind == TeacherSettlementKind.PAY_OUT ? amount.negate() : amount;
		if (before.add(signed).signum() < 0) {
			throw new FieldValidationException("Insufficient wallet balance",
					List.of(new FieldError("amount", "exceeds the amount payable (" + before.toPlainString() + ")")));
		}
		TeacherSettlement entry = settlementRepository.saveAndFlush(TeacherSettlement.walletEntry(wallet, kind,
				signed, description, before, key, hash, actorId, Instant.now()));
		String action = kind == TeacherSettlementKind.PAY_OUT ? "teacher_wallet.pay_out_recorded"
				: "teacher_wallet.pay_in_recorded";
		auditLogApi.record(new AuditLogEntry(actorId, action, TARGET_WALLET, wallet.getId(), null,
				entryMetadata(entry)));
		return new PostResult(viewOf(teacherId, wallet), EntryView.from(entry, emailsOf(List.of(actorId))), true);
	}

	private TeacherWallet lockExisting(UUID teacherId) {
		return lockIfExists(teacherId)
			.orElseThrow(() -> new ConflictException("Create the teacher's wallet before recording entries"));
	}

	/** Runs under the wallet lock, so a concurrent duplicate observes the first posting. */
	private Optional<PostResult> replay(String key, String hash, UUID teacherId) {
		return settlementRepository.findByIdempotencyKey(key).map(previous -> {
			if (!hash.equals(previous.getRequestHash()) || !teacherId.equals(previous.getTeacherId())) {
				throw new ConflictException("This Idempotency-Key was already used for a different request");
			}
			TeacherWallet wallet = walletRepository.findByTeacher(teacherId).orElseThrow();
			return new PostResult(viewOf(teacherId, wallet),
					EntryView.from(previous, emailsOf(List.of(previous.getCalculatedBy()))), false);
		});
	}

	private WalletView viewOf(UUID teacherId, TeacherWallet wallet) {
		String currency = wallet != null ? wallet.getCurrency() : tenantCurrency();
		if (wallet == null) {
			return new WalletView(teacherId, false, null, currency, null, null, null, null);
		}
		BigDecimal balance = balanceOf(teacherId);
		BigDecimal unpaidSettlements = settlementRepository.findUnpaidSettlementAmounts(teacherId)
			.stream()
			.reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
		return new WalletView(teacherId, true, wallet.getId(), currency, balance, unpaidSettlements,
				balance.subtract(unpaidSettlements), wallet.getCreatedAt());
	}

	private static Map<String, Object> entryMetadata(TeacherSettlement entry) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("entryId", entry.getId().toString());
		metadata.put("teacherId", entry.getTeacherId().toString());
		metadata.put("kind", entry.getKind().name());
		metadata.put("amount", entry.getShareAmount().toPlainString());
		metadata.put("currency", entry.getCurrency());
		metadata.put("balanceBefore", entry.getBalanceBefore().toPlainString());
		metadata.put("balanceAfter", entry.getBalanceAfter().toPlainString());
		if (entry.getReason() != null) {
			metadata.put("description", entry.getReason());
		}
		if (entry.getIdempotencyKey() != null) {
			metadata.put("reference", entry.getIdempotencyKey());
		}
		return metadata;
	}

	private String tenantCurrency() {
		return tenantConfigApi.resolveDefaultCurrency(tenantContext.getTenantId());
	}

	private static void requireCurrency(String requested, String walletCurrency) {
		if (requested != null && !requested.isBlank() && !requested.trim().equalsIgnoreCase(walletCurrency)) {
			throw new FieldValidationException("Currency mismatch",
					List.of(new FieldError("currency", "must be " + walletCurrency)));
		}
	}

	private static BigDecimal normalizeMoney(BigDecimal value, String field, boolean allowZero) {
		if (value == null || value.signum() < 0 || (!allowZero && value.signum() == 0)) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError(field, allowZero ? "must be zero or more" : "must be greater than zero")));
		}
		BigDecimal scaled = normalizeSigned(value, field);
		if (scaled.compareTo(MAX_AMOUNT) > 0) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError(field, "must not exceed " + MAX_AMOUNT.toPlainString())));
		}
		return scaled;
	}

	private static BigDecimal normalizeSigned(BigDecimal value, String field) {
		if (value == null) {
			throw new FieldValidationException("Invalid amount", List.of(new FieldError(field, "is required")));
		}
		try {
			return value.setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException e) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError(field, "must have at most 2 decimal places")));
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new FieldValidationException("Invalid " + field, List.of(new FieldError(field, "is required")));
		}
		String trimmed = value.trim();
		if (trimmed.length() > MAX_TEXT) {
			throw new FieldValidationException("Invalid " + field,
					List.of(new FieldError(field, "must be at most " + MAX_TEXT + " characters")));
		}
		return trimmed;
	}

	private static String requireKey(String key) {
		if (key == null || key.isBlank() || key.trim().length() > MAX_KEY) {
			throw new FieldValidationException("Idempotency-Key header required",
					List.of(new FieldError("Idempotency-Key", "is required (1-" + MAX_KEY + " characters)")));
		}
		return key.trim();
	}

	static String hash(Object... parts) {
		String canonical = java.util.Arrays.stream(parts)
			.map(part -> part instanceof BigDecimal decimal ? decimal.toPlainString() : Objects.toString(part, ""))
			.collect(Collectors.joining("\u001f"));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private TenantUserSummary requireTeacher(UUID teacherId) {
		return userProvisioningApi.findTenantUserSummaries(List.of(teacherId))
			.stream()
			.filter(summary -> summary.userId().equals(teacherId) && "TEACHER".equals(summary.roleCode()))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Teacher not found"));
	}

	private Map<UUID, String> emailsOf(Collection<UUID> userIds) {
		Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		return userProvisioningApi.findTenantUserSummaries(ids)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
	}

	// ------------------------------------------------------------------
	// Views.
	// ------------------------------------------------------------------

	/**
	 * {@code balance} = amount payable to the teacher = {@code unpaidSettlements} + {@code
	 * walletEntries}. All amount fields are {@code null} when {@code exists} is false.
	 */
	public record WalletView(UUID teacherId, boolean exists, UUID walletId, String currency, BigDecimal balance,
			BigDecimal unpaidSettlements, BigDecimal walletEntries, Instant createdAt) {

	}

	/**
	 * One activity line. {@code amount} is the signed effect on the balance (PAY_OUT and paid
	 * settlements negative). {@code source = WALLET} rows are wallet entries ({@code event} = their
	 * kind, with balance before/after); {@code source = SETTLEMENT} rows are read-only settlement
	 * events ({@code SETTLEMENT_EARNED | SETTLEMENT_ADJUSTED | SETTLEMENT_PAID}) with the settlement
	 * id, period and payout reference, and null balances. {@code accountName}/{@code categoryName}
	 * stay null until Finance Accounts exist.
	 */
	public record EntryView(UUID id, TeacherSettlementKind kind, String event, String source, BigDecimal amount,
			String currency, String description, BigDecimal balanceBefore, BigDecimal balanceAfter,
			Instant createdAt, UUID createdBy, String createdByEmail, String accountName, String categoryName,
			UUID settlementId, LocalDate periodStart, LocalDate periodEnd, String payoutReference) {

		/** Settlement calculated (share > 0 -> Pay In tab, < 0 -> Pay Out tab). */
		public static final String SETTLEMENT_EARNED = "SETTLEMENT_EARNED";

		/** Settlement adjustment row calculated. */
		public static final String SETTLEMENT_ADJUSTED = "SETTLEMENT_ADJUSTED";

		/** Settlement (or settlement adjustment) marked paid - lowers the balance by its share. */
		public static final String SETTLEMENT_PAID = "SETTLEMENT_PAID";

		static EntryView from(TeacherSettlement entry, Map<UUID, String> emails) {
			return new EntryView(entry.getId(), entry.getKind(), entry.getKind().name(), "WALLET",
					entry.getShareAmount(), entry.getCurrency(), entry.getReason(), entry.getBalanceBefore(),
					entry.getBalanceAfter(), entry.getCalculatedAt(), entry.getCalculatedBy(),
					emails.get(entry.getCalculatedBy()), null, null, null, null, null, null);
		}

	}

	public record PostingRequest(BigDecimal amount, String description, String currency, UUID accountId,
			UUID categoryId, BigDecimal expectedBalance) {

	}

	public record CreateResult(WalletView wallet, boolean created) {

	}

	public record PostResult(WalletView wallet, EntryView entry, boolean created) {

	}

}

package com.lms.financeexpensemanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.financeexpensemanagement.domain.FinanceAccount;
import com.lms.financeexpensemanagement.domain.FinanceEnums.RoutingMethod;
import com.lms.financeexpensemanagement.domain.FinancePaymentRouting;
import com.lms.financeexpensemanagement.domain.FinanceTransaction;
import com.lms.financeexpensemanagement.repository.FinanceAccountRepository;
import com.lms.financeexpensemanagement.repository.FinancePaymentRoutingRepository;
import com.lms.financeexpensemanagement.repository.FinanceScheduledTransactionRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIN-01 Accounts tab: finance accounts (name, description, derived balance),
 * the reasoned balance adjustment and the payment-method routing. Accounts are
 * archived, never deleted; an account that is routed, used by an active
 * schedule or holds a non-zero balance cannot be archived.
 */
@Service
public class FinanceAccountService {

	static final String TARGET_ACCOUNT = "finance_account";

	static final String TARGET_ROUTING = "finance_payment_routing";

	private static final int MAX_NAME = 100;

	private final FinanceAccountRepository accountRepository;

	private final FinancePaymentRoutingRepository routingRepository;

	private final FinanceScheduledTransactionRepository scheduleRepository;

	private final FinancePostingService postingService;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public FinanceAccountService(FinanceAccountRepository accountRepository,
			FinancePaymentRoutingRepository routingRepository,
			FinanceScheduledTransactionRepository scheduleRepository, FinancePostingService postingService,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.accountRepository = accountRepository;
		this.routingRepository = routingRepository;
		this.scheduleRepository = scheduleRepository;
		this.postingService = postingService;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<AccountView> list(boolean includeArchived) {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS);
		return views(accountRepository.findAllOrdered(includeArchived));
	}

	@Transactional(readOnly = true)
	public AccountView get(UUID accountId) {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS);
		return views(List.of(load(accountId))).get(0);
	}

	private List<AccountView> views(List<FinanceAccount> accounts) {
		Map<UUID, BigDecimal> balances = postingService.balances();
		Map<UUID, List<RoutingMethod>> mapped = new LinkedHashMap<>();
		for (FinancePaymentRouting routing : routingRepository.findAll()) {
			mapped.computeIfAbsent(routing.getAccountId(), ignored -> new ArrayList<>()).add(routing.getMethodCode());
		}
		return accounts.stream()
			.map(account -> new AccountView(account.getId(), account.getName(), account.getDescription(),
					account.getCurrency(), balances.getOrDefault(account.getId(), BigDecimal.ZERO.setScale(2)),
					account.isArchived(), mapped.getOrDefault(account.getId(), List.of()), account.getCreatedAt(),
					account.getUpdatedAt()))
			.toList();
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	@Transactional
	public AccountView create(String name, String description, BigDecimal openingBalance) {
		permissionCheckService.requireCapability(Capability.FINANCE_ACCOUNTS_MANAGE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		String trimmedName = requireName(name);
		String trimmedDescription = FinancePostingService.optionalText(description, "description");
		BigDecimal opening = FinancePostingService.normalizeAmount(openingBalance, "openingBalance", true);
		if (accountRepository.existsByNameIgnoreCaseExcluding(trimmedName, null)) {
			throw new ConflictException("An account with this name already exists");
		}
		FinanceAccount account = accountRepository.saveAndFlush(new FinanceAccount(tenantContext.getTenantId(),
				trimmedName, trimmedDescription, postingService.tenantCurrency(), actorId));
		FinanceTransaction opening0 = postingService.postOpening(account, opening, actorId);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", trimmedName);
		metadata.put("currency", account.getCurrency());
		metadata.put("openingBalance", opening.toPlainString());
		metadata.put("openingTransactionId", opening0.getId().toString());
		auditLogApi.record(new AuditLogEntry(actorId, "finance_account.created", TARGET_ACCOUNT, account.getId(),
				null, metadata));
		return views(List.of(account)).get(0);
	}

	@Transactional
	public AccountView update(UUID accountId, String name, String description) {
		permissionCheckService.requireCapability(Capability.FINANCE_ACCOUNTS_MANAGE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceAccount account = load(accountId);
		String trimmedName = requireName(name);
		String trimmedDescription = FinancePostingService.optionalText(description, "description");
		if (accountRepository.existsByNameIgnoreCaseExcluding(trimmedName, account.getId())) {
			throw new ConflictException("An account with this name already exists");
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("nameBefore", account.getName());
		metadata.put("nameAfter", trimmedName);
		account.rename(trimmedName, trimmedDescription, actorId);
		accountRepository.saveAndFlush(account);
		auditLogApi.record(new AuditLogEntry(actorId, "finance_account.updated", TARGET_ACCOUNT, account.getId(),
				null, metadata));
		return views(List.of(account)).get(0);
	}

	/** The balance pencil: never an overwrite - a reasoned ADJUSTMENT row with before/after. */
	@Transactional
	public FinanceTransactionView adjust(UUID accountId, BigDecimal targetBalance, BigDecimal expectedBalance,
			String reason, String idempotencyKey) {
		permissionCheckService.requireCapability(Capability.FINANCE_ADJUST_ACCOUNT_BALANCE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		load(accountId);
		BigDecimal target = FinancePostingService.normalizeAmount(targetBalance, "targetBalance", true);
		BigDecimal expected = expectedBalance == null ? null
				: FinancePostingService.normalizeAmount(expectedBalance, "expectedBalance", true);
		String trimmedReason = FinancePostingService.requireText(reason, "reason");
		String key = FinancePostingService.optionalKey(idempotencyKey);
		FinanceTransaction row = postingService.adjust(accountId, target, expected, trimmedReason, key, actorId);
		Map<UUID, String> names = Map.of(accountId, load(accountId).getName());
		return FinanceTransactionView.of(row, names, Map.of(), Map.of(), null);
	}

	@Transactional
	public AccountView archive(UUID accountId) {
		permissionCheckService.requireCapability(Capability.FINANCE_ACCOUNTS_MANAGE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceAccount account = postingService.lockAccount(accountId, "accountId");
		if (!routingRepository.findAllForAccount(account.getId()).isEmpty()) {
			throw new ConflictException("This account is mapped to a payment method - change the routing first");
		}
		if (scheduleRepository.existsActiveForAccount(account.getId())) {
			throw new ConflictException("An active scheduled transaction uses this account - cancel or edit it first");
		}
		BigDecimal balance = postingService.balanceOf(account.getId());
		if (balance.signum() != 0) {
			throw new ConflictException("The account balance is " + balance.toPlainString()
					+ " - transfer or adjust it to zero before archiving");
		}
		account.archive(actorId, Instant.now());
		accountRepository.saveAndFlush(account);
		auditLogApi.record(new AuditLogEntry(actorId, "finance_account.archived", TARGET_ACCOUNT, account.getId(),
				null, Map.of("name", account.getName())));
		return views(List.of(account)).get(0);
	}

	@Transactional
	public AccountView unarchive(UUID accountId) {
		permissionCheckService.requireCapability(Capability.FINANCE_ACCOUNTS_MANAGE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceAccount account = load(accountId);
		if (!account.isArchived()) {
			throw new ConflictException("This account is not archived");
		}
		account.unarchive(actorId);
		accountRepository.saveAndFlush(account);
		auditLogApi.record(new AuditLogEntry(actorId, "finance_account.unarchived", TARGET_ACCOUNT, account.getId(),
				null, Map.of("name", account.getName())));
		return views(List.of(account)).get(0);
	}

	// ------------------------------------------------------------------
	// Routing (Configure dialog).
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public RoutingView routing() {
		permissionCheckService.requireCapability(Capability.FINANCE_DASHBOARD_TRANSACTIONS);
		return routingView();
	}

	private RoutingView routingView() {
		Map<RoutingMethod, UUID> byMethod = new EnumMap<>(RoutingMethod.class);
		Instant updatedAt = null;
		for (FinancePaymentRouting routing : routingRepository.findAll()) {
			byMethod.put(routing.getMethodCode(), routing.getAccountId());
			if (updatedAt == null || routing.getUpdatedAt().isAfter(updatedAt)) {
				updatedAt = routing.getUpdatedAt();
			}
		}
		Map<String, UUID> methods = new LinkedHashMap<>();
		byMethod.forEach((method, accountId) -> methods.put(method.name(), accountId));
		return new RoutingView(byMethod.get(RoutingMethod.CASH), byMethod.get(RoutingMethod.CARD),
				byMethod.get(RoutingMethod.GATEWAY), byMethod.get(RoutingMethod.OTHER), methods, updatedAt);
	}

	/**
	 * Replaces the whole routing set atomically. The Bank/Online slot writes the
	 * three real codes it stands for (BANK_TRANSFER, GATEWAY, MANUAL_SLIP).
	 * Applies to future postings only - existing rows keep their account.
	 */
	@Transactional
	public RoutingView saveRouting(UUID cashAccountId, UUID cardAccountId, UUID bankOnlineAccountId,
			UUID otherAccountId) {
		permissionCheckService.requireCapability(Capability.FINANCE_ROUTING_CONFIGURE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Map<RoutingMethod, UUID> next = new EnumMap<>(RoutingMethod.class);
		put(next, RoutingMethod.CASH, cashAccountId, "cashAccountId");
		put(next, RoutingMethod.CARD, cardAccountId, "cardAccountId");
		put(next, RoutingMethod.BANK_TRANSFER, bankOnlineAccountId, "bankOnlineAccountId");
		put(next, RoutingMethod.GATEWAY, bankOnlineAccountId, "bankOnlineAccountId");
		put(next, RoutingMethod.MANUAL_SLIP, bankOnlineAccountId, "bankOnlineAccountId");
		put(next, RoutingMethod.OTHER, otherAccountId, "otherAccountId");

		RoutingView before = routingView();
		List<FinancePaymentRouting> existing = routingRepository.findAll();
		routingRepository.deleteAll(existing);
		routingRepository.flush();
		Instant now = Instant.now();
		List<FinancePaymentRouting> rows = new ArrayList<>();
		next.forEach((method, accountId) -> rows
			.add(new FinancePaymentRouting(tenantContext.getTenantId(), method, accountId, actorId, now)));
		routingRepository.saveAll(rows);
		routingRepository.flush();
		RoutingView after = routingView();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", stringify(before.methods()));
		metadata.put("after", stringify(after.methods()));
		auditLogApi.record(new AuditLogEntry(actorId, "finance_routing.changed", TARGET_ROUTING,
				tenantContext.getTenantId(), null, metadata));
		return after;
	}

	private void put(Map<RoutingMethod, UUID> target, RoutingMethod method, UUID accountId, String field) {
		if (accountId == null) {
			return;
		}
		postingService.requireActiveAccount(accountId, field);
		target.put(method, accountId);
	}

	private static Map<String, String> stringify(Map<String, UUID> methods) {
		Map<String, String> out = new LinkedHashMap<>();
		methods.forEach((k, v) -> out.put(k, v.toString()));
		return out;
	}

	// ------------------------------------------------------------------
	// Helpers / views.
	// ------------------------------------------------------------------

	private FinanceAccount load(UUID accountId) {
		return accountRepository.findById(accountId)
			.orElseThrow(() -> new NotFoundException("Finance account not found"));
	}

	private static String requireName(String name) {
		if (name == null || name.isBlank()) {
			throw new FieldValidationException("Invalid name", List.of(new FieldError("name", "is required")));
		}
		String trimmed = name.trim();
		if (trimmed.length() > MAX_NAME) {
			throw new FieldValidationException("Invalid name",
					List.of(new FieldError("name", "must be at most " + MAX_NAME + " characters")));
		}
		return trimmed;
	}

	public record AccountView(UUID id, String name, String description, String currency, BigDecimal balance,
			boolean archived, List<RoutingMethod> mappedMethods, Instant createdAt, Instant updatedAt) {

	}

	/**
	 * The four Klass slots plus the raw code map. {@code bankOnlineAccountId}
	 * reports the GATEWAY row; {@code methods} shows every real code.
	 */
	public record RoutingView(UUID cashAccountId, UUID cardAccountId, UUID bankOnlineAccountId, UUID otherAccountId,
			Map<String, UUID> methods, Instant updatedAt) {

	}

}

package com.lms.financeexpensemanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.money.PlatformCurrency;
import com.lms.common.tenant.TenantContext;
import com.lms.financeexpensemanagement.domain.Expense;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.financeexpensemanagement.repository.ExpenseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Wave 7 (PAR-23-01/05) - expense recording. Every method re-checks {@link
 * DomainArea#FINANCE_EXPENSES} server-side; every lookup is tenant-scoped
 * (another tenant's id is a 404, never a leak).
 *
 * <p>Expenses are append-only (wave-07-plan.md §10 judgment call 1): there is
 * no update or delete method here. {@link #voidExpense} is the only mutation,
 * gated on {@code DELETE} (the matrix's "D" is narrowed to "void"), requires a
 * reason, is one-way, and is audit-logged in the same transaction.
 *
 * <p>{@link #create} mirrors {@code SlipUploadService}'s upload gate exactly:
 * no {@code @Transactional} on the method (the object-storage call is
 * outbound), bounded streaming read, magic-byte sniffing, and a best-effort
 * compensating object delete if the DB write fails - no partial write.
 */
@Service
public class ExpenseService {

	private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);

	private static final Duration ATTACHMENT_URL_TTL = Duration.ofMinutes(5);

	private static final int STREAM_READ_CHUNK_SIZE = 8192;

	private final ExpenseRepository expenseRepository;

	private final ExpenseCategoryRepository categoryRepository;

	private final ExpenseWriteService expenseWriteService;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	private final ObjectStorageApi objectStorageApi;

	private final UserProvisioningApi userProvisioningApi;

	private final AuditLogApi auditLogApi;

	private final FinancePeriodResolver periodResolver;

	private final long maxAttachmentSizeBytes;

	private final EntityManager entityManager;

	public ExpenseService(ExpenseRepository expenseRepository, ExpenseCategoryRepository categoryRepository,
			ExpenseWriteService expenseWriteService, PermissionCheckService permissionCheckService,
			TenantContext tenantContext, ObjectStorageApi objectStorageApi, UserProvisioningApi userProvisioningApi,
			AuditLogApi auditLogApi, FinancePeriodResolver periodResolver,
			@Value("${app.finance.expense.max-attachment-size-bytes}") long maxAttachmentSizeBytes,
			EntityManager entityManager) {
		this.entityManager = entityManager;
		this.expenseRepository = expenseRepository;
		this.categoryRepository = categoryRepository;
		this.expenseWriteService = expenseWriteService;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
		this.objectStorageApi = objectStorageApi;
		this.userProvisioningApi = userProvisioningApi;
		this.auditLogApi = auditLogApi;
		this.periodResolver = periodResolver;
		this.maxAttachmentSizeBytes = maxAttachmentSizeBytes;
	}

	@Transactional(readOnly = true)
	public Page<ExpenseView> search(LocalDate from, LocalDate to, UUID categoryId, ExpenseMethod method,
			boolean includeVoided, Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		if (from != null && to != null && from.isAfter(to)) {
			throw new FieldValidationException("Invalid date range",
					List.of(new FieldError("from", "must be on or before 'to'")));
		}
		Page<Expense> page = expenseRepository.search(from, to, categoryId, method, includeVoided, pageable);
		return new PageImpl<>(toViews(page.getContent()), pageable, page.getTotalElements());
	}

	@Transactional(readOnly = true)
	public ExpenseView get(UUID expenseId) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		return toViews(List.of(load(expenseId))).get(0);
	}

	public ExpenseView create(NewExpenseCommand command, MultipartFile attachment) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		UUID tenantId = tenantContext.getTenantId();

		ExpenseCategory category = categoryRepository.findById(command.categoryId())
			.orElseThrow(() -> new NotFoundException("Expense category not found"));
		if (category.isArchived()) {
			throw new FieldValidationException("Expense category is archived",
					List.of(new FieldError("categoryId", "category is archived")));
		}
		if (command.expenseDate().isAfter(periodResolver.today())) {
			throw new FieldValidationException("Expense date is in the future",
					List.of(new FieldError("expenseDate", "must not be in the future")));
		}
		BigDecimal amount = normalizeAmount(command.amount());

		Expense expense = new Expense(tenantId, category.getId(), command.expenseDate(),
				command.description().trim(), amount, PlatformCurrency.DEFAULT_CURRENCY, command.method(),
				blankToNull(command.reference()), actorId);

		// Upload gate runs BEFORE any storage/DB write; a rejected file never
		// touches the object store or the expense table.
		StoredObject stored = null;
		if (attachment != null && !attachment.isEmpty()) {
			byte[] bytes = readBoundedBytes(attachment, maxAttachmentSizeBytes);
			String sniffedMimeType = ExpenseReceiptSniffer.sniff(bytes);
			if (sniffedMimeType == null) {
				throw new UnsupportedMediaTypeException(
						"The receipt's content does not match an accepted format (PDF, PNG or JPEG)");
			}
			String filename = sanitizeFilename(attachment.getOriginalFilename());
			stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes),
					sniffedMimeType, bytes.length, filename));
			expense.attachReceipt(stored.objectKey(), filename, sniffedMimeType, bytes.length);
		}

		Expense saved;
		try {
			saved = expenseWriteService.persistNew(expense);
		}
		catch (RuntimeException saveFailure) {
			if (stored != null) {
				try {
					objectStorageApi.delete(stored.objectKey());
				}
				catch (RuntimeException deleteFailure) {
					log.warn("Failed to delete orphaned expense receipt '{}' after a save failure",
							stored.objectKey(), deleteFailure);
				}
			}
			throw saveFailure;
		}
		return toViews(List.of(saved)).get(0);
	}

	@Transactional
	public ExpenseView voidExpense(UUID expenseId, String reason) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.DELETE);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Expense expense = load(expenseId);
		// Row lock + re-read so two concurrent voids serialize: the second
		// observes the first's void and gets 409 instead of overwriting the
		// recorded reason/actor. Loaded via the tenant-scoped repository above.
		entityManager.refresh(expense, LockModeType.PESSIMISTIC_WRITE);
		if (expense.isVoided()) {
			throw new ConflictException("Expense is already voided");
		}
		String trimmedReason = reason.trim();
		expense.voidExpense(actorId, trimmedReason, Instant.now());
		Expense saved = expenseRepository.save(expense);
		auditLogApi.record(new AuditLogEntry(actorId, "expense.voided", ExpenseWriteService.TARGET_ENTITY,
				saved.getId(), trimmedReason,
				Map.of("amount", saved.getAmount().toPlainString(), "currency", saved.getCurrency(), "expenseDate",
						saved.getExpenseDate().toString(), "categoryId", saved.getCategoryId().toString())));
		return toViews(List.of(saved)).get(0);
	}

	/**
	 * Protected-content read: a short-lived signed URL, only after a
	 * server-side {@code VIEW} check and a tenant-scoped lookup of THIS
	 * expense - the raw object key is never exposed on any response.
	 */
	@Transactional(readOnly = true)
	public SignedDownloadUrl attachmentUrl(UUID expenseId) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		Expense expense = load(expenseId);
		if (!expense.hasAttachment()) {
			throw new NotFoundException("This expense has no receipt attachment");
		}
		return objectStorageApi.generateSignedDownloadUrl(expense.getAttachmentObjectKey(), ATTACHMENT_URL_TTL);
	}

	private Expense load(UUID expenseId) {
		return expenseRepository.findById(expenseId).orElseThrow(() -> new NotFoundException("Expense not found"));
	}

	/**
	 * Bean Validation already enforces {@code > 0} and at most 2 fraction
	 * digits; this is the defense-in-depth normalization to the column scale.
	 */
	private static BigDecimal normalizeAmount(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError("amount", "must be greater than 0")));
		}
		try {
			return amount.setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException e) {
			throw new FieldValidationException("Invalid amount",
					List.of(new FieldError("amount", "must have at most 2 decimal places")));
		}
	}

	private List<ExpenseView> toViews(List<Expense> expenses) {
		if (expenses.isEmpty()) {
			return List.of();
		}
		Set<UUID> categoryIds = expenses.stream().map(Expense::getCategoryId).collect(Collectors.toSet());
		Map<UUID, String> categoryNames = new HashMap<>();
		for (ExpenseCategory category : categoryRepository.findAllById(categoryIds)) {
			categoryNames.put(category.getId(), category.getName());
		}
		Set<UUID> userIds = new HashSet<>();
		for (Expense expense : expenses) {
			userIds.add(expense.getCreatedBy());
			if (expense.getVoidedBy() != null) {
				userIds.add(expense.getVoidedBy());
			}
		}
		Map<UUID, String> emails = userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
		return expenses.stream()
			.map(expense -> new ExpenseView(expense.getId(), expense.getExpenseDate(), expense.getCategoryId(),
					categoryNames.get(expense.getCategoryId()), expense.getDescription(), expense.getAmount(),
					expense.getCurrency(), expense.getMethod(), expense.getReference(), expense.hasAttachment(),
					expense.getAttachmentFilename(), expense.getAttachmentMimeType(),
					expense.getAttachmentSizeBytes(), expense.getCreatedBy(), emails.get(expense.getCreatedBy()),
					expense.getCreatedAt(), expense.isVoided(), expense.getVoidedAt(), expense.getVoidedBy(),
					expense.getVoidedBy() == null ? null : emails.get(expense.getVoidedBy()),
					expense.getVoidReason()))
			.toList();
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private static String sanitizeFilename(String rawFilename) {
		String name = Objects.requireNonNullElse(rawFilename, "receipt");
		int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (lastSlash >= 0) {
			name = name.substring(lastSlash + 1);
		}
		name = name.chars()
			.filter(c -> c >= 0x20)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
		if (name.isBlank()) {
			name = "receipt";
		}
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	/** Aborts as soon as the running byte count exceeds the limit - never trusts a client-declared size. */
	private static byte[] readBoundedBytes(MultipartFile file, long maxBytes) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[STREAM_READ_CHUNK_SIZE];
			long total = 0;
			int read;
			while ((read = input.read(chunk)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw new PayloadTooLargeException("The receipt exceeds the maximum allowed size");
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded receipt", e);
		}
	}

	public record NewExpenseCommand(UUID categoryId, LocalDate expenseDate, String description, BigDecimal amount,
			ExpenseMethod method, String reference) {

	}

	public record ExpenseView(UUID id, LocalDate expenseDate, UUID categoryId, String categoryName,
			String description, BigDecimal amount, String currency, ExpenseMethod method, String reference,
			boolean hasAttachment, String attachmentFilename, String attachmentMimeType, Long attachmentSizeBytes,
			UUID createdBy, String createdByEmail, Instant createdAt, boolean voided, Instant voidedAt,
			UUID voidedBy, String voidedByEmail, String voidReason) {

	}

}

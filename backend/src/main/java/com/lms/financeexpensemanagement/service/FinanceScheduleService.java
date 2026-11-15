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
import com.lms.financeexpensemanagement.domain.FinanceEnums.OccurrenceStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.SourceType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import com.lms.financeexpensemanagement.domain.FinanceScheduledOccurrence;
import com.lms.financeexpensemanagement.domain.FinanceScheduledTransaction;
import com.lms.financeexpensemanagement.domain.FinanceTransaction;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.financeexpensemanagement.repository.FinanceAccountRepository;
import com.lms.financeexpensemanagement.repository.FinanceScheduledOccurrenceRepository;
import com.lms.financeexpensemanagement.repository.FinanceScheduledTransactionRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIN-01 Scheduled tab.
 *
 * <p><b>Policy (LMS, differs from Klass):</b> no existing policy authorises the
 * platform to move money on its own, so the scheduler only <em>materialises</em>
 * due occurrences (the daily job, or a list refresh). An authorised user then
 * records each occurrence; the posting is idempotent on
 * {@code (tenant, schedule, due date)}. Nothing here ever calls a payment
 * gateway or sends money.
 *
 * <p><b>Calendar rules</b> (tenant time zone): a ONE_TIME schedule falls due on
 * its date; a MONTHLY schedule falls due on {@code dayOfMonth} of every month
 * from {@code startsOn} (the creation date) on. A month without that day
 * (e.g. the 31st in April) is <em>skipped</em> - no occurrence is created and
 * the UI says so. Missed job runs are caught up: every due date up to today
 * that has no occurrence gets one (status DUE). A failed posting stays
 * FAILED with its error and can be retried; attempts are counted.
 */
@Service
public class FinanceScheduleService {

	private static final Logger log = LoggerFactory.getLogger(FinanceScheduleService.class);

	static final String TARGET_SCHEDULE = "finance_scheduled_transaction";

	private static final int MAX_NAME = 100;

	private final FinanceScheduledTransactionRepository scheduleRepository;

	private final FinanceScheduledOccurrenceRepository occurrenceRepository;

	private final FinanceAccountRepository accountRepository;

	private final ExpenseCategoryRepository categoryRepository;

	private final FinancePostingService postingService;

	private final FinanceTransactionService transactionService;

	private final FinancePeriodResolver periodResolver;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final EntityManager entityManager;

	public FinanceScheduleService(FinanceScheduledTransactionRepository scheduleRepository,
			FinanceScheduledOccurrenceRepository occurrenceRepository, FinanceAccountRepository accountRepository,
			ExpenseCategoryRepository categoryRepository, FinancePostingService postingService,
			FinanceTransactionService transactionService, FinancePeriodResolver periodResolver,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext,
			EntityManager entityManager) {
		this.scheduleRepository = scheduleRepository;
		this.occurrenceRepository = occurrenceRepository;
		this.accountRepository = accountRepository;
		this.categoryRepository = categoryRepository;
		this.postingService = postingService;
		this.transactionService = transactionService;
		this.periodResolver = periodResolver;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.entityManager = entityManager;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	@Transactional
	public Page<ScheduleView> search(String search, ScheduleStatus status, int page, int size) {
		permissionCheckService.requireCapability(Capability.FINANCE_VIEW_SCHEDULES);
		// A list refresh is also a materialisation point, so the tab never depends on the job alone.
		materializeDueForCurrentTenant();
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		Page<FinanceScheduledTransaction> result = scheduleRepository.search(search, status, pageable);
		return new PageImpl<>(views(result.getContent()), pageable, result.getTotalElements());
	}

	@Transactional(readOnly = true)
	public ScheduleView get(UUID scheduleId) {
		permissionCheckService.requireCapability(Capability.FINANCE_VIEW_SCHEDULES);
		return views(List.of(load(scheduleId))).get(0);
	}

	@Transactional(readOnly = true)
	public List<OccurrenceView> occurrences(UUID scheduleId) {
		permissionCheckService.requireCapability(Capability.FINANCE_VIEW_SCHEDULES);
		load(scheduleId);
		return occurrenceRepository.findBySchedule(scheduleId).stream().map(OccurrenceView::of).toList();
	}

	@Transactional
	public List<OccurrenceView> dueOccurrences() {
		permissionCheckService.requireCapability(Capability.FINANCE_VIEW_SCHEDULES);
		materializeDueForCurrentTenant();
		return occurrenceRepository.findOpen().stream().map(OccurrenceView::of).toList();
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	public record ScheduleCommand(String name, TransactionType type, BigDecimal amount, String currency,
			UUID accountId, UUID fromAccountId, UUID toAccountId, UUID categoryId, String note,
			ScheduleType scheduleType, LocalDate scheduledDate, Integer dayOfMonth) {

	}

	@Transactional
	public ScheduleView create(ScheduleCommand command) {
		permissionCheckService.requireCapability(Capability.FINANCE_MANAGE_SCHEDULES);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		Validated v = validate(command, null);
		LocalDate today = periodResolver.today();
		FinanceScheduledTransaction schedule = scheduleRepository.saveAndFlush(new FinanceScheduledTransaction(
				tenantContext.getTenantId(), v.name, command.type(), v.amount, postingService.tenantCurrency(),
				v.fromAccountId, v.toAccountId, v.categoryId, v.note, command.scheduleType(), v.scheduledDate,
				v.dayOfMonth, today, actorId));
		materialize(schedule, today);
		auditLogApi.record(new AuditLogEntry(actorId, "finance_schedule.created", TARGET_SCHEDULE, schedule.getId(),
				null, metadata(schedule)));
		return views(List.of(schedule)).get(0);
	}

	/** Changes future occurrences only; POSTED/SKIPPED/FAILED history is untouched. */
	@Transactional
	public ScheduleView update(UUID scheduleId, ScheduleCommand command) {
		permissionCheckService.requireCapability(Capability.FINANCE_MANAGE_SCHEDULES);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceScheduledTransaction schedule = load(scheduleId);
		if (schedule.getStatus() != ScheduleStatus.ACTIVE) {
			throw new ConflictException("Only an active schedule can be edited");
		}
		if (command.type() != null && command.type() != schedule.getType()) {
			throw new FieldValidationException("Type cannot change",
					List.of(new FieldError("type", "cancel this schedule and create a new one to change its type")));
		}
		Validated v = validate(new ScheduleCommand(command.name(), schedule.getType(), command.amount(),
				command.currency(), command.accountId(), command.fromAccountId(), command.toAccountId(),
				command.categoryId(), command.note(), command.scheduleType(), command.scheduledDate(),
				command.dayOfMonth()), schedule);
		Map<String, Object> before = metadata(schedule);
		schedule.update(v.name, v.amount, v.fromAccountId, v.toAccountId, v.categoryId, v.note,
				command.scheduleType(), v.scheduledDate, v.dayOfMonth, actorId);
		scheduleRepository.saveAndFlush(schedule);
		materialize(schedule, periodResolver.today());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", before);
		metadata.put("after", metadata(schedule));
		auditLogApi.record(new AuditLogEntry(actorId, "finance_schedule.updated", TARGET_SCHEDULE, schedule.getId(),
				null, metadata));
		return views(List.of(schedule)).get(0);
	}

	/** Cancels future due dates; completed occurrences and their postings stay as they are. */
	@Transactional
	public ScheduleView cancel(UUID scheduleId) {
		permissionCheckService.requireCapability(Capability.FINANCE_MANAGE_SCHEDULES);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceScheduledTransaction schedule = load(scheduleId);
		if (schedule.getStatus() == ScheduleStatus.CANCELLED) {
			throw new ConflictException("This schedule is already cancelled");
		}
		Instant now = Instant.now();
		schedule.cancel(actorId, now);
		scheduleRepository.saveAndFlush(schedule);
		int skipped = 0;
		for (FinanceScheduledOccurrence occurrence : occurrenceRepository.findBySchedule(schedule.getId())) {
			if (occurrence.isOpen()) {
				occurrence.markSkipped(actorId, now);
				occurrenceRepository.saveAndFlush(occurrence);
				skipped++;
			}
		}
		Map<String, Object> metadata = metadata(schedule);
		metadata.put("openOccurrencesSkipped", skipped);
		auditLogApi.record(new AuditLogEntry(actorId, "finance_schedule.cancelled", TARGET_SCHEDULE,
				schedule.getId(), null, metadata));
		return views(List.of(schedule)).get(0);
	}

	/**
	 * Records one due occurrence as a real posting. Idempotent: the occurrence
	 * row is locked and the unique (tenant, schedule, due date) + the posting's
	 * source link make a second attempt a 409. Validation failures are recorded
	 * on the occurrence (FAILED + error) and reported to the caller.
	 */
	@Transactional
	public OccurrenceView postOccurrence(UUID occurrenceId) {
		permissionCheckService.requireCapability(Capability.FINANCE_RECORD_TRANSACTION);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceScheduledOccurrence occurrence = loadOccurrence(occurrenceId);
		entityManager.refresh(occurrence, LockModeType.PESSIMISTIC_WRITE);
		if (!occurrence.isOpen()) {
			throw new ConflictException("This occurrence is already " + occurrence.getStatus().name().toLowerCase());
		}
		FinanceScheduledTransaction schedule = load(occurrence.getScheduleId());
		if (schedule.getStatus() == ScheduleStatus.CANCELLED) {
			throw new ConflictException("This schedule was cancelled");
		}
		if (occurrence.getDueDate().isAfter(periodResolver.today())) {
			throw new ConflictException("This occurrence is not due yet");
		}
		Instant now = Instant.now();
		FinanceTransaction posted;
		try {
			// Validate before writing anything so a failure can be recorded on the occurrence
			// without aborting the database transaction.
			preflight(schedule);
			posted = switch (schedule.getType()) {
				case INCOME -> postingService.postIncome(schedule.getToAccountId(), schedule.getAmount(),
						occurrence.getDueDate(), noteFor(schedule), schedule.getCategoryId(), SourceType.SCHEDULE,
						occurrence.getId(), null, actorId);
				case EXPENSE -> transactionService.postExpenseWithRecord(schedule.getFromAccountId(),
						schedule.getAmount(), occurrence.getDueDate(), noteFor(schedule), schedule.getCategoryId(),
						SourceType.SCHEDULE, occurrence.getId(), null, actorId);
				case TRANSFER -> postingService.postTransfer(schedule.getFromAccountId(), schedule.getToAccountId(),
						schedule.getAmount(), occurrence.getDueDate(), noteFor(schedule), SourceType.SCHEDULE,
						occurrence.getId(), null, actorId);
				default -> throw new IllegalStateException("Unsupported scheduled type " + schedule.getType());
			};
		}
		catch (FieldValidationException | NotFoundException failure) {
			// Recorded, not thrown: throwing would roll the FAILED mark back with the transaction.
			// The controller answers 409 for a FAILED result.
			occurrence.markFailed(failure.getMessage(), actorId, now);
			occurrenceRepository.saveAndFlush(occurrence);
			auditLogApi.record(new AuditLogEntry(actorId, "finance_schedule.occurrence_failed", TARGET_SCHEDULE,
					schedule.getId(), failure.getMessage(), occurrenceMetadata(occurrence)));
			return OccurrenceView.of(occurrence);
		}
		occurrence.markPosted(posted.getId(), actorId, now);
		occurrenceRepository.saveAndFlush(occurrence);
		if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
			schedule.complete();
			scheduleRepository.saveAndFlush(schedule);
		}
		auditLogApi.record(new AuditLogEntry(actorId, "finance_schedule.occurrence_posted", TARGET_SCHEDULE,
				schedule.getId(), null, occurrenceMetadata(occurrence)));
		return OccurrenceView.of(occurrence);
	}

	@Transactional
	public OccurrenceView skipOccurrence(UUID occurrenceId) {
		permissionCheckService.requireCapability(Capability.FINANCE_MANAGE_SCHEDULES);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		FinanceScheduledOccurrence occurrence = loadOccurrence(occurrenceId);
		entityManager.refresh(occurrence, LockModeType.PESSIMISTIC_WRITE);
		if (!occurrence.isOpen()) {
			throw new ConflictException("This occurrence is already " + occurrence.getStatus().name().toLowerCase());
		}
		occurrence.markSkipped(actorId, Instant.now());
		occurrenceRepository.saveAndFlush(occurrence);
		FinanceScheduledTransaction schedule = load(occurrence.getScheduleId());
		if (schedule.getScheduleType() == ScheduleType.ONE_TIME && schedule.getStatus() == ScheduleStatus.ACTIVE) {
			schedule.complete();
			scheduleRepository.saveAndFlush(schedule);
		}
		auditLogApi.record(new AuditLogEntry(actorId, "finance_schedule.occurrence_skipped", TARGET_SCHEDULE,
				schedule.getId(), null, occurrenceMetadata(occurrence)));
		return OccurrenceView.of(occurrence);
	}

	// ------------------------------------------------------------------
	// Materialisation (job + list refresh). No permission check: called inside
	// an already-authorised read or by the job with the tenant context set.
	// ------------------------------------------------------------------

	@Transactional
	public int materializeDueForCurrentTenant() {
		LocalDate today = periodResolver.today();
		int created = 0;
		for (FinanceScheduledTransaction schedule : scheduleRepository.findActive()) {
			created += materialize(schedule, today);
		}
		return created;
	}

	int materialize(FinanceScheduledTransaction schedule, LocalDate today) {
		if (schedule.getStatus() != ScheduleStatus.ACTIVE) {
			return 0;
		}
		int created = 0;
		for (LocalDate due : dueDatesUpTo(schedule, today)) {
			if (occurrenceRepository.findByScheduleAndDueDate(schedule.getId(), due).isPresent()) {
				continue;
			}
			try {
				occurrenceRepository.saveAndFlush(
						new FinanceScheduledOccurrence(tenantContext.getTenantId(), schedule.getId(), due));
				created++;
			}
			catch (DataIntegrityViolationException raced) {
				// A concurrent run materialised the same due date: the unique constraint did its job.
				log.debug("finance.schedule_occurrence_raced scheduleId={} due={}", schedule.getId(), due);
			}
		}
		return created;
	}

	/** Every due date from startsOn to today (inclusive), skipping months without the day. */
	static List<LocalDate> dueDatesUpTo(FinanceScheduledTransaction schedule, LocalDate today) {
		List<LocalDate> dates = new ArrayList<>();
		if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
			if (!schedule.getScheduledDate().isAfter(today)) {
				dates.add(schedule.getScheduledDate());
			}
			return dates;
		}
		YearMonth month = YearMonth.from(schedule.getStartsOn());
		YearMonth last = YearMonth.from(today);
		while (!month.isAfter(last)) {
			LocalDate due = schedule.dueDateIn(month);
			if (due != null && !due.isBefore(schedule.getStartsOn()) && !due.isAfter(today)) {
				dates.add(due);
			}
			month = month.plusMonths(1);
		}
		return dates;
	}

	/** The next date on/after {@code from} the schedule will fall due, or null when none. */
	static LocalDate nextDueDate(FinanceScheduledTransaction schedule, LocalDate from) {
		if (schedule.getStatus() != ScheduleStatus.ACTIVE) {
			return null;
		}
		if (schedule.getScheduleType() == ScheduleType.ONE_TIME) {
			return schedule.getScheduledDate().isBefore(from) ? null : schedule.getScheduledDate();
		}
		YearMonth month = YearMonth.from(from);
		for (int i = 0; i < 24; i++) {
			LocalDate due = schedule.dueDateIn(month);
			if (due != null && !due.isBefore(from) && !due.isBefore(schedule.getStartsOn())) {
				return due;
			}
			month = month.plusMonths(1);
		}
		return null;
	}

	/** Month names a MONTHLY schedule skips because the day does not exist (e.g. 31 -> Feb, Apr ...). */
	static List<String> skippedMonths(FinanceScheduledTransaction schedule) {
		if (schedule.getScheduleType() != ScheduleType.MONTHLY || schedule.getDayOfMonth() <= 28) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		for (Month m : Month.values()) {
			// Leap-year February still has no 30th/31st; the 29th is skipped only in common years.
			int length = m == Month.FEBRUARY ? 28 : m.length(false);
			if (schedule.getDayOfMonth() > length) {
				names.add(m.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
						+ (m == Month.FEBRUARY && schedule.getDayOfMonth() == 29 ? " (common years)" : ""));
			}
		}
		return names;
	}

	// ------------------------------------------------------------------
	// Validation / helpers.
	// ------------------------------------------------------------------

	private record Validated(String name, BigDecimal amount, UUID fromAccountId, UUID toAccountId, UUID categoryId,
			String note, LocalDate scheduledDate, Integer dayOfMonth) {

	}

	private Validated validate(ScheduleCommand command, FinanceScheduledTransaction existing) {
		if (command.name() == null || command.name().isBlank()) {
			throw new FieldValidationException("Invalid name", List.of(new FieldError("name", "is required")));
		}
		String name = command.name().trim();
		if (name.length() > MAX_NAME) {
			throw new FieldValidationException("Invalid name",
					List.of(new FieldError("name", "must be at most " + MAX_NAME + " characters")));
		}
		TransactionType type = command.type();
		if (type == null || !(type == TransactionType.INCOME || type == TransactionType.EXPENSE
				|| type == TransactionType.TRANSFER)) {
			throw new FieldValidationException("Invalid type",
					List.of(new FieldError("type", "must be INCOME, EXPENSE or TRANSFER")));
		}
		BigDecimal amount = FinancePostingService.normalizeAmount(command.amount(), "amount", false);
		postingService.requireCurrency(command.currency(), "currency");
		String note = FinancePostingService.optionalText(command.note(), "note");
		UUID from = null;
		UUID to = null;
		UUID categoryId = null;
		switch (type) {
			case INCOME -> {
				to = postingService.requireActiveAccount(firstNonNull(command.toAccountId(), command.accountId()),
						"accountId").getId();
				categoryId = postingService.requireActiveCategory(command.categoryId(), "categoryId").getId();
			}
			case EXPENSE -> {
				from = postingService
					.requireActiveAccount(firstNonNull(command.fromAccountId(), command.accountId()), "accountId")
					.getId();
				categoryId = postingService.requireActiveCategory(command.categoryId(), "categoryId").getId();
			}
			case TRANSFER -> {
				from = postingService.requireActiveAccount(command.fromAccountId(), "fromAccountId").getId();
				to = postingService.requireActiveAccount(command.toAccountId(), "toAccountId").getId();
				if (from.equals(to)) {
					throw new FieldValidationException("Transfer needs two different accounts",
							List.of(new FieldError("toAccountId", "must differ from the source account")));
				}
				if (command.categoryId() != null) {
					throw new FieldValidationException("Category not applicable",
							List.of(new FieldError("categoryId", "a transfer has no category")));
				}
			}
			default -> throw new IllegalStateException("unreachable");
		}
		if (command.scheduleType() == null) {
			throw new FieldValidationException("Invalid schedule type",
					List.of(new FieldError("scheduleType", "must be ONE_TIME or MONTHLY")));
		}
		LocalDate scheduledDate = null;
		Integer dayOfMonth = null;
		if (command.scheduleType() == ScheduleType.ONE_TIME) {
			if (command.scheduledDate() == null) {
				throw new FieldValidationException("Scheduled date is required",
						List.of(new FieldError("scheduledDate", "is required for a one-time schedule")));
			}
			scheduledDate = command.scheduledDate();
		}
		else {
			if (command.dayOfMonth() == null || command.dayOfMonth() < 1 || command.dayOfMonth() > 31) {
				throw new FieldValidationException("Invalid day of month",
						List.of(new FieldError("dayOfMonth", "must be between 1 and 31")));
			}
			dayOfMonth = command.dayOfMonth();
		}
		return new Validated(name, amount, from, to, categoryId, note, scheduledDate, dayOfMonth);
	}

	private static UUID firstNonNull(UUID a, UUID b) {
		return a != null ? a : b;
	}

	private void preflight(FinanceScheduledTransaction schedule) {
		if (schedule.getFromAccountId() != null) {
			postingService.requireActiveAccount(schedule.getFromAccountId(), "fromAccountId");
		}
		if (schedule.getToAccountId() != null) {
			postingService.requireActiveAccount(schedule.getToAccountId(), "toAccountId");
		}
		if (schedule.getCategoryId() != null) {
			postingService.requireActiveCategory(schedule.getCategoryId(), "categoryId");
		}
	}

	private static String noteFor(FinanceScheduledTransaction schedule) {
		return schedule.getNote() == null ? schedule.getName() : schedule.getName() + " - " + schedule.getNote();
	}

	private FinanceScheduledTransaction load(UUID scheduleId) {
		return scheduleRepository.findById(scheduleId)
			.orElseThrow(() -> new NotFoundException("Scheduled transaction not found"));
	}

	private FinanceScheduledOccurrence loadOccurrence(UUID occurrenceId) {
		return occurrenceRepository.findById(occurrenceId)
			.orElseThrow(() -> new NotFoundException("Scheduled occurrence not found"));
	}

	private static Map<String, Object> metadata(FinanceScheduledTransaction s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.getName());
		m.put("type", s.getType().name());
		m.put("amount", s.getAmount().toPlainString());
		m.put("currency", s.getCurrency());
		m.put("scheduleType", s.getScheduleType().name());
		m.put("status", s.getStatus().name());
		if (s.getScheduledDate() != null) {
			m.put("scheduledDate", s.getScheduledDate().toString());
		}
		if (s.getDayOfMonth() != null) {
			m.put("dayOfMonth", s.getDayOfMonth());
		}
		if (s.getFromAccountId() != null) {
			m.put("fromAccountId", s.getFromAccountId().toString());
		}
		if (s.getToAccountId() != null) {
			m.put("toAccountId", s.getToAccountId().toString());
		}
		return m;
	}

	private static Map<String, Object> occurrenceMetadata(FinanceScheduledOccurrence o) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("occurrenceId", o.getId().toString());
		m.put("dueDate", o.getDueDate().toString());
		m.put("status", o.getStatus().name());
		m.put("attempts", o.getAttempts());
		if (o.getTransactionId() != null) {
			m.put("transactionId", o.getTransactionId().toString());
		}
		return m;
	}

	private List<ScheduleView> views(List<FinanceScheduledTransaction> schedules) {
		if (schedules.isEmpty()) {
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
		LocalDate today = periodResolver.today();
		return schedules.stream().map(s -> {
			List<FinanceScheduledOccurrence> occurrences = occurrenceRepository.findBySchedule(s.getId());
			FinanceScheduledOccurrence latestExecuted = occurrences.stream()
				.filter(o -> o.getExecutedAt() != null)
				.max((a, b) -> a.getExecutedAt().compareTo(b.getExecutedAt()))
				.orElse(null);
			long open = occurrences.stream().filter(FinanceScheduledOccurrence::isOpen).count();
			LocalDate nextDue = occurrences.stream()
				.filter(FinanceScheduledOccurrence::isOpen)
				.map(FinanceScheduledOccurrence::getDueDate)
				.min(LocalDate::compareTo)
				.orElseGet(() -> nextDueDate(s, today.plusDays(1)));
			Map<OccurrenceStatus, Long> counts = occurrences.stream()
				.collect(Collectors.groupingBy(FinanceScheduledOccurrence::getStatus, Collectors.counting()));
			return new ScheduleView(s.getId(), s.getName(), s.getType(), s.getAmount(), s.getCurrency(),
					s.getFromAccountId(), accountNames.get(s.getFromAccountId()), s.getToAccountId(),
					accountNames.get(s.getToAccountId()), s.getCategoryId(), categoryNames.get(s.getCategoryId()),
					s.getNote(), s.getScheduleType(), s.getScheduledDate(), s.getDayOfMonth(), s.getStartsOn(),
					s.getStatus(), nextDue, open, counts.getOrDefault(OccurrenceStatus.POSTED, 0L),
					counts.getOrDefault(OccurrenceStatus.FAILED, 0L), counts.getOrDefault(OccurrenceStatus.SKIPPED, 0L),
					latestExecuted == null ? null : OccurrenceView.of(latestExecuted), skippedMonths(s),
					s.getCreatedAt(), s.getUpdatedAt(), s.getCancelledAt());
		}).toList();
	}

	public record ScheduleView(UUID id, String name, TransactionType type, BigDecimal amount, String currency,
			UUID fromAccountId, String fromAccountName, UUID toAccountId, String toAccountName, UUID categoryId,
			String categoryName, String note, ScheduleType scheduleType, LocalDate scheduledDate, Integer dayOfMonth,
			LocalDate startsOn, ScheduleStatus status, LocalDate nextDueDate, long openOccurrences,
			long postedOccurrences, long failedOccurrences, long skippedOccurrences, OccurrenceView lastExecution,
			List<String> skippedMonths, Instant createdAt, Instant updatedAt, Instant cancelledAt) {

	}

	public record OccurrenceView(UUID id, UUID scheduleId, LocalDate dueDate, OccurrenceStatus status,
			UUID transactionId, int attempts, String lastError, Instant executedAt, UUID executedBy) {

		static OccurrenceView of(FinanceScheduledOccurrence o) {
			return new OccurrenceView(o.getId(), o.getScheduleId(), o.getDueDate(), o.getStatus(),
					o.getTransactionId(), o.getAttempts(), o.getLastError(), o.getExecutedAt(), o.getExecutedBy());
		}

	}

}

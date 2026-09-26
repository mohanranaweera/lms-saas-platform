package com.lms.ledgersettlementmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.money.PlatformCurrency;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CourseSummary;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueApi;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueEntry;
import com.lms.ledgersettlementmanagement.domain.TeacherRevenueShareRate;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlement;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementItem;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementKind;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementStatus;
import com.lms.ledgersettlementmanagement.repository.TeacherRevenueShareRateRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementItemRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementRepository;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 7 teacher settlement FOUNDATION (PAR-24-02/03/04, PAR-23-03) - see
 * wave-07-plan.md §2/§10 for scope and the judgment calls it encodes.
 *
 * <p>What this does: computes a per-teacher statement for a fully closed
 * period from the authoritative ledger ({@link LedgerRevenueApi}), at the
 * teacher's revenue-share rate in effect for that period, and STORES the
 * figures (never recomputed). Tracks {@code CALCULATED -> PAID} and appends
 * signed ADJUSTMENT rows for corrections.
 *
 * <p>What this deliberately does NOT do: write, mutate, or delete any {@code
 * ledger_entry} (no new ledger entry type - change-controlled); move money;
 * compute platform commission or gateway fees; split payments.
 *
 * <p>Idempotency is schema-enforced (V55): one REGULAR row per (teacher,
 * period) and one settlement item per ledger entry, ever. The pre-checks here
 * are friendly 409s; a concurrent race still hits the constraints, which the
 * global handler maps to 409 with the whole transaction rolled back.
 *
 * <p>Authorization: {@code FINANCE_EXPENSES} {@code VIEW} for reads, {@code
 * CREATE_EDIT} for writes (tenant Finance triggers teacher settlement -
 * judgment call 2). Every write is audit-logged in the same transaction
 * ({@code .claude/rules/security.md}: settlement amount changes).
 */
@Service
@Transactional
public class TeacherSettlementService {

	static final String TARGET_SETTLEMENT = "teacher_settlement";

	static final String TARGET_RATE = "teacher_revenue_share_rate";

	private static final long MAX_PERIOD_DAYS = 366;

	private static final BigDecimal HUNDRED = new BigDecimal("100");

	private final TeacherRevenueShareRateRepository rateRepository;

	private final TeacherSettlementRepository settlementRepository;

	private final TeacherSettlementItemRepository itemRepository;

	private final LedgerRevenueApi ledgerRevenueApi;

	private final CourseLookupApi courseLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final TeacherLookupApi teacherLookupApi;

	private final EntityManager entityManager;

	public TeacherSettlementService(TeacherRevenueShareRateRepository rateRepository,
			TeacherSettlementRepository settlementRepository, TeacherSettlementItemRepository itemRepository,
			LedgerRevenueApi ledgerRevenueApi, CourseLookupApi courseLookupApi,
			UserProvisioningApi userProvisioningApi, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, TenantConfigApi tenantConfigApi, TenantContext tenantContext,
			TeacherLookupApi teacherLookupApi, EntityManager entityManager) {
		this.teacherLookupApi = teacherLookupApi;
		this.entityManager = entityManager;
		this.rateRepository = rateRepository;
		this.settlementRepository = settlementRepository;
		this.itemRepository = itemRepository;
		this.ledgerRevenueApi = ledgerRevenueApi;
		this.courseLookupApi = courseLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	/**
	 * Teachers a settlement can be calculated for (Finance screens' picker).
	 * Finance roles hold no {@code TEACHERS} grant, so {@code GET
	 * /api/v1/teachers} is unavailable to them; this read exposes only
	 * id/name/email, gated on {@code FINANCE_EXPENSES}/{@code VIEW}. The id is
	 * the {@code tenant_user} id that {@code course.teacher_id} stores.
	 */
	@Transactional(readOnly = true)
	public List<TeacherLookupApi.TeacherSummary> listPayees() {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		return teacherLookupApi.listTeachers();
	}

	// ------------------------------------------------------------------
	// Revenue-share rates (append-only, effective-dated).
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<RateView> listRates(UUID teacherId) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		List<TeacherRevenueShareRate> rates = rateRepository.findHistory(teacherId);
		Map<UUID, String> emails = emailsOf(rates.stream().map(TeacherRevenueShareRate::getTeacherId).toList());
		return rates.stream().map(rate -> RateView.from(rate, emails.get(rate.getTeacherId()))).toList();
	}

	public RateView addRate(UUID teacherId, BigDecimal sharePercent, LocalDate effectiveFrom) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		TenantUserSummary teacher = requireTeacher(teacherId);
		BigDecimal percent = normalizePercent(sharePercent);
		if (rateRepository.existsForTeacherOn(teacherId, effectiveFrom)) {
			throw new ConflictException("A revenue share rate already starts on this date for this teacher");
		}
		TeacherRevenueShareRate saved = rateRepository.save(
				new TeacherRevenueShareRate(tenantContext.getTenantId(), teacherId, percent, effectiveFrom, actorId));
		auditLogApi.record(new AuditLogEntry(actorId, "teacher_share_rate.created", TARGET_RATE, saved.getId(), null,
				Map.of("teacherId", teacherId.toString(), "sharePercent", percent.toPlainString(), "effectiveFrom",
						effectiveFrom.toString())));
		return RateView.from(saved, teacher.email());
	}

	// ------------------------------------------------------------------
	// Settlements.
	// ------------------------------------------------------------------

	public SettlementDetail calculate(UUID teacherId, LocalDate periodStart, LocalDate periodEnd) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		requireTeacher(teacherId);
		ZoneId zone = tenantZone();
		validatePeriod(periodStart, periodEnd, LocalDate.now(zone));

		if (settlementRepository.existsOverlappingRegular(teacherId, periodStart, periodEnd)) {
			throw new ConflictException(
					"A settlement already exists for this teacher covering part or all of this period");
		}
		TeacherRevenueShareRate rate = rateRepository.findEffectiveOn(teacherId, periodStart)
			.orElseThrow(() -> new FieldValidationException("No revenue share rate is in effect for this period",
					List.of(new FieldError("teacherId",
							"no revenue share rate is effective on " + periodStart + " for this teacher"))));
		if (rateRepository.existsStartingWithin(teacherId, periodStart, periodEnd)) {
			throw new FieldValidationException("The revenue share rate changes within this period",
					List.of(new FieldError("periodEnd",
							"a different rate takes effect inside this period - settle each rate's dates separately")));
		}

		List<LedgerRevenueEntry> candidates = teacherEntries(teacherId,
				ledgerRevenueApi.findRevenueEntries(periodStart.atStartOfDay(zone).toInstant(),
						periodEnd.plusDays(1).atStartOfDay(zone).toInstant()));
		Set<UUID> alreadySettled = itemRepository
			.findSettledLedgerEntryIds(candidates.stream().map(LedgerRevenueEntry::entryId).toList());
		List<LedgerRevenueEntry> entries = candidates.stream()
			.filter(entry -> !alreadySettled.contains(entry.entryId()))
			.toList();
		if (entries.isEmpty()) {
			throw new ConflictException("There are no unsettled ledger entries for this teacher in this period");
		}

		BigDecimal gross = BigDecimal.ZERO.setScale(2);
		BigDecimal refunds = BigDecimal.ZERO.setScale(2);
		for (LedgerRevenueEntry entry : entries) {
			if (entry.amount().signum() > 0) {
				gross = gross.add(entry.amount());
			}
			else {
				refunds = refunds.add(entry.amount().abs());
			}
		}
		BigDecimal shareAmount = shareOf(gross.subtract(refunds), rate.getSharePercent());

		TeacherSettlement settlement = settlementRepository.saveAndFlush(TeacherSettlement.regular(
				tenantContext.getTenantId(), teacherId, periodStart, periodEnd, gross, refunds,
				rate.getSharePercent(), rate.getId(), shareAmount, PlatformCurrency.DEFAULT_CURRENCY, actorId,
				Instant.now()));
		itemRepository.saveAllAndFlush(entries.stream()
			.map(entry -> new TeacherSettlementItem(tenantContext.getTenantId(), settlement.getId(), entry.entryId(),
					entry.courseId(), entry.amount()))
			.toList());

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("teacherId", teacherId.toString());
		metadata.put("periodStart", periodStart.toString());
		metadata.put("periodEnd", periodEnd.toString());
		metadata.put("grossAmount", gross.toPlainString());
		metadata.put("refundAmount", refunds.toPlainString());
		metadata.put("sharePercent", rate.getSharePercent().toPlainString());
		metadata.put("shareAmount", shareAmount.toPlainString());
		metadata.put("ledgerEntryCount", entries.size());
		auditLogApi.record(new AuditLogEntry(actorId, "teacher_settlement.calculated", TARGET_SETTLEMENT,
				settlement.getId(), null, metadata));
		return detailOf(settlement);
	}

	@Transactional(readOnly = true)
	public Page<SettlementView> list(UUID teacherId, TeacherSettlementStatus status, TeacherSettlementKind kind,
			Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		Page<TeacherSettlement> page = settlementRepository.search(teacherId, status, kind, pageable);
		return new PageImpl<>(toViews(page.getContent()), pageable, page.getTotalElements());
	}

	@Transactional(readOnly = true)
	public SettlementDetail get(UUID settlementId) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		return detailOf(load(settlementId));
	}

	public SettlementDetail markPaid(UUID settlementId, String payoutReference) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		TeacherSettlement settlement = load(settlementId);
		// Row lock + re-read (SELECT ... FOR UPDATE) so two concurrent
		// mark-paid calls serialize: the second observes PAID and gets 409
		// instead of silently overwriting paid_by/payout_reference (no
		// @Version column exists on this table). The entity was already
		// loaded through the tenant-scoped repository above.
		entityManager.refresh(settlement, LockModeType.PESSIMISTIC_WRITE);
		if (settlement.isPaid()) {
			throw new ConflictException("Settlement is already marked paid");
		}
		String reference = (payoutReference == null || payoutReference.isBlank()) ? null : payoutReference.trim();
		settlement.markPaid(actorId, reference, Instant.now());
		settlementRepository.save(settlement);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("previousStatus", TeacherSettlementStatus.CALCULATED.name());
		metadata.put("newStatus", TeacherSettlementStatus.PAID.name());
		metadata.put("shareAmount", settlement.getShareAmount().toPlainString());
		if (reference != null) {
			metadata.put("payoutReference", reference);
		}
		auditLogApi.record(new AuditLogEntry(actorId, "teacher_settlement.marked_paid", TARGET_SETTLEMENT,
				settlement.getId(), null, metadata));
		return detailOf(settlement);
	}

	public SettlementDetail adjust(UUID settlementId, BigDecimal amount, String reason) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		TeacherSettlement original = load(settlementId);
		if (original.getKind() != TeacherSettlementKind.REGULAR) {
			throw new ConflictException("Adjustments must reference the original (regular) settlement");
		}
		BigDecimal adjustment = normalizeAdjustment(amount);
		BigDecimal before = effectiveTotal(original, settlementRepository.findAdjustmentsOf(original.getId()));
		String trimmedReason = reason.trim();
		TeacherSettlement saved = settlementRepository
			.save(TeacherSettlement.adjustment(original, adjustment, trimmedReason, actorId, Instant.now()));
		BigDecimal after = before.add(adjustment);
		auditLogApi.record(new AuditLogEntry(actorId, "teacher_settlement.adjusted", TARGET_SETTLEMENT,
				original.getId(), trimmedReason,
				Map.of("adjustmentId", saved.getId().toString(), "adjustmentAmount", adjustment.toPlainString(),
						"totalBefore", before.toPlainString(), "totalAfter", after.toPlainString())));
		return detailOf(original);
	}

	// ------------------------------------------------------------------
	// Internals.
	// ------------------------------------------------------------------

	/** Ledger entries for courses currently taught by {@code teacherId}, excluding zero-amount rows. */
	private List<LedgerRevenueEntry> teacherEntries(UUID teacherId, List<LedgerRevenueEntry> entries) {
		Set<UUID> courseIds = entries.stream()
			.map(LedgerRevenueEntry::courseId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		if (courseIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, UUID> teacherByCourse = courseLookupApi.getTeacherIdsByCourseId(courseIds);
		return entries.stream()
			.filter(entry -> entry.courseId() != null && teacherId.equals(teacherByCourse.get(entry.courseId())))
			.filter(entry -> entry.amount().signum() != 0)
			.toList();
	}

	static BigDecimal shareOf(BigDecimal net, BigDecimal sharePercent) {
		return net.multiply(sharePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
	}

	private static BigDecimal effectiveTotal(TeacherSettlement original, List<TeacherSettlement> adjustments) {
		BigDecimal total = original.getShareAmount();
		for (TeacherSettlement adjustment : adjustments) {
			total = total.add(adjustment.getShareAmount());
		}
		return total;
	}

	private static void validatePeriod(LocalDate start, LocalDate end, LocalDate today) {
		if (start.isAfter(end)) {
			throw new FieldValidationException("Invalid period",
					List.of(new FieldError("periodStart", "must be on or before periodEnd")));
		}
		if (!end.isBefore(today)) {
			throw new FieldValidationException("Period is not closed",
					List.of(new FieldError("periodEnd", "must be before today - only closed periods can be settled")));
		}
		if (ChronoUnit.DAYS.between(start, end) >= MAX_PERIOD_DAYS) {
			throw new FieldValidationException("Invalid period",
					List.of(new FieldError("periodEnd", "period must not exceed " + MAX_PERIOD_DAYS + " days")));
		}
	}

	private static BigDecimal normalizePercent(BigDecimal percent) {
		if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
			throw new FieldValidationException("Invalid share percent",
					List.of(new FieldError("sharePercent", "must be between 0 and 100")));
		}
		try {
			return percent.setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException e) {
			throw new FieldValidationException("Invalid share percent",
					List.of(new FieldError("sharePercent", "must have at most 2 decimal places")));
		}
	}

	private static BigDecimal normalizeAdjustment(BigDecimal amount) {
		if (amount == null || amount.signum() == 0) {
			throw new FieldValidationException("Invalid adjustment",
					List.of(new FieldError("amount", "must not be zero")));
		}
		try {
			return amount.setScale(2, RoundingMode.UNNECESSARY);
		}
		catch (ArithmeticException e) {
			throw new FieldValidationException("Invalid adjustment",
					List.of(new FieldError("amount", "must have at most 2 decimal places")));
		}
	}

	private TenantUserSummary requireTeacher(UUID teacherId) {
		return userProvisioningApi.findTenantUserSummaries(List.of(teacherId))
			.stream()
			.filter(summary -> summary.userId().equals(teacherId) && "TEACHER".equals(summary.roleCode()))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Teacher not found"));
	}

	private TeacherSettlement load(UUID settlementId) {
		return settlementRepository.findById(settlementId)
			.orElseThrow(() -> new NotFoundException("Settlement not found"));
	}

	private ZoneId tenantZone() {
		Object configured = tenantConfigApi
			.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL, "default_timezone")
			.orElse(null);
		if (configured instanceof String zoneName) {
			try {
				return ZoneId.of(zoneName);
			}
			catch (DateTimeException ignored) {
				// Registry-validated; UTC fallback is defensive only.
			}
		}
		return ZoneId.of("UTC");
	}

	private Map<UUID, String> emailsOf(Collection<UUID> userIds) {
		Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
		if (ids.isEmpty()) {
			return Map.of();
		}
		return userProvisioningApi.findTenantUserSummaries(ids)
			.stream()
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
	}

	private List<SettlementView> toViews(List<TeacherSettlement> settlements) {
		if (settlements.isEmpty()) {
			return List.of();
		}
		List<UUID> regularIds = settlements.stream()
			.filter(s -> s.getKind() == TeacherSettlementKind.REGULAR)
			.map(TeacherSettlement::getId)
			.toList();
		Map<UUID, List<TeacherSettlement>> adjustmentsByOriginal = settlementRepository
			.findAdjustmentsOfAny(regularIds)
			.stream()
			.collect(Collectors.groupingBy(TeacherSettlement::getAdjustsSettlementId));
		Set<UUID> userIds = new HashSet<>();
		for (TeacherSettlement s : settlements) {
			userIds.add(s.getTeacherId());
			userIds.add(s.getCalculatedBy());
			if (s.getPaidBy() != null) {
				userIds.add(s.getPaidBy());
			}
		}
		Map<UUID, String> emails = emailsOf(userIds);
		return settlements.stream()
			.map(s -> SettlementView.from(s, emails,
					s.getKind() == TeacherSettlementKind.REGULAR
							? effectiveTotal(s, adjustmentsByOriginal.getOrDefault(s.getId(), List.of()))
							: s.getShareAmount()))
			.toList();
	}

	private SettlementDetail detailOf(TeacherSettlement settlement) {
		List<TeacherSettlement> adjustments = settlement.getKind() == TeacherSettlementKind.REGULAR
				? settlementRepository.findAdjustmentsOf(settlement.getId()) : List.of();
		List<TeacherSettlement> all = new ArrayList<>();
		all.add(settlement);
		all.addAll(adjustments);
		List<SettlementView> views = toViews(all);

		List<TeacherSettlementItem> items = settlement.getKind() == TeacherSettlementKind.REGULAR
				? itemRepository.findBySettlementId(settlement.getId()) : List.of();
		Map<UUID, List<TeacherSettlementItem>> byCourse = items.stream()
			.collect(Collectors.groupingBy(TeacherSettlementItem::getCourseId, LinkedHashMap::new,
					Collectors.toList()));
		Map<UUID, String> titles = byCourse.isEmpty() ? Map.of()
				: courseLookupApi.getCourseSummaries(byCourse.keySet())
					.stream()
					.collect(Collectors.toMap(CourseSummary::id, CourseSummary::name, (a, b) -> a));
		List<CourseBreakdown> courses = byCourse.entrySet().stream().map(entry -> {
			BigDecimal gross = BigDecimal.ZERO.setScale(2);
			BigDecimal refunds = BigDecimal.ZERO.setScale(2);
			for (TeacherSettlementItem item : entry.getValue()) {
				if (item.getAmount().signum() > 0) {
					gross = gross.add(item.getAmount());
				}
				else {
					refunds = refunds.add(item.getAmount().abs());
				}
			}
			return new CourseBreakdown(entry.getKey(), titles.get(entry.getKey()), gross, refunds,
					gross.subtract(refunds), entry.getValue().size());
		}).sorted(Comparator.comparing(CourseBreakdown::net).reversed()).toList();
		return new SettlementDetail(views.get(0), courses, views.subList(1, views.size()));
	}

	// ------------------------------------------------------------------
	// Views.
	// ------------------------------------------------------------------

	public record RateView(UUID id, UUID teacherId, String teacherEmail, BigDecimal sharePercent,
			LocalDate effectiveFrom, UUID createdBy, Instant createdAt) {

		static RateView from(TeacherRevenueShareRate rate, String teacherEmail) {
			return new RateView(rate.getId(), rate.getTeacherId(), teacherEmail, rate.getSharePercent(),
					rate.getEffectiveFrom(), rate.getCreatedBy(), rate.getCreatedAt());
		}

	}

	/**
	 * {@code effectiveShareAmount} = {@code shareAmount} + every adjustment's
	 * amount (REGULAR rows only; for an ADJUSTMENT row it is its own amount).
	 */
	public record SettlementView(UUID id, TeacherSettlementKind kind, UUID teacherId, String teacherEmail,
			LocalDate periodStart, LocalDate periodEnd, BigDecimal grossAmount, BigDecimal refundAmount,
			BigDecimal netAmount, BigDecimal sharePercent, BigDecimal shareAmount, BigDecimal effectiveShareAmount,
			String currency, UUID adjustsSettlementId, String reason, TeacherSettlementStatus status,
			UUID calculatedBy, String calculatedByEmail, Instant calculatedAt, UUID paidBy, String paidByEmail,
			Instant paidAt, String payoutReference) {

		static SettlementView from(TeacherSettlement s, Map<UUID, String> emails, BigDecimal effectiveShareAmount) {
			return new SettlementView(s.getId(), s.getKind(), s.getTeacherId(), emails.get(s.getTeacherId()),
					s.getPeriodStart(), s.getPeriodEnd(), s.getGrossAmount(), s.getRefundAmount(), s.getNetAmount(),
					s.getSharePercent(), s.getShareAmount(), effectiveShareAmount, s.getCurrency(),
					s.getAdjustsSettlementId(), s.getReason(), s.getStatus(), s.getCalculatedBy(),
					emails.get(s.getCalculatedBy()), s.getCalculatedAt(), s.getPaidBy(),
					s.getPaidBy() == null ? null : emails.get(s.getPaidBy()), s.getPaidAt(), s.getPayoutReference());
		}

	}

	public record CourseBreakdown(UUID courseId, String courseTitle, BigDecimal gross, BigDecimal refunds,
			BigDecimal net, int entryCount) {

	}

	public record SettlementDetail(SettlementView settlement, List<CourseBreakdown> courses,
			List<SettlementView> adjustments) {

	}

}

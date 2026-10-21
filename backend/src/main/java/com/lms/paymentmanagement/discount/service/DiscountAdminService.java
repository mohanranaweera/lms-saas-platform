package com.lms.paymentmanagement.discount.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.CoursePriceOption;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.paymentmanagement.discount.domain.DiscountCourseRole;
import com.lms.paymentmanagement.discount.domain.DiscountPaymentType;
import com.lms.paymentmanagement.discount.domain.DiscountRedemption;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersion;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersionCourse;
import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.RedemptionStatus;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import com.lms.paymentmanagement.discount.repository.DiscountRedemptionRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionCourseRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionRepository;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.CourseOptionView;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.CourseRef;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountCounts;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountInput;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountListView;
import com.lms.paymentmanagement.discount.service.DiscountAdminViews.DiscountRuleView;
import com.lms.paymentmanagement.discount.service.DiscountRuleLoader.LoadedRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-admin management of discount rules (DISC-01, plan §5).
 *
 * <ul>
 * <li>Tenant from {@link TenantContext}; every read/write goes through the
 * structural tenant-aware repositories, so a foreign rule id is a 404.</li>
 * <li>{@code DISCOUNTS} VIEW for reads, CREATE_EDIT for writes (owner decision
 * 2026-10-21; {@code DiscountAdminController} re-checks the same grants with
 * {@code @PreAuthorize}). A caller without CREATE_EDIT sees every discount
 * code masked ({@value #MASKED_CODE}).</li>
 * <li>Full server-side validation per type ({@code 422 DISCOUNT_VALIDATION}
 * with field errors); unknown / foreign course ids are {@code 422
 * DISCOUNT_COURSE_NOT_FOUND}; draft / archived courses are {@code 422
 * DISCOUNT_VALIDATION}; a live duplicate code is {@code 409
 * DISCOUNT_CODE_TAKEN} - also when a concurrent create/edit wins the race (the
 * live-code unique index violation is mapped, never a 500).</li>
 * <li>Edit appends a new immutable version (D13); delete archives (D11); every
 * create / edit / toggle / archive writes an audit row with before/after in the
 * same transaction.</li>
 * </ul>
 */
@Service
public class DiscountAdminService {

	static final String TARGET_ENTITY = "discount_rule";

	private static final int MAX_COURSES = 200;

	private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");

	private static final int MAX_USES = 1_000_000;

	/** What a VIEW-only caller sees instead of a discount code. */
	static final String MASKED_CODE = "******";

	/** Sane bounds for Valid From / Valid To (inclusive). */
	static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

	static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

	private static final String LIVE_CODE_INDEX = "uq_discount_rule_tenant_code_live";

	private final DiscountRuleRepository ruleRepository;

	private final DiscountRuleVersionRepository versionRepository;

	private final DiscountRuleVersionCourseRepository versionCourseRepository;

	private final DiscountRedemptionRepository redemptionRepository;

	private final DiscountRuleLoader ruleLoader;

	private final CourseLookupApi courseLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final DiscountClock clock;

	public DiscountAdminService(DiscountRuleRepository ruleRepository, DiscountRuleVersionRepository versionRepository,
			DiscountRuleVersionCourseRepository versionCourseRepository,
			DiscountRedemptionRepository redemptionRepository, DiscountRuleLoader ruleLoader,
			CourseLookupApi courseLookupApi, PermissionCheckService permissionCheckService, AuditLogApi auditLogApi,
			TenantContext tenantContext, DiscountClock clock) {
		this.ruleRepository = ruleRepository;
		this.versionRepository = versionRepository;
		this.versionCourseRepository = versionCourseRepository;
		this.redemptionRepository = redemptionRepository;
		this.ruleLoader = ruleLoader;
		this.courseLookupApi = courseLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.clock = clock;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	/** @param status {@code ALL} (default), {@code ACTIVE} or {@code INACTIVE}; archived rules are never listed. */
	@Transactional(readOnly = true)
	public DiscountListView list(String status) {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.VIEW);
		String filter = status == null || status.isBlank() ? "ALL" : status.strip().toUpperCase(Locale.ROOT);
		if (!List.of("ALL", "ACTIVE", "INACTIVE").contains(filter)) {
			throw new DiscountAdminException.Validation(
					List.of(new FieldError("status", "Use ALL, ACTIVE or INACTIVE")));
		}
		List<LoadedRule> loaded = ruleLoader.load(ruleRepository.findAllLive());
		long active = loaded.stream().filter(rule -> rule.rule().isActive()).count();
		DiscountCounts counts = new DiscountCounts(loaded.size(), active, loaded.size() - active);
		List<LoadedRule> filtered = loaded.stream()
			.filter(rule -> switch (filter) {
				case "ACTIVE" -> rule.rule().isActive();
				case "INACTIVE" -> !rule.rule().isActive();
				default -> true;
			})
			.toList();
		Map<UUID, String> names = courseNames();
		Usage usage = usage(filtered.stream().map(rule -> rule.rule().getId()).toList());
		LocalDate today = clock.today();
		boolean revealCode = canEdit();
		List<DiscountRuleView> items = filtered.stream()
			.map(rule -> toView(rule, names, usage, today, 0, revealCode))
			.toList();
		return new DiscountListView(items, counts);
	}

	@Transactional(readOnly = true)
	public DiscountRuleView get(UUID id) {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.VIEW);
		LoadedRule loaded = loadLive(requireLiveRule(id));
		return toView(loaded, courseNames(), usage(List.of(id)), clock.today(), versionRepository.countByRuleId(id),
				canEdit());
	}

	@Transactional(readOnly = true)
	public List<CourseOptionView> courseOptions() {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.VIEW);
		return courseLookupApi.findCoursePriceOptions()
			.stream()
			.filter(DiscountAdminService::selectable)
			.map(course -> new CourseOptionView(course.courseId(), course.name(),
					course.price() == null ? null
							: course.currency() + " " + course.price().setScale(2, RoundingMode.HALF_UP).toPlainString(),
					course.monthly(), course.pricingModel(), course.price(), course.currency()))
			.toList();
	}

	// ------------------------------------------------------------------
	// Writes.
	// ------------------------------------------------------------------

	@Transactional
	public DiscountRuleView create(DiscountInput input) {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.CREATE_EDIT);
		UUID actor = AuthenticatedPrincipalHolder.get().userId();
		UUID tenantId = tenantContext.getTenantId();
		Validated validated = validate(input, null, null);
		DiscountRule rule = ruleRepository.save(new DiscountRule(tenantId, validated.terms().discountType(),
				validated.codeNormalized(), validated.active(), actor));
		DiscountRuleVersion version = appendVersion(rule, 1, validated, actor);
		rule.moveToVersion(version.getId(), validated.codeNormalized());
		rule = ruleRepository.save(rule);
		flushMappingCodeRace();
		LoadedRule loaded = new LoadedRule(rule, version, validated.requiredCourseIds(), validated.rewardCourseIds());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("after", snapshot(loaded));
		auditLogApi.record(new AuditLogEntry(actor, "discount_rule.created", TARGET_ENTITY, rule.getId(), null,
				metadata));
		return toView(loaded, courseNames(), usage(List.of(rule.getId())), clock.today(), 1, true);
	}

	/** Appends a new immutable version and moves the header to it (D13). The type cannot change. */
	@Transactional
	public DiscountRuleView update(UUID id, DiscountInput input) {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.CREATE_EDIT);
		UUID actor = AuthenticatedPrincipalHolder.get().userId();
		DiscountRule rule = ruleRepository.lockById(id)
			.filter(found -> !found.isArchived())
			.orElseThrow(() -> new NotFoundException("Discount not found"));
		LoadedRule before = loadLive(rule);
		Map<String, Object> beforeSnapshot = snapshot(before);
		Validated validated = validate(input, rule.getDiscountType(), rule.getId());
		int nextVersion = before.version().getVersionNo() + 1;
		DiscountRuleVersion version = appendVersion(rule, nextVersion, validated, actor);
		rule.moveToVersion(version.getId(), validated.codeNormalized());
		// A PUT without "active" keeps the current toggle.
		boolean wasActive = rule.isActive();
		rule.setActive(input.active() == null ? wasActive : input.active());
		rule = ruleRepository.save(rule);
		flushMappingCodeRace();
		LoadedRule after = new LoadedRule(rule, version, validated.requiredCourseIds(), validated.rewardCourseIds());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", beforeSnapshot);
		metadata.put("after", snapshot(after));
		auditLogApi.record(new AuditLogEntry(actor, "discount_rule.updated", TARGET_ENTITY, rule.getId(), null,
				metadata));
		if (wasActive != rule.isActive()) {
			// A PUT that flips the toggle is also an activation / deactivation.
			auditActiveChange(actor, rule, wasActive);
		}
		return toView(after, courseNames(), usage(List.of(id)), clock.today(), versionRepository.countByRuleId(id),
				true);
	}

	private void auditActiveChange(UUID actor, DiscountRule rule, boolean before) {
		boolean active = rule.isActive();
		auditLogApi.record(new AuditLogEntry(actor, active ? "discount_rule.activated" : "discount_rule.deactivated",
				TARGET_ENTITY, rule.getId(), null,
				Map.of("before", Map.of("active", before), "after", Map.of("active", active))));
	}

	/**
	 * Flushes the header so a concurrent create/edit that took the same live
	 * code surfaces HERE (the pre-check read can not see an uncommitted row):
	 * the {@value #LIVE_CODE_INDEX} violation is 409 {@code
	 * DISCOUNT_CODE_TAKEN}; any other integrity violation propagates.
	 */
	private void flushMappingCodeRace() {
		try {
			ruleRepository.flush();
		}
		catch (DataIntegrityViolationException ex) {
			Throwable cause = ex.getMostSpecificCause();
			if (cause != null && cause.getMessage() != null && cause.getMessage().contains(LIVE_CODE_INDEX)) {
				throw new DiscountAdminException.CodeTaken();
			}
			throw ex;
		}
	}

	@Transactional
	public DiscountRuleView setActive(UUID id, Boolean active) {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.CREATE_EDIT);
		if (active == null) {
			throw new DiscountAdminException.Validation(List.of(new FieldError("active", "active is required")));
		}
		UUID actor = AuthenticatedPrincipalHolder.get().userId();
		DiscountRule rule = ruleRepository.lockById(id)
			.filter(found -> !found.isArchived())
			.orElseThrow(() -> new NotFoundException("Discount not found"));
		boolean before = rule.isActive();
		if (before != active) {
			rule.setActive(active);
			rule = ruleRepository.save(rule);
			auditActiveChange(actor, rule, before);
		}
		return toView(loadLive(rule), courseNames(), usage(List.of(id)), clock.today(),
				versionRepository.countByRuleId(id), true);
	}

	/** D11: archive (never a row delete); an already-archived or foreign rule is 404. */
	@Transactional
	public void archive(UUID id) {
		permissionCheckService.requirePermission(DomainArea.DISCOUNTS, PermissionAction.CREATE_EDIT);
		UUID actor = AuthenticatedPrincipalHolder.get().userId();
		DiscountRule rule = ruleRepository.lockById(id)
			.filter(found -> !found.isArchived())
			.orElseThrow(() -> new NotFoundException("Discount not found"));
		Map<String, Object> before = snapshot(loadLive(rule));
		Instant now = clock.now();
		rule.archive(now);
		ruleRepository.save(rule);
		auditLogApi.record(new AuditLogEntry(actor, "discount_rule.archived", TARGET_ENTITY, rule.getId(), null,
				Map.of("before", before, "after", Map.of("archivedAt", now.toString()))));
	}

	// ------------------------------------------------------------------
	// Validation.
	// ------------------------------------------------------------------

	private record Validated(DiscountRuleVersion.Terms terms, List<UUID> requiredCourseIds,
			List<UUID> rewardCourseIds, String codeNormalized, boolean active) {

	}

	private Validated validate(DiscountInput input, DiscountType existingType, UUID selfId) {
		if (input == null) {
			throw new DiscountAdminException.Validation(List.of(new FieldError("body", "A request body is required")));
		}
		List<FieldError> errors = new ArrayList<>();

		String name = trimToNull(input.name());
		if (name == null) {
			errors.add(new FieldError("name", "Discount name is required"));
		}
		else if (name.length() > 120) {
			errors.add(new FieldError("name", "Discount name must be at most 120 characters"));
		}
		String description = trimToNull(input.description());
		if (description != null && description.length() > 1000) {
			errors.add(new FieldError("description", "Description must be at most 1000 characters"));
		}

		DiscountType type = parse(DiscountType.class, input.discountType());
		if (type == null) {
			errors.add(new FieldError("discountType",
					"Choose ADVANCE_PAYMENT, BUNDLE_AMOUNT_OFF, BUNDLE_FREE_COURSE or DISCOUNT_CODE"));
		}
		else if (existingType != null && type != existingType) {
			errors.add(new FieldError("discountType", "The discount type of an existing discount cannot be changed"));
		}

		List<UUID> required = distinct(input.courseIds(), "courseIds", "Select at least one required course",
				errors);

		SelectionMode mode = parse(SelectionMode.class, input.selectionMode());
		Integer minCount = null;
		if (mode == null) {
			errors.add(new FieldError("selectionMode", "Choose ALL, ANY or MIN_COUNT"));
		}
		else if (mode == SelectionMode.MIN_COUNT) {
			minCount = input.minCount();
			if (minCount == null) {
				errors.add(new FieldError("minCount", "Minimum count is required"));
			}
			else if (minCount < 1 || (required != null && minCount > required.size())) {
				errors.add(new FieldError("minCount",
						"Minimum count must be between 1 and the number of required courses"));
			}
		}

		LocalDate validFrom = input.validFrom();
		LocalDate validTo = input.validTo();
		if (validFrom == null) {
			errors.add(new FieldError("validFrom", "Valid from is required"));
		}
		if (validTo == null) {
			errors.add(new FieldError("validTo", "Valid to is required"));
		}
		if (validFrom != null && (validFrom.isBefore(MIN_DATE) || validFrom.isAfter(MAX_DATE))) {
			errors.add(new FieldError("validFrom", "Valid from must be between " + MIN_DATE + " and " + MAX_DATE));
		}
		if (validTo != null && (validTo.isBefore(MIN_DATE) || validTo.isAfter(MAX_DATE))) {
			errors.add(new FieldError("validTo", "Valid to must be between " + MIN_DATE + " and " + MAX_DATE));
		}
		if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
			errors.add(new FieldError("validTo", "Valid to must be on or after valid from"));
		}

		Integer maxUses = input.maxUses();
		if (maxUses != null && (maxUses < 1 || maxUses > MAX_USES)) {
			errors.add(new FieldError("maxUses", "Maximum uses must be between 1 and " + MAX_USES));
		}
		BigDecimal minPurchase = input.minPurchaseAmount();
		if (minPurchase != null && !validMoney(minPurchase, true)) {
			errors.add(new FieldError("minPurchaseAmount",
					"Minimum purchase amount must be zero or more with at most 2 decimals"));
		}

		DiscountPaymentType paymentType = null;
		DiscountValueType valueType = null;
		BigDecimal value = null;
		List<UUID> reward = List.of();
		Integer freeMonths = null;
		String code = null;
		if (type == DiscountType.ADVANCE_PAYMENT) {
			paymentType = parse(DiscountPaymentType.class, input.paymentType());
			if (paymentType == null) {
				errors.add(new FieldError("paymentType", "Payment type is required (INSTALLMENTS)"));
			}
		}
		if (type != null && type != DiscountType.BUNDLE_FREE_COURSE) {
			valueType = parse(DiscountValueType.class, input.valueType());
			if (valueType == null) {
				errors.add(new FieldError("valueType", "Choose FIXED or PERCENTAGE"));
			}
			value = input.value();
			if (value == null) {
				errors.add(new FieldError("value", "Discount value is required"));
			}
			else if (!validMoney(value, false)) {
				errors.add(new FieldError("value", "Discount value must be more than zero with at most 2 decimals"));
			}
			else if (valueType == DiscountValueType.PERCENTAGE && value.compareTo(BigDecimal.valueOf(100)) > 0) {
				errors.add(new FieldError("value", "A percentage cannot be more than 100"));
			}
			if (value != null && validMoney(value, false)) {
				value = value.setScale(2, RoundingMode.HALF_UP);
			}
		}
		if (type == DiscountType.BUNDLE_FREE_COURSE) {
			List<UUID> rewards = distinct(input.rewardCourseIds(), "rewardCourseIds", "Select at least one free course",
					errors);
			reward = rewards == null ? List.of() : rewards;
			if (required != null && reward.stream().anyMatch(required::contains)) {
				errors.add(new FieldError("rewardCourseIds", "A free course cannot also be a required course"));
			}
			freeMonths = input.freeMonths();
			if (freeMonths == null || freeMonths < 1 || freeMonths > 24) {
				errors.add(new FieldError("freeMonths", "Free for (months) must be between 1 and 24"));
			}
		}
		if (type == DiscountType.DISCOUNT_CODE) {
			code = DiscountPricingService.normalizeCode(input.code());
			if (code == null) {
				errors.add(new FieldError("code", "Discount code is required"));
			}
			else if (!DiscountPricingService.isWellFormedCode(code)) {
				errors.add(new FieldError("code", "Use 6-40 letters, digits, '-' or '_'"));
			}
		}
		if (!errors.isEmpty()) {
			throw new DiscountAdminException.Validation(errors);
		}

		// Course existence (tenant-scoped) and type-specific course checks.
		Map<UUID, CoursePriceOption> courses = new HashMap<>();
		courseLookupApi.findCoursePriceOptions().forEach(course -> courses.put(course.courseId(), course));
		List<FieldError> missing = new ArrayList<>();
		if (required.stream().anyMatch(courseId -> !courses.containsKey(courseId))) {
			missing.add(new FieldError("courseIds", "One or more required courses were not found"));
		}
		if (reward.stream().anyMatch(courseId -> !courses.containsKey(courseId))) {
			missing.add(new FieldError("rewardCourseIds", "One or more free courses were not found"));
		}
		if (!missing.isEmpty()) {
			throw new DiscountAdminException.CourseNotFound(missing);
		}
		// Same rule as the course pickers (courseOptions): no draft / archived course.
		if (required.stream().anyMatch(courseId -> !selectable(courses.get(courseId)))) {
			errors.add(new FieldError("courseIds", "Draft or archived courses cannot be used in a discount"));
		}
		if (reward.stream().anyMatch(courseId -> !selectable(courses.get(courseId)))) {
			errors.add(new FieldError("rewardCourseIds", "Draft or archived courses cannot be used in a discount"));
		}
		if (type == DiscountType.BUNDLE_FREE_COURSE
				&& reward.stream().anyMatch(courseId -> !courses.get(courseId).monthly())) {
			errors.add(new FieldError("rewardCourseIds", "Free courses must be monthly-priced courses"));
		}
		if (type == DiscountType.ADVANCE_PAYMENT
				&& required.stream().anyMatch(courseId -> !courses.get(courseId).monthly())) {
			errors.add(new FieldError("courseIds", "Advance payment discounts apply to monthly-priced courses only"));
		}
		if (!errors.isEmpty()) {
			throw new DiscountAdminException.Validation(errors);
		}
		if (code != null) {
			Optional<DiscountRule> holder = ruleRepository.findLiveByCode(code);
			if (holder.isPresent() && !holder.get().getId().equals(selfId)) {
				throw new DiscountAdminException.CodeTaken();
			}
		}
		DiscountRuleVersion.Terms terms = new DiscountRuleVersion.Terms(name, description, type, mode, minCount,
				paymentType, valueType, value, freeMonths, code, validFrom, validTo, maxUses,
				minPurchase == null ? null : minPurchase.setScale(2, RoundingMode.HALF_UP), clock.tenantCurrency());
		return new Validated(terms, required, reward, code, input.active() == null || input.active());
	}

	private static List<UUID> distinct(List<UUID> ids, String field, String emptyMessage, List<FieldError> errors) {
		if (ids == null || ids.isEmpty()) {
			errors.add(new FieldError(field, emptyMessage));
			return null;
		}
		if (ids.size() > MAX_COURSES) {
			// Bounded before any de-duplication work.
			errors.add(new FieldError(field, "At most " + MAX_COURSES + " courses"));
			return null;
		}
		if (ids.stream().anyMatch(Objects::isNull)) {
			errors.add(new FieldError(field, "Course ids must not be empty"));
			return null;
		}
		Set<UUID> unique = new LinkedHashSet<>(ids);
		if (unique.size() > MAX_COURSES) {
			errors.add(new FieldError(field, "At most " + MAX_COURSES + " courses"));
			return null;
		}
		return List.copyOf(unique);
	}

	/** A course a discount may reference: neither archived nor DRAFT. */
	private static boolean selectable(CoursePriceOption course) {
		return course != null && !course.archived() && !"DRAFT".equals(course.status());
	}

	private boolean canEdit() {
		return permissionCheckService.hasPermission(DomainArea.DISCOUNTS, PermissionAction.CREATE_EDIT);
	}

	private static boolean validMoney(BigDecimal value, boolean allowZero) {
		if (value.stripTrailingZeros().scale() > 2) {
			return false;
		}
		int sign = value.signum();
		return (allowZero ? sign >= 0 : sign > 0) && value.compareTo(MAX_MONEY) <= 0;
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private DiscountRuleVersion appendVersion(DiscountRule rule, int versionNo, Validated validated, UUID actor) {
		UUID tenantId = tenantContext.getTenantId();
		DiscountRuleVersion version = versionRepository.save(
				new DiscountRuleVersion(tenantId, rule.getId(), versionNo, validated.terms(), clock.now(), actor));
		for (UUID courseId : validated.requiredCourseIds()) {
			versionCourseRepository.save(
					new DiscountRuleVersionCourse(tenantId, version.getId(), courseId, DiscountCourseRole.REQUIRED));
		}
		for (UUID courseId : validated.rewardCourseIds()) {
			versionCourseRepository
				.save(new DiscountRuleVersionCourse(tenantId, version.getId(), courseId, DiscountCourseRole.REWARD));
		}
		return version;
	}

	private DiscountRule requireLiveRule(UUID id) {
		return ruleRepository.findById(id)
			.filter(rule -> !rule.isArchived())
			.orElseThrow(() -> new NotFoundException("Discount not found"));
	}

	private LoadedRule loadLive(DiscountRule rule) {
		List<LoadedRule> loaded = ruleLoader.load(List.of(rule));
		if (loaded.isEmpty()) {
			throw new NotFoundException("Discount not found");
		}
		return loaded.get(0);
	}

	private Map<UUID, String> courseNames() {
		Map<UUID, String> names = new HashMap<>();
		courseLookupApi.findCoursePriceOptions().forEach(course -> names.put(course.courseId(), course.name()));
		return names;
	}

	private record Usage(Map<UUID, Long> used, Map<UUID, Long> reserved) {

	}

	private Usage usage(List<UUID> ruleIds) {
		Map<UUID, Long> used = new HashMap<>();
		Map<UUID, Long> reserved = new HashMap<>();
		Instant now = clock.now();
		for (DiscountRedemption redemption : redemptionRepository.findAllByRuleIdIn(ruleIds)) {
			if (redemption.getStatus() == RedemptionStatus.CONSUMED) {
				used.merge(redemption.getRuleId(), 1L, Long::sum);
			}
			else if (redemption.countsAt(now)) {
				reserved.merge(redemption.getRuleId(), 1L, Long::sum);
			}
		}
		return new Usage(used, reserved);
	}

	/** @param revealCode {@code false} for a caller without DISCOUNTS CREATE_EDIT: the code is masked. */
	private static DiscountRuleView toView(LoadedRule loaded, Map<UUID, String> names, Usage usage, LocalDate today,
			long versionCount, boolean revealCode) {
		DiscountRule rule = loaded.rule();
		DiscountRuleVersion v = loaded.version();
		String status;
		if (!rule.isActive()) {
			status = "INACTIVE";
		}
		else if (today.isBefore(v.getValidFrom())) {
			status = "SCHEDULED";
		}
		else if (today.isAfter(v.getValidTo())) {
			status = "EXPIRED";
		}
		else {
			status = "ACTIVE";
		}
		return new DiscountRuleView(rule.getId(), v.getName(), v.getDescription(), v.getDiscountType().name(),
				rule.isActive(), status, loaded.requiredCourseIds(), refs(loaded.requiredCourseIds(), names),
				v.getSelectionMode().name(), v.getMinCount(), name(v.getPaymentType()), name(v.getValueType()),
				v.getValue(), valueLabel(v), loaded.rewardCourseIds(), refs(loaded.rewardCourseIds(), names),
				v.getFreeMonths(), v.getCode() == null || revealCode ? v.getCode() : MASKED_CODE, v.getValidFrom(),
				v.getValidTo(), v.getMaxUses(),
				v.getMinPurchaseAmount(), v.getCurrency(), v.getVersionNo(), Math.max(versionCount, v.getVersionNo()),
				usage.used().getOrDefault(rule.getId(), 0L), usage.reserved().getOrDefault(rule.getId(), 0L),
				rule.getCreatedAt(), rule.getUpdatedAt());
	}

	private static List<CourseRef> refs(List<UUID> ids, Map<UUID, String> names) {
		return ids.stream().map(id -> new CourseRef(id, names.get(id))).toList();
	}

	private static String valueLabel(DiscountRuleVersion v) {
		if (v.getDiscountType() == DiscountType.BUNDLE_FREE_COURSE) {
			int months = v.getFreeMonths() == null ? 0 : v.getFreeMonths();
			return months + (months == 1 ? " MONTH FREE" : " MONTHS FREE");
		}
		if (v.getValue() == null) {
			return null;
		}
		if (v.getValueType() == DiscountValueType.PERCENTAGE) {
			return v.getValue().stripTrailingZeros().toPlainString() + "% OFF";
		}
		return v.getCurrency() + " " + v.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString() + " OFF";
	}

	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	/** Audit before/after: every term as a string (null-safe nested map). */
	private static Map<String, Object> snapshot(LoadedRule loaded) {
		DiscountRuleVersion v = loaded.version();
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("versionId", String.valueOf(v.getId()));
		map.put("versionNo", v.getVersionNo());
		map.put("name", v.getName());
		map.put("description", v.getDescription());
		map.put("discountType", name(v.getDiscountType()));
		map.put("active", loaded.rule().isActive());
		map.put("courseIds", loaded.requiredCourseIds().stream().map(UUID::toString).toList());
		map.put("selectionMode", name(v.getSelectionMode()));
		map.put("minCount", v.getMinCount());
		map.put("paymentType", name(v.getPaymentType()));
		map.put("valueType", name(v.getValueType()));
		map.put("value", v.getValue() == null ? null : v.getValue().toPlainString());
		map.put("rewardCourseIds", loaded.rewardCourseIds().stream().map(UUID::toString).toList());
		map.put("freeMonths", v.getFreeMonths());
		map.put("code", v.getCode());
		map.put("validFrom", v.getValidFrom() == null ? null : v.getValidFrom().toString());
		map.put("validTo", v.getValidTo() == null ? null : v.getValidTo().toString());
		map.put("maxUses", v.getMaxUses());
		map.put("minPurchaseAmount", v.getMinPurchaseAmount() == null ? null : v.getMinPurchaseAmount().toPlainString());
		map.put("currency", v.getCurrency());
		return map;
	}

}

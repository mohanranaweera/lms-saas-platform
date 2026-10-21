package com.lms.coursemanagement.course.service;

import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseArchiveStateChangedEvent;
import com.lms.coursemanagement.api.CourseAuditEvent;
import com.lms.coursemanagement.api.CourseClonedEvent;
import com.lms.coursemanagement.api.CoursePriceChangedEvent;
import com.lms.coursemanagement.api.CoursePricingModelChangedEvent;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CoursePriceHistory;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseBillingConfigurationRepository;
import com.lms.coursemanagement.course.repository.CourseBillingPeriodRepository;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CoursePriceHistoryRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseSpecifications;
import com.lms.coursemanagement.course.service.CourseRelationsService.CommissionChange;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Course Management (MVP-008) course-level CRUD, teacher
 * reassignment, pricing, and publish/unpublish. Every public method
 * independently re-checks authorization before doing anything else -
 * defense in depth on top of {@code CourseController}'s coarse {@code
 * @PreAuthorize("isAuthenticated()")} gate, mirroring {@code
 * usermanagement.staff.service.StaffService}'s established discipline (see
 * its javadoc). The staff-matrix-or-Teacher-ownership check itself is
 * delegated to {@link CourseAccessGuard}, since it is identical across this
 * class and {@code CourseModuleService}/{@code CourseLessonService}.
 *
 * <p>KLASS-T4a adds the "Add New Course" settings (multi-teacher, payment
 * type, enrollment fee, Payment Valid Days, commission, invite links, tags,
 * linked courses), the registration toggle, persisted list ordering, server
 * search/archived filters and {@code Idempotency-Key} replay on create.
 * Tenant identity is always {@link TenantContext}; every repository read is
 * tenant-scoped.
 */
@Service
@Transactional
public class CourseService {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

	private static final String STUDENT_ROLE = "STUDENT";

	static final String DEFAULT_CATEGORY = "General";

	private static final int MAX_SLUG_LENGTH = 160;

	private static final int MAX_TEACHERS = 20;

	/**
	 * Defensive server-side cap on {@code listCourses}' page size, applied
	 * regardless of any {@code spring.data.web.pageable.max-page-size}
	 * configuration - a client requesting {@code ?size=999999} must never
	 * be able to force an unbounded read (MVP-008 review High finding).
	 */
	private static final int MAX_PAGE_SIZE = 100;

	private final CourseRepository courseRepository;

	private final CoursePriceHistoryRepository coursePriceHistoryRepository;

	private final CourseModuleRepository courseModuleRepository;

	private final CourseLessonRepository courseLessonRepository;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final CourseAccessGuard courseAccessGuard;

	private final ApplicationEventPublisher eventPublisher;

	private final CourseViewAssembler viewAssembler;

	private final CourseRelationsService relationsService;

	private final BillingConfigurationService billingConfigurationService;

	private final CourseBillingConfigurationRepository billingConfigurationRepository;

	private final CourseBillingPeriodRepository billingPeriodRepository;

	private final TenantConfigApi tenantConfigApi;

	private final CourseCreateIdempotencyGuard idempotencyGuard;

	public CourseService(CourseRepository courseRepository, CoursePriceHistoryRepository coursePriceHistoryRepository,
			CourseModuleRepository courseModuleRepository, CourseLessonRepository courseLessonRepository,
			TenantContext tenantContext, PermissionCheckService permissionCheckService,
			UserProvisioningApi userProvisioningApi, CourseAccessGuard courseAccessGuard,
			ApplicationEventPublisher eventPublisher, CourseViewAssembler viewAssembler,
			CourseRelationsService relationsService, BillingConfigurationService billingConfigurationService,
			CourseBillingConfigurationRepository billingConfigurationRepository,
			CourseBillingPeriodRepository billingPeriodRepository, TenantConfigApi tenantConfigApi,
			CourseCreateIdempotencyGuard idempotencyGuard) {
		this.courseRepository = courseRepository;
		this.coursePriceHistoryRepository = coursePriceHistoryRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.courseAccessGuard = courseAccessGuard;
		this.eventPublisher = eventPublisher;
		this.viewAssembler = viewAssembler;
		this.relationsService = relationsService;
		this.billingConfigurationService = billingConfigurationService;
		this.billingConfigurationRepository = billingConfigurationRepository;
		this.billingPeriodRepository = billingPeriodRepository;
		this.tenantConfigApi = tenantConfigApi;
		this.idempotencyGuard = idempotencyGuard;
	}

	public CourseView createCourse(NewCourseCommand command) {
		return createCourse(command, null);
	}

	/**
	 * Creates a course. For a Teacher-role caller, the teacher is forced to
	 * the caller's own id server-side - any {@code teacherId}/{@code
	 * teacherIds} in the command is ignored, never trusted, per plan §12/§15.
	 * For a staff caller, {@code CREATE_EDIT} on {@link DomainArea#COURSES} is
	 * required and at least one teacher is mandatory ({@code teacherIds}
	 * first = primary, else the legacy {@code teacherId}), each validated via
	 * {@link UserProvisioningApi} (same tenant, {@code role = TEACHER}, not
	 * suspended).
	 *
	 * <p>Pricing: {@code pricingModel} defaults to ONE_TIME; SESSION/CUSTOM
	 * are not selectable here (D09). ONE_TIME needs {@code price >= 0}
	 * ({@code course.price}); MONTHLY needs {@code price > 0} and creates the
	 * billing configuration plus the first open billing period in this same
	 * transaction; FREE/MODULE/DAILY/INSTALLMENT reject a non-zero price.
	 * {@code enrollmentFee} is informational only (ADR-028) and is never read
	 * by checkout.
	 *
	 * <p>{@code idempotencyKey}: the same key from the same actor in the same
	 * tenant returns the course the first request created (see {@link
	 * CourseCreateIdempotencyGuard}).
	 */
	public CourseView createCourse(NewCourseCommand command, String idempotencyKey) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		UUID tenantId = tenantContext.getTenantId();
		CourseSettingsInput settings = command.settings();

		List<UUID> teacherIds;
		if (TEACHER_ROLE.equals(principal.role())) {
			teacherIds = List.of(principal.userId());
		}
		else {
			permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT);
			teacherIds = requestedTeachers(settings.teacherIds(), command.teacherId());
			validateTeacherCandidates(teacherIds);
		}

		String name = requireName(command.name());
		CoursePricingModel pricingModel = settings.pricingModel() == null ? CoursePricingModel.ONE_TIME
				: settings.pricingModel();
		if (!pricingModel.selectableOnCreate()) {
			throw CourseRuleException.invalid(CourseRuleException.PRICING_MODEL_NOT_SELECTABLE, "pricingModel",
					"This payment type cannot be chosen for a new course");
		}
		BigDecimal coursePrice = initialCoursePrice(pricingModel, command.price());
		String whatsapp = cleanInviteUrl(settings.whatsappInviteUrl(), "whatsappInviteUrl");
		String telegram = cleanInviteUrl(settings.telegramInviteUrl(), "telegramInviteUrl");
		Integer accessDurationDays = resolveAccessDuration(settings.paymentValidDays(), command.accessDurationDays());

		CourseCreateIdempotencyGuard.Begin idempotency = idempotencyGuard.begin(tenantId, principal.userId(),
				idempotencyKey);
		if (idempotency.isReplay()) {
			Optional<Course> earlier = courseRepository.findById(idempotency.replayCourseId());
			if (earlier.isPresent()) {
				return toView(earlier.get());
			}
			idempotencyGuard.forget(tenantId, principal.userId(), idempotencyKey);
			idempotency = idempotencyGuard.begin(tenantId, principal.userId(), idempotencyKey);
		}

		String slug = isBlank(command.slug()) ? uniqueSlugFor(name) : command.slug().strip();
		// Friendlier pre-check for the common case; NOT the race-safe guard -
		// V11's UNIQUE (tenant_id, slug) constraint (surfaced as 409 via
		// GlobalExceptionHandler's DataIntegrityViolationException handler)
		// is what actually prevents a concurrent double-creation from both
		// succeeding.
		if (courseRepository.existsBySlug(slug)) {
			throw new ConflictException("A course with this slug already exists");
		}
		String category = isBlank(command.category()) ? DEFAULT_CATEGORY : command.category().strip();

		Course course = new Course(tenantId, teacherIds.get(0), name, slug, category, command.subject(),
				command.stream(), command.grade(), command.academicYear(), command.description(), coursePrice,
				accessDurationDays, command.enrollmentRule(), command.status());
		course.setPricingModel(pricingModel);
		course.setEnrollmentFee(settings.enrollmentFee());
		course.setWhatsappInviteUrl(whatsapp);
		course.setTelegramInviteUrl(telegram);
		course = courseRepository.save(course);

		relationsService.syncTeachers(course, teacherIds);
		if (settings.tagNames() != null) {
			relationsService.replaceTags(course.getId(), settings.tagNames());
		}
		if (settings.linkedCourseIds() != null && !settings.linkedCourseIds().isEmpty()) {
			relationsService.replaceLinks(course, settings.linkedCourseIds(), principal.userId());
		}
		if (settings.commissionRate() != null) {
			relationsService.appendCommissionRateIfChanged(course.getId(), settings.commissionRate(),
					principal.userId());
		}
		if (pricingModel == CoursePricingModel.MONTHLY) {
			String currency = tenantConfigApi.resolveDefaultCurrency(tenantId);
			billingConfigurationService.createOrUpdateConfiguration(course.getId(), null, currency, false);
			billingConfigurationService.addBillingPeriod(course.getId(), command.price(), currency, Instant.now());
		}

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", name);
		metadata.put("pricingModel", pricingModel.name());
		metadata.put("price", command.price());
		metadata.put("teacherIds", teacherIds.stream().map(UUID::toString).toList());
		metadata.put("enrollmentFee", course.getEnrollmentFee());
		metadata.put("commissionRate", settings.commissionRate());
		metadata.put("paymentValidDays", accessDurationDays);
		metadata.put("linkedCourseIds", settings.linkedCourseIds() == null ? null
				: settings.linkedCourseIds().stream().map(UUID::toString).toList());
		publishAudit(principal, "course.created", "course", course.getId(), metadata);

		if (idempotency.claim() != null) {
			idempotency.claim().complete(course.getId());
		}
		return toView(course);
	}

	/**
	 * Teacher-role: {@code filter.teacherId()} is ignored/overridden - the
	 * caller's own id is force-combined with the optional filters, so a
	 * Teacher can still narrow their own listing but can never see another
	 * teacher's courses. Staff: requires {@code VIEW} on {@link
	 * DomainArea#COURSES}, then applies all optional filters, including
	 * {@code teacherId}. Every filter is layered on top of, never instead of,
	 * {@code CourseRepository}'s inherited tenant-scoped {@code
	 * findAll(Specification, Pageable)}. The requested page size is clamped
	 * to {@link #MAX_PAGE_SIZE}.
	 *
	 * <p>KLASS-T4a: {@code archived}/{@code search} filters; when the client
	 * sends no explicit sort, the order is {@code displayOrder} (nulls last),
	 * then {@code createdAt}, then {@code id}.
	 */
	@Transactional(readOnly = true)
	public PageResponse<CourseView> listCourses(Pageable pageable, CourseListFilter filter) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Pageable safePageable = clampPageSize(pageable);
		Specification<Course> archivedSpec = filter.archived() != null
				? CourseSpecifications.archived(filter.archived())
				: CourseSpecifications.excludeArchivedUnless(filter.includeArchived());
		Specification<Course> spec = CourseSpecifications.withStatus(filter.status())
			.and(CourseSpecifications.withCategory(filter.category()))
			.and(archivedSpec);

		if (TEACHER_ROLE.equals(principal.role())) {
			spec = spec.and(CourseSpecifications.withTeacherId(principal.userId()));
		}
		else {
			permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.VIEW);
			spec = spec.and(CourseSpecifications.withTeacherId(filter.teacherId()));
		}
		if (!isBlank(filter.search())) {
			spec = spec.and(CourseSpecifications.search(filter.search(),
					viewAssembler.teacherIdsMatchingName(filter.search())));
		}

		Page<Course> page = courseRepository.findAll(spec, safePageable);
		List<CourseView> views = viewAssembler.toViews(page.getContent(), isStaffView(principal),
				courseAccessGuard::isCallerParticipant);
		Map<UUID, CourseView> byId = views.stream().collect(Collectors.toMap(CourseView::id, view -> view));
		return PageResponse.from(page.map(course -> byId.get(course.getId())));
	}

	private Pageable clampPageSize(Pageable pageable) {
		Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : adminListOrder();
		int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
		return PageRequest.of(pageable.getPageNumber(), size, sort);
	}

	static Sort adminListOrder() {
		return Sort.by(Sort.Order.asc("displayOrder").nullsLast(), Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
	}

	@Transactional(readOnly = true)
	public CourseView getCourse(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		return toView(course);
	}

	/**
	 * Edits a course. With a {@code name} (the full edit form) legacy fields
	 * keep their full-replacement semantics ({@code description}, {@code
	 * subject}, ... {@code null} clears); without one (KLASS-T8b partial body)
	 * every field is "{@code null} = unchanged";
	 * {@code slug}/{@code category} {@code null} = unchanged. KLASS settings
	 * follow {@link CourseSettingsInput}'s "{@code null} = unchanged" rule.
	 * Price and pricing-model changes go through the same audited paths as
	 * {@link #changePrice}/{@link #changePricingModel}; changing the PRIMARY
	 * teacher stays Tenant-Admin-only (as {@link #reassignTeacher}), and a
	 * Teacher-role caller cannot change the teacher list at all.
	 */
	public CourseView updateCourse(UUID id, CourseEditCommand command) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		CourseSettingsInput settings = command.settings();
		Map<String, Object> changes = new LinkedHashMap<>();

		if (!isBlank(command.slug())) {
			String slug = command.slug().strip();
			if (!course.getSlug().equals(slug) && courseRepository.existsBySlugAndIdNot(slug, id)) {
				throw new ConflictException("A course with this slug already exists");
			}
			course.setSlug(slug);
		}
		if (!isBlank(command.category())) {
			course.setCategory(command.category().strip());
		}
		if (command.name() != null) {
			// Full edit form: legacy fields keep their full-replacement semantics.
			String name = requireName(command.name());
			recordChange(changes, "name", course.getName(), name);
			course.setName(name);
			course.setSubject(command.subject());
			course.setStream(command.stream());
			course.setGrade(command.grade());
			course.setAcademicYear(command.academicYear());
			course.setDescription(command.description());
			course.setEnrollmentRule(command.enrollmentRule());
			applyAccessDuration(course, settings.paymentValidDays(), command.accessDurationDays(), changes);
		}
		else {
			// KLASS-T8b: a partial body (no name) - every field is "null = unchanged".
			applyIfPresent(command.subject(), course::setSubject);
			applyIfPresent(command.stream(), course::setStream);
			applyIfPresent(command.grade(), course::setGrade);
			applyIfPresent(command.academicYear(), course::setAcademicYear);
			applyIfPresent(command.description(), course::setDescription);
			applyIfPresent(command.enrollmentRule(), course::setEnrollmentRule);
			if (settings.paymentValidDays() != null || command.accessDurationDays() != null) {
				applyAccessDuration(course, settings.paymentValidDays(), command.accessDurationDays(), changes);
			}
		}

		if (settings.enrollmentFee() != null && course.getEnrollmentFee().compareTo(settings.enrollmentFee()) != 0) {
			changes.put("enrollmentFee", Map.of("from", course.getEnrollmentFee(), "to", settings.enrollmentFee()));
			course.setEnrollmentFee(settings.enrollmentFee());
		}
		if (settings.whatsappInviteUrl() != null) {
			String url = cleanInviteUrl(settings.whatsappInviteUrl(), "whatsappInviteUrl");
			recordChange(changes, "whatsappInviteUrl", course.getWhatsappInviteUrl(), url);
			course.setWhatsappInviteUrl(url);
		}
		if (settings.telegramInviteUrl() != null) {
			String url = cleanInviteUrl(settings.telegramInviteUrl(), "telegramInviteUrl");
			recordChange(changes, "telegramInviteUrl", course.getTelegramInviteUrl(), url);
			course.setTelegramInviteUrl(url);
		}

		if (settings.teacherIds() != null) {
			updateTeachers(course, principal, settings.teacherIds(), changes);
		}
		if (settings.tagNames() != null && relationsService.replaceTags(course.getId(), settings.tagNames())) {
			changes.put("tags", settings.tagNames());
		}
		if (settings.linkedCourseIds() != null
				&& relationsService.replaceLinks(course, settings.linkedCourseIds(), principal.userId())) {
			changes.put("linkedCourseIds", settings.linkedCourseIds().stream().map(UUID::toString).toList());
		}
		if (settings.commissionRate() != null) {
			relationsService.appendCommissionRateIfChanged(course.getId(), settings.commissionRate(), principal.userId())
				.ifPresent(change -> publishCommissionAudit(principal, course.getId(), change));
		}
		if (settings.pricingModel() != null && settings.pricingModel() != course.getPricingModel()) {
			if (!settings.pricingModel().selectableOnCreate()) {
				throw CourseRuleException.invalid(CourseRuleException.PRICING_MODEL_NOT_SELECTABLE, "pricingModel",
						"This payment type can no longer be chosen");
			}
			applyPricingModelChange(course, settings.pricingModel(), principal);
		}
		if (command.price() != null) {
			applyEditedPrice(course, command.price(), principal);
		}
		applyCharges(course, command.charges(), principal);

		if (!changes.isEmpty()) {
			publishAudit(principal, "course.updated", "course", course.getId(), changes);
		}
		return toView(course);
	}

	/**
	 * V90 facility-fee override / postage edit - same authorization as the
	 * price edit ({@code CREATE_EDIT} on this tenant course, checked by the
	 * caller). Validation: values are {@code >= 0} with at most 2 decimals
	 * (DTO + re-checked here), {@code facilityFeeInherit = false} needs an
	 * explicit override. Every effective change is audited ({@code
	 * course.charges_changed}) with before/after values ({@code null} =
	 * inherit), inside this transaction. Existing orders keep their own
	 * immutable snapshot ({@code student_order_course_charge}).
	 */
	private void applyCharges(Course course, CourseChargesInput charges, AuthenticatedPrincipal principal) {
		if (charges == null || charges.isEmpty()) {
			return;
		}
		Map<String, Object> changes = new LinkedHashMap<>();
		if (Boolean.TRUE.equals(charges.facilityFeeInherit())) {
			if (course.getFacilityFeeOverride() != null) {
				recordChange(changes, "facilityFeeOverride", course.getFacilityFeeOverride(), null);
				course.setFacilityFeeOverride(null);
			}
		}
		else if (Boolean.FALSE.equals(charges.facilityFeeInherit()) || charges.facilityFeeOverride() != null) {
			if (charges.facilityFeeOverride() == null) {
				throw CourseRuleException.invalid("VALIDATION_ERROR", "facilityFeeOverride",
						"Enter the course facility fee (0 for no fee) or inherit the institute default");
			}
			BigDecimal override = requireMoney(charges.facilityFeeOverride(), "facilityFeeOverride");
			if (course.getFacilityFeeOverride() == null || course.getFacilityFeeOverride().compareTo(override) != 0) {
				recordChange(changes, "facilityFeeOverride", course.getFacilityFeeOverride(), override);
				course.setFacilityFeeOverride(override);
			}
		}
		if (charges.postageFee() != null) {
			BigDecimal postage = requireMoney(charges.postageFee(), "postageFee");
			if (course.getPostageFee().compareTo(postage) != 0) {
				recordChange(changes, "postageFee", course.getPostageFee(), postage);
				course.setPostageFee(postage);
			}
		}
		if (!changes.isEmpty()) {
			publishAudit(principal, "course.charges_changed", "course", course.getId(), changes);
		}
	}

	/** Same bound as the tenant facility-fee config ({@code ConfigPropertyRegistry}). */
	private static final BigDecimal MAX_COURSE_CHARGE = new BigDecimal("1000000");

	private static BigDecimal requireMoney(BigDecimal value, String field) {
		if (value.signum() < 0 || value.stripTrailingZeros().scale() > 2 || value.compareTo(MAX_COURSE_CHARGE) >= 0) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", field,
					"Must be 0 or more, below 1,000,000, with at most 2 decimal places");
		}
		return value.setScale(2, java.math.RoundingMode.UNNECESSARY);
	}

	private static void applyAccessDuration(Course course, Integer paymentValidDays, Integer legacyAccessDurationDays,
			Map<String, Object> changes) {
		Integer accessDurationDays = resolveAccessDuration(paymentValidDays, legacyAccessDurationDays);
		recordChange(changes, "paymentValidDays", course.getAccessDurationDays(), accessDurationDays);
		course.setAccessDurationDays(accessDurationDays);
	}

	private static void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
		if (value != null) {
			setter.accept(value);
		}
	}

	private void updateTeachers(Course course, AuthenticatedPrincipal principal, List<UUID> requested,
			Map<String, Object> changes) {
		List<UUID> desired = List.copyOf(new LinkedHashSet<>(requested));
		if (desired.isEmpty()) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "teacherIds", "Teacher is required");
		}
		List<UUID> current = relationsService.currentTeacherIds(course);
		if (desired.equals(current)) {
			return;
		}
		if (TEACHER_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		if (!desired.get(0).equals(course.getTeacherId()) && !TENANT_ADMIN_ROLE.equals(principal.role())) {
			// Changing the primary teacher = teacher reassignment (Tenant Admin only, plan §10/§15(a)).
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		if (desired.size() > MAX_TEACHERS) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "teacherIds",
					"At most " + MAX_TEACHERS + " teachers can be assigned");
		}
		// R2-D7: only teachers being newly assigned (and a new primary, which is a
		// reassignment, mirroring reassignTeacher) must be active Teachers. An
		// already-assigned co-teacher who has since been suspended must not
		// block an unrelated edit of the teacher list.
		Set<UUID> alreadyAssigned = new HashSet<>(current);
		List<UUID> toValidate = new ArrayList<>();
		for (UUID teacherId : desired) {
			boolean newPrimary = teacherId.equals(desired.get(0)) && !teacherId.equals(course.getTeacherId());
			if (!alreadyAssigned.contains(teacherId) || newPrimary) {
				toValidate.add(teacherId);
			}
		}
		if (!toValidate.isEmpty()) {
			validateTeacherCandidates(toValidate);
		}
		relationsService.syncTeachers(course, desired);
		changes.put("teacherIds", Map.of("from", current.stream().map(UUID::toString).toList(), "to",
				desired.stream().map(UUID::toString).toList()));
	}

	/**
	 * KLASS-T4a (D15): opens/closes the course for NEW registrations. Never
	 * revokes an existing participation or paid access. Audited.
	 */
	public CourseView setRegistrationEnabled(UUID id, boolean enabled) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		if (course.isRegistrationEnabled() != enabled) {
			boolean previous = course.isRegistrationEnabled();
			course.setRegistrationEnabled(enabled);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("previous", previous);
			metadata.put("registrationEnabled", enabled);
			publishAudit(AuthenticatedPrincipalHolder.get(), "course.registration_changed", "course", course.getId(),
					metadata);
		}
		return toView(course);
	}

	/**
	 * KLASS-T4a: persists the admin list order (drag handles). Staff with
	 * {@code COURSES}/{@code CREATE_EDIT} only - a Teacher may not reorder the
	 * tenant's list. {@code courseIds} may be a subset (e.g. the ACTIVE tab):
	 * those courses are rearranged among the positions they already occupy
	 * and every course of the tenant is then renumbered {@code 1..n}. Unknown
	 * or cross-tenant ids are 404; duplicates are 400.
	 */
	public void reorderCourses(List<UUID> courseIds) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT);
		requireDistinct(courseIds, "courseIds");

		List<Course> all = courseRepository.findAll(adminListOrder());
		Map<UUID, Course> byId = all.stream().collect(Collectors.toMap(Course::getId, course -> course));
		for (UUID courseId : courseIds) {
			if (!byId.containsKey(courseId)) {
				throw new NotFoundException("Course not found");
			}
		}
		Set<UUID> moving = new HashSet<>(courseIds);
		List<Course> reordered = new ArrayList<>(all);
		int next = 0;
		for (int position = 0; position < reordered.size(); position++) {
			if (moving.contains(reordered.get(position).getId())) {
				reordered.set(position, byId.get(courseIds.get(next++)));
			}
		}
		for (int position = 0; position < reordered.size(); position++) {
			Course course = reordered.get(position);
			if (!Objects.equals(course.getDisplayOrder(), position + 1)) {
				course.setDisplayOrder(position + 1);
			}
		}
	}

	/**
	 * Reassigns {@code teacher_id}. Tenant Admin only - decided this session,
	 * a hard exception to {@link CourseAccessGuard}'s staff-matrix-or
	 * -ownership check: never {@link DomainArea#COURSES}'s flat matrix (a
	 * Course Coordinator's {@code CREATE_EDIT}/{@code APPROVE} grant must not
	 * authorize this), and never the Teacher-ownership path either (the
	 * course's own owning Teacher is rejected here too). See plan §10/§15(a).
	 * KLASS-T4a: the primary teacher assignment row follows; co-teachers stay.
	 */
	public CourseView reassignTeacher(UUID id, UUID newTeacherId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TENANT_ADMIN_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		Course course = loadCourse(id);
		validateTeacherCandidates(List.of(newTeacherId));
		relationsService.makePrimary(course, newTeacherId);
		return toView(course);
	}

	/**
	 * The ONLY code path permitted to write {@code course.price} (plan
	 * §15(c)) - writes the new price and inserts the {@code
	 * course_price_history} row in this one transaction, then publishes
	 * {@link CoursePriceChangedEvent}. The history row is written
	 * unconditionally regardless of {@code course.status} (a DRAFT course's
	 * price change is recorded too - decided default, plan §13), but a true
	 * no-op (new price equal to the current price) writes nothing at all -
	 * there is no "change" to record.
	 */
	public CourseView changePrice(UUID id, BigDecimal newPrice) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		applyPriceChange(course, newPrice, AuthenticatedPrincipalHolder.get());
		return toView(course);
	}

	private void applyPriceChange(Course course, BigDecimal newPrice, AuthenticatedPrincipal principal) {
		BigDecimal previousPrice = course.getPrice();
		if (previousPrice.compareTo(newPrice) == 0) {
			return;
		}
		course.setPrice(newPrice);
		CoursePriceHistory history = new CoursePriceHistory(tenantContext.getTenantId(), course.getId(),
				principal.userId(), previousPrice, newPrice);
		coursePriceHistoryRepository.save(history);
		eventPublisher.publishEvent(new CoursePriceChangedEvent(tenantContext.getTenantId(), course.getId(),
				principal.userId(), previousPrice, newPrice, Instant.now()));
	}

	/**
	 * Edit-form price: ONE_TIME writes {@code course.price} via the audited
	 * history path; MONTHLY ensures a billing configuration and opens a new
	 * billing period when the amount differs (the closed period keeps its
	 * amount - V39 is append-only); any other model rejects a non-zero price.
	 */
	private void applyEditedPrice(Course course, BigDecimal price, AuthenticatedPrincipal principal) {
		switch (course.getPricingModel()) {
			case ONE_TIME -> applyPriceChange(course, price, principal);
			case MONTHLY -> {
				if (price.signum() <= 0) {
					throw CourseRuleException.invalid("VALIDATION_ERROR", "price",
							"A monthly fee greater than 0 is required");
				}
				String currency = tenantConfigApi.resolveDefaultCurrency(course.getTenantId());
				var configuration = billingConfigurationRepository.findByCourseId(course.getId());
				if (configuration.isEmpty()) {
					billingConfigurationService.createOrUpdateConfiguration(course.getId(), null, currency, false);
				}
				BigDecimal current = billingConfigurationRepository.findByCourseId(course.getId())
					.flatMap(config -> billingPeriodRepository.findCurrentOpenByBillingConfigurationId(config.getId()))
					.map(period -> period.getAmount())
					.orElse(null);
				if (current == null || current.compareTo(price) != 0) {
					String periodCurrency = configuration.map(config -> config.getCurrency()).orElse(currency);
					billingConfigurationService.addBillingPeriod(course.getId(), price, periodCurrency, Instant.now());
				}
			}
			default -> {
				if (price.signum() != 0) {
					throw CourseRuleException.invalid(CourseRuleException.PRICE_NOT_ALLOWED, "price",
							"This payment type has no course price");
				}
			}
		}
	}

	/**
	 * Sets {@code status = PUBLIC}. Gated on {@code CREATE_EDIT} (not {@code
	 * APPROVE}) since both roles the API contract names for this action -
	 * Tenant Admin and Course Coordinator - already hold {@code CREATE_EDIT}
	 * on {@link DomainArea#COURSES} in the existing matrix.
	 */
	public CourseView publish(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		course.setStatus(CourseStatus.PUBLIC);
		return toView(course);
	}

	/**
	 * Reverts {@code status} to {@code DRAFT} - the pre-publish state a
	 * course "remains" in (plan §4 step 4); no separate PRIVATE-transition
	 * endpoint exists in the API contract.
	 */
	public CourseView unpublish(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		course.setStatus(CourseStatus.DRAFT);
		return toView(course);
	}

	/**
	 * Tenant Admin only, via {@link DomainArea#COURSES}'s {@code DELETE}
	 * grant - routed through {@link CourseAccessGuard}, which also
	 * independently rejects any Teacher regardless of ownership.
	 *
	 * <p>Modules/lessons/tags/teacher assignments/links cascade (V14/V85).
	 * {@code course_price_history} and {@code course_commission_rate} rows are
	 * deliberately left untouched (no FK) - financial history survives.
	 * KLASS-T4a: a course that student participation, enrollments, orders or
	 * entitlements reference cannot be hard-deleted (those FKs do not
	 * cascade) - that surfaces as {@code 409 COURSE_IN_USE}; archive it instead.
	 */
	public void deleteCourse(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.DELETE);
		try {
			courseRepository.delete(course);
			courseRepository.flush();
		}
		catch (DataIntegrityViolationException ex) {
			throw CourseRuleException.inUse(CourseRuleException.COURSE_IN_USE,
					"This course has students or payments and cannot be deleted. Archive it instead.");
		}
	}

	/**
	 * The sole write path for {@code course.pricing_model} (Wave 2) - see
	 * {@link Course#setPricingModel}'s javadoc. Audited like {@link
	 * #changePrice}; a true no-op writes no event.
	 */
	public CourseView changePricingModel(UUID id, CoursePricingModel newPricingModel) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		applyPricingModelChange(course, newPricingModel, AuthenticatedPrincipalHolder.get());
		return toView(course);
	}

	private void applyPricingModelChange(Course course, CoursePricingModel newPricingModel,
			AuthenticatedPrincipal principal) {
		CoursePricingModel previous = course.getPricingModel();
		if (previous == newPricingModel) {
			return;
		}
		course.setPricingModel(newPricingModel);
		// Existing session rows keep their historical association until the session is edited;
		// no bulk rewrite of entitlement/payment history occurs on a course model change.
		eventPublisher.publishEvent(new CoursePricingModelChangedEvent(tenantContext.getTenantId(), course.getId(),
				principal.userId(), previous, newPricingModel, Instant.now()));
	}

	/**
	 * Sets {@code archived_at = now()} (Wave 2) - the sole write path for
	 * this field. A true no-op writes no event. Archiving never touches
	 * enrollment/payment data or any child row.
	 */
	public CourseView archiveCourse(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		if (course.isArchived()) {
			return toView(course);
		}
		course.archive(Instant.now());

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		eventPublisher.publishEvent(
				new CourseArchiveStateChangedEvent(tenantContext.getTenantId(), course.getId(), principal.userId(),
						true, Instant.now()));

		return toView(course);
	}

	/** The inverse of {@link #archiveCourse} - see its javadoc. */
	public CourseView unarchiveCourse(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		if (!course.isArchived()) {
			return toView(course);
		}
		course.unarchive();

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		eventPublisher.publishEvent(
				new CourseArchiveStateChangedEvent(tenantContext.getTenantId(), course.getId(), principal.userId(),
						false, Instant.now()));

		return toView(course);
	}

	/**
	 * Creates a brand-new DRAFT course that copies the source course's
	 * classification/content structure only - never its billing
	 * configuration/history, price history, or any enrollment/payment data.
	 * {@code price} is copied only for ONE_TIME. KLASS-T4a: module prices and
	 * lesson session fields are structure and are copied; the clone gets its
	 * own primary teacher assignment; tags, links, cover, commission and
	 * invite links are not copied. Publishes {@link CourseClonedEvent}.
	 */
	public CourseView cloneCourse(UUID id) {
		Course source = loadCourse(id);
		courseAccessGuard.requireCourseAccess(source, PermissionAction.CREATE_EDIT);

		BigDecimal clonedPrice = source.getPricingModel() == CoursePricingModel.ONE_TIME ? source.getPrice()
				: BigDecimal.ZERO;
		String clonedSlug = uniqueCloneSlug(source.getSlug());

		Course clone = new Course(source.getTenantId(), source.getTeacherId(), source.getName() + " (Copy)",
				clonedSlug, source.getCategory(), source.getSubject(), source.getStream(), source.getGrade(),
				source.getAcademicYear(), source.getDescription(), clonedPrice, source.getAccessDurationDays(),
				source.getEnrollmentRule(), CourseStatus.DRAFT);
		clone.setPricingModel(source.getPricingModel());
		clone.setEnrollmentFee(source.getEnrollmentFee());
		clone.setFacilityFeeOverride(source.getFacilityFeeOverride());
		clone.setPostageFee(source.getPostageFee());
		clone = courseRepository.save(clone);
		relationsService.syncTeachers(clone, List.of(source.getTeacherId()));

		for (CourseModule module : courseModuleRepository.findByCourseId(source.getId())) {
			CourseModule clonedModule = new CourseModule(clone.getTenantId(), clone.getId(), module.getTitle(),
					module.getSequence());
			clonedModule.setPrice(module.getPrice());
			clonedModule = courseModuleRepository.save(clonedModule);
			for (CourseLesson lesson : courseLessonRepository.findByModuleId(module.getId())) {
				CourseLesson clonedLesson = new CourseLesson(clone.getTenantId(), clonedModule.getId(),
						lesson.getTitle(), lesson.getSequence());
				clonedLesson.setSchedule(lesson.getStartsAt(), lesson.getEndsAt());
				clonedLesson.setResourceType(lesson.getResourceType());
				clonedLesson.setPaymentPolicy(lesson.getPaymentType(), lesson.getPrice());
				clonedLesson.setDescription(lesson.getDescription());
				clonedLesson.setLessonLabel(lesson.getLessonLabel());
				clonedLesson.setBillingMonth(lesson.getBillingYear(), lesson.getBillingMonth());
				courseLessonRepository.save(clonedLesson);
			}
		}

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		eventPublisher.publishEvent(new CourseClonedEvent(tenantContext.getTenantId(), source.getId(), clone.getId(),
				principal.userId(), Instant.now()));

		return toView(clone);
	}

	/**
	 * A cloned course's slug must still satisfy {@code course}'s tenant
	 * -scoped {@code UNIQUE (tenant_id, slug)} constraint - appends a short
	 * random suffix so repeated clones never collide either.
	 */
	private String uniqueCloneSlug(String sourceSlug) {
		String candidate = sourceSlug + "-copy-" + UUID.randomUUID().toString().substring(0, 8);
		return candidate.length() <= MAX_SLUG_LENGTH ? candidate : candidate.substring(0, MAX_SLUG_LENGTH);
	}

	/**
	 * KLASS-T4a: a slug derived from the course name when the client sends
	 * none - ASCII-folded, lower-case, single hyphens (the same shape
	 * {@code CourseCreateRequest} enforces), suffixed with a short random
	 * token on collision. The DB unique constraint stays the final guard.
	 */
	String uniqueSlugFor(String name) {
		String base = slugify(name);
		if (!courseRepository.existsBySlug(base)) {
			return base;
		}
		for (int attempt = 0; attempt < 5; attempt++) {
			String suffix = UUID.randomUUID().toString().substring(0, 6);
			String trimmedBase = base.length() > MAX_SLUG_LENGTH - 7 ? base.substring(0, MAX_SLUG_LENGTH - 7) : base;
			String candidate = trimmedBase.replaceAll("-+$", "") + "-" + suffix;
			if (!courseRepository.existsBySlug(candidate)) {
				return candidate;
			}
		}
		return "course-" + UUID.randomUUID();
	}

	static String slugify(String name) {
		String folded = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD)
			.replaceAll("\\p{M}", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-+|-+$", "");
		if (folded.length() > 120) {
			folded = folded.substring(0, 120).replaceAll("-+$", "");
		}
		return folded.isEmpty() ? "course" : folded;
	}

	private Course loadCourse(UUID id) {
		// CourseRepository#findById is tenant-scoped by TenantAwareRepositoryImpl
		// - a cross-tenant id is structurally invisible here, surfacing as
		// empty (-> 404), never a cross-tenant read.
		return courseRepository.findById(id).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private static List<UUID> requestedTeachers(List<UUID> teacherIds, UUID legacyTeacherId) {
		List<UUID> requested = teacherIds != null && !teacherIds.isEmpty()
				? List.copyOf(new LinkedHashSet<>(teacherIds))
				: legacyTeacherId != null ? List.of(legacyTeacherId) : List.of();
		if (requested.isEmpty()) {
			throw new InvalidTeacherAssignmentException("teacherId is required");
		}
		if (requested.size() > MAX_TEACHERS) {
			throw new InvalidTeacherAssignmentException("At most " + MAX_TEACHERS + " teachers can be assigned");
		}
		return requested;
	}

	/**
	 * Confirms every candidate teacher id exists in the caller's own tenant
	 * with {@code role = TEACHER} and is not suspended, via {@link
	 * UserProvisioningApi#findTenantUserSummaries} (one batched, tenant-scoped
	 * read). A cross-tenant/unknown id is indistinguishable from a non-teacher
	 * (400, the existing contract). The composite {@code fk_course_teacher}/
	 * {@code fk_course_teacher_assignment_teacher} FKs remain the schema-level
	 * backstop.
	 */
	private void validateTeacherCandidates(List<UUID> teacherIds) {
		Map<UUID, TenantUserSummary> summaries = new HashMap<>();
		userProvisioningApi.findTenantUserSummaries(teacherIds)
			.forEach(summary -> summaries.put(summary.userId(), summary));
		for (UUID teacherId : teacherIds) {
			TenantUserSummary summary = summaries.get(teacherId);
			if (summary == null || !TEACHER_ROLE.equals(summary.roleCode())
					|| "SUSPENDED".equalsIgnoreCase(summary.status())) {
				throw new InvalidTeacherAssignmentException(
						"teacherId does not reference an active Teacher in this tenant");
			}
		}
	}

	private static String requireName(String rawName) {
		String name = rawName == null ? "" : rawName.strip();
		if (name.isEmpty()) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "name", "Name is required");
		}
		return name;
	}

	private static BigDecimal initialCoursePrice(CoursePricingModel pricingModel, BigDecimal price) {
		return switch (pricingModel) {
			case ONE_TIME -> {
				if (price == null) {
					throw CourseRuleException.invalid("VALIDATION_ERROR", "price",
							"A price is required for a Full Course");
				}
				yield price;
			}
			case MONTHLY -> {
				if (price == null || price.signum() <= 0) {
					throw CourseRuleException.invalid("VALIDATION_ERROR", "price",
							"A monthly fee greater than 0 is required");
				}
				yield BigDecimal.ZERO;
			}
			default -> {
				if (price != null && price.signum() != 0) {
					throw CourseRuleException.invalid(CourseRuleException.PRICE_NOT_ALLOWED, "price",
							"This payment type has no course price");
				}
				yield BigDecimal.ZERO;
			}
		};
	}

	/** Payment Valid Days wins over the legacy field; {@code 0} = no limit ({@code NULL}). */
	private static Integer resolveAccessDuration(Integer paymentValidDays, Integer legacyAccessDurationDays) {
		if (paymentValidDays != null) {
			return paymentValidDays == 0 ? null : paymentValidDays;
		}
		return legacyAccessDurationDays;
	}

	/**
	 * Invite links must be absolute {@code https://} URLs with a host (V85
	 * also CHECKs the prefix); blank = none.
	 */
	static String cleanInviteUrl(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String url = raw.strip();
		try {
			URI uri = new URI(url);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !url.startsWith("https://")
					|| url.length() > 500) {
				throw CourseRuleException.invalid("VALIDATION_ERROR", field, "Must be an https:// link");
			}
		}
		catch (URISyntaxException ex) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", field, "Must be an https:// link");
		}
		return url;
	}

	private static void requireDistinct(List<UUID> ids, String field) {
		if (ids == null || ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) {
			throw CourseRuleException.invalid(CourseRuleException.REORDER_MISMATCH, field,
					"The list must contain each id exactly once");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static void recordChange(Map<String, Object> changes, String field, Object from, Object to) {
		if (!Objects.equals(from, to)) {
			Map<String, Object> change = new HashMap<>();
			change.put("from", from);
			change.put("to", to);
			changes.put(field, change);
		}
	}

	private void publishCommissionAudit(AuthenticatedPrincipal principal, UUID courseId, CommissionChange change) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("previousRate", change.previousRate());
		metadata.put("newRate", change.newRate());
		publishAudit(principal, "course.commission_rate_changed", "course", courseId, metadata);
	}

	private void publishAudit(AuthenticatedPrincipal principal, String action, String targetEntity, UUID targetId,
			Map<String, Object> metadata) {
		eventPublisher.publishEvent(new CourseAuditEvent(tenantContext.getTenantId(), principal.userId(), action,
				targetEntity, targetId, metadata, Instant.now()));
	}

	private static boolean isStaffView(AuthenticatedPrincipal principal) {
		return !STUDENT_ROLE.equals(principal.role());
	}

	private CourseView toView(Course course) {
		// No resolved principal (never on a real request path) = the narrower student view.
		boolean staffView = AuthenticatedPrincipalHolder.isSet() && isStaffView(AuthenticatedPrincipalHolder.get());
		if (staffView) {
			return viewAssembler.toView(course, true);
		}
		// KLASS-T8b SEC M1: participant-only fields (invite links, non-PUBLIC linked
		// courses) only for a participating Student.
		return AuthenticatedPrincipalHolder.isSet()
				? viewAssembler.toStudentView(course, courseAccessGuard::isCallerParticipant)
				: viewAssembler.toView(course, false);
	}

}

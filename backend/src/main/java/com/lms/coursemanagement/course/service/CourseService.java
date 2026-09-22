package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseArchiveStateChangedEvent;
import com.lms.coursemanagement.api.CourseClonedEvent;
import com.lms.coursemanagement.api.CoursePriceChangedEvent;
import com.lms.coursemanagement.api.CoursePricingModelChangedEvent;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CoursePriceHistory;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CoursePriceHistoryRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseSpecifications;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 */
@Service
@Transactional
public class CourseService {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

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

	private final CourseCheckoutAmountResolver courseCheckoutAmountResolver;

	public CourseService(CourseRepository courseRepository, CoursePriceHistoryRepository coursePriceHistoryRepository,
			CourseModuleRepository courseModuleRepository, CourseLessonRepository courseLessonRepository,
			TenantContext tenantContext, PermissionCheckService permissionCheckService,
			UserProvisioningApi userProvisioningApi, CourseAccessGuard courseAccessGuard,
			ApplicationEventPublisher eventPublisher, CourseCheckoutAmountResolver courseCheckoutAmountResolver) {
		this.courseRepository = courseRepository;
		this.coursePriceHistoryRepository = coursePriceHistoryRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.courseAccessGuard = courseAccessGuard;
		this.eventPublisher = eventPublisher;
		this.courseCheckoutAmountResolver = courseCheckoutAmountResolver;
	}

	/**
	 * Creates a course. For a Teacher-role caller, {@code teacherId} is
	 * forced to the caller's own id server-side - any value present on
	 * {@code command.teacherId()} is ignored, never trusted, per plan
	 * §12/§15. For a staff caller, {@code CREATE_EDIT} on {@link
	 * DomainArea#COURSES} is required and {@code teacherId} is mandatory,
	 * validated via {@link UserProvisioningApi} (same-tenant, {@code role =
	 * TEACHER}).
	 */
	public CourseView createCourse(NewCourseCommand command) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		UUID teacherId;
		if (TEACHER_ROLE.equals(principal.role())) {
			teacherId = principal.userId();
		}
		else {
			permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT);
			if (command.teacherId() == null) {
				throw new InvalidTeacherAssignmentException("teacherId is required");
			}
			validateTeacherCandidate(command.teacherId());
			teacherId = command.teacherId();
		}

		// Friendlier pre-check for the common case; NOT the race-safe guard -
		// V11's UNIQUE (tenant_id, slug) constraint (surfaced as 409 via
		// GlobalExceptionHandler's DataIntegrityViolationException handler)
		// is what actually prevents a concurrent double-creation from both
		// succeeding.
		if (courseRepository.existsBySlug(command.slug())) {
			throw new ConflictException("A course with this slug already exists");
		}

		Course course = new Course(tenantContext.getTenantId(), teacherId, command.name(), command.slug(),
				command.category(), command.subject(), command.stream(), command.grade(), command.academicYear(),
				command.description(), command.price(), command.accessDurationDays(), command.enrollmentRule(),
				command.status());
		course = courseRepository.save(course);
		return toView(course);
	}

	/**
	 * Teacher-role: {@code filter.teacherId()} is ignored/overridden - the
	 * caller's own id is force-combined with the optional {@code
	 * status}/{@code category} filters, so a Teacher can still narrow their
	 * own listing but can never see another teacher's courses, matching
	 * this module's established "never trust a client-supplied identity
	 * field for a Teacher caller" convention (see {@link #createCourse}).
	 * Staff: requires {@code VIEW} on {@link DomainArea#COURSES} as before,
	 * then applies all three optional filters, including {@code teacherId}
	 * (staff may filter by any teacher in their own tenant). Every filter
	 * is layered on top of, never instead of, {@code CourseRepository}'s
	 * inherited tenant-scoped {@code findAll(Specification, Pageable)}, so
	 * a filter can never be used to widen a result set across tenants. The
	 * requested page size is clamped to {@link #MAX_PAGE_SIZE} regardless of
	 * Spring's own {@code max-page-size} configuration.
	 */
	@Transactional(readOnly = true)
	public PageResponse<CourseView> listCourses(Pageable pageable, CourseListFilter filter) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Pageable safePageable = clampPageSize(pageable);
		Specification<Course> spec = CourseSpecifications.withStatus(filter.status())
			.and(CourseSpecifications.withCategory(filter.category()))
			.and(CourseSpecifications.excludeArchivedUnless(filter.includeArchived()));

		if (TEACHER_ROLE.equals(principal.role())) {
			spec = spec.and(CourseSpecifications.withTeacherId(principal.userId()));
		}
		else {
			permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.VIEW);
			spec = spec.and(CourseSpecifications.withTeacherId(filter.teacherId()));
		}

		Page<Course> page = courseRepository.findAll(spec, safePageable);
		Map<UUID, CheckoutAmount> resolvedAmounts = courseCheckoutAmountResolver.resolveBatch(page.getContent());
		return PageResponse.from(page.map(course -> toView(course, resolvedAmounts)));
	}

	private Pageable clampPageSize(Pageable pageable) {
		if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
	}

	@Transactional(readOnly = true)
	public CourseView getCourse(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		return toView(course);
	}

	public CourseView updateCourse(UUID id, CourseEditCommand command) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);

		if (!course.getSlug().equals(command.slug()) && courseRepository.existsBySlugAndIdNot(command.slug(), id)) {
			throw new ConflictException("A course with this slug already exists");
		}

		course.setName(command.name());
		course.setSlug(command.slug());
		course.setCategory(command.category());
		course.setSubject(command.subject());
		course.setStream(command.stream());
		course.setGrade(command.grade());
		course.setAcademicYear(command.academicYear());
		course.setDescription(command.description());
		course.setEnrollmentRule(command.enrollmentRule());
		course.setAccessDurationDays(command.accessDurationDays());
		return toView(course);
	}

	/**
	 * Reassigns {@code teacher_id}. Tenant Admin only - decided this session,
	 * a hard exception to {@link CourseAccessGuard}'s staff-matrix-or
	 * -ownership check: never {@link DomainArea#COURSES}'s flat matrix (a
	 * Course Coordinator's {@code CREATE_EDIT}/{@code APPROVE} grant must not
	 * authorize this), and never the Teacher-ownership path either (the
	 * course's own owning Teacher is rejected here too). See plan §10/§15(a).
	 */
	public CourseView reassignTeacher(UUID id, UUID newTeacherId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!TENANT_ADMIN_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		Course course = loadCourse(id);
		validateTeacherCandidate(newTeacherId);
		course.setTeacherId(newTeacherId);
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

		BigDecimal previousPrice = course.getPrice();
		if (previousPrice.compareTo(newPrice) == 0) {
			return toView(course);
		}

		course.setPrice(newPrice);

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		CoursePriceHistory history = new CoursePriceHistory(tenantContext.getTenantId(), course.getId(),
				principal.userId(), previousPrice, newPrice);
		coursePriceHistoryRepository.save(history);

		eventPublisher.publishEvent(new CoursePriceChangedEvent(tenantContext.getTenantId(), course.getId(),
				principal.userId(), previousPrice, newPrice, Instant.now()));

		return toView(course);
	}

	/**
	 * Sets {@code status = PUBLIC}. Gated on {@code CREATE_EDIT} (not {@code
	 * APPROVE}) since both roles the API contract names for this action -
	 * Tenant Admin and Course Coordinator - already hold {@code CREATE_EDIT}
	 * on {@link DomainArea#COURSES} in the existing matrix; see this class's
	 * top-level javadoc note in the module report for why a combined {@code
	 * CREATE_EDIT}-or-{@code APPROVE} check was not needed.
	 */
	public CourseView publish(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		course.setStatus(CourseStatus.PUBLIC);
		return toView(course);
	}

	/**
	 * Reverts {@code status} to {@code DRAFT}. The brief left "revert to
	 * DRAFT" vs. a distinct "make PRIVATE" endpoint an open judgment call -
	 * resolved here as DRAFT, since the plan's own flow (§4 step 4) treats
	 * DRAFT as the pre-publish state a course "remains" in, and no separate
	 * PRIVATE-transition endpoint is named anywhere in the API contract
	 * table (§10). A tenant wanting PRIVATE specifically can still reach it
	 * via {@code updateCourse}... actually not - PRIVATE is a status value
	 * excluded from that endpoint too. If a distinct "make private" action is
	 * wanted later, that is new scope, not a silent default here.
	 */
	public CourseView unpublish(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		course.setStatus(CourseStatus.DRAFT);
		return toView(course);
	}

	/**
	 * Tenant Admin only, via {@link DomainArea#COURSES}'s {@code DELETE}
	 * grant (which only Tenant Admin holds in the existing matrix - Course
	 * Coordinator has no {@code DELETE} grant on this area) - routed through
	 * {@link CourseAccessGuard}, which also independently rejects any
	 * Teacher regardless of ownership for the {@code DELETE} action.
	 *
	 * <p>A plain delete of the {@code course} row is sufficient: {@code
	 * fk_course_module_course}/{@code fk_course_lesson_module} declare
	 * {@code ON DELETE CASCADE} (V14), so the database itself removes a
	 * course's {@code course_module}/{@code course_lesson} rows - this no
	 * longer needs manual, ordered delete statements in application code
	 * (V14's own header explains why the DB-enforced version is preferred
	 * over the service-layer-sequencing this replaced).
	 *
	 * <p>{@code course_price_history} rows are deliberately left untouched -
	 * V12 dropped {@code fk_course_price_history_course} specifically so a
	 * course's price-change audit trail survives the course's own deletion,
	 * per root {@code CLAUDE.md}'s "Never delete financial history"
	 * instruction ({@link CoursePriceHistoryRepository} additionally makes
	 * this structural, not just a convention here: every delete-shaped
	 * method on it throws).
	 */
	public void deleteCourse(UUID id) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.DELETE);
		courseRepository.delete(course);
	}

	/**
	 * The sole write path for {@code course.pricing_model} (Wave 2) - see
	 * {@link Course#setPricingModel}'s javadoc. A new course always starts
	 * {@code ONE_TIME}; this is the only way to change it afterward, audited
	 * exactly like {@link #changePrice}. A true no-op (new value equal to
	 * current) writes no event, mirroring {@link #changePrice}'s own no-op
	 * behavior.
	 */
	public CourseView changePricingModel(UUID id, CoursePricingModel newPricingModel) {
		Course course = loadCourse(id);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);

		CoursePricingModel previous = course.getPricingModel();
		if (previous == newPricingModel) {
			return toView(course);
		}
		course.setPricingModel(newPricingModel);

		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		eventPublisher.publishEvent(new CoursePricingModelChangedEvent(tenantContext.getTenantId(), course.getId(),
				principal.userId(), previous, newPricingModel, Instant.now()));

		return toView(course);
	}

	/**
	 * Sets {@code archived_at = now()} (Wave 2) - the sole write path for
	 * this field, per {@link Course#archive}'s javadoc. A true no-op (already
	 * archived) writes no event. Archiving does not touch enrollment/payment
	 * data or any child row at all - it is purely a listing-visibility flag
	 * (see {@link CourseListFilter}'s javadoc), never a delete.
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
	 * Creates a brand-new course (a new id, {@code DRAFT} status, never
	 * archived) that copies the source course's classification/content
	 * structure only - never its billing configuration/billing-period
	 * history/price history, and never any enrollment/payment/review data
	 * (structurally impossible for any of those to reference the new id,
	 * since nothing populates them here). {@code price} is copied only when
	 * the source course's pricing model is {@code ONE_TIME}; every other
	 * pricing model clones with {@code price = 0} (mirroring this schema's
	 * existing "{@code price} is meaningless outside {@code ONE_TIME}"
	 * framing) - {@code pricingModel} itself IS copied, so the clone starts
	 * out requiring the same billing configuration setup the source course
	 * would (deliberately not auto-created here). Gated identically to {@link
	 * #createCourse} would be for the cloning caller's own role (Teacher:
	 * ownership-forced, per {@link CourseAccessGuard}; staff:
	 * {@code CREATE_EDIT}) via the same {@link CourseAccessGuard} check every
	 * other mutation on the SOURCE course uses. Publishes {@link
	 * CourseClonedEvent} (Fix 3, ADR-015/Phase E architecture review) so
	 * {@code AuditLogEventListener} records this the same way every other
	 * course mutation is audited - cloning previously left no audit trail at
	 * all.
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
		clone = courseRepository.save(clone);

		for (CourseModule module : courseModuleRepository.findByCourseId(source.getId())) {
			CourseModule clonedModule = new CourseModule(clone.getTenantId(), clone.getId(), module.getTitle(),
					module.getSequence());
			clonedModule = courseModuleRepository.save(clonedModule);
			for (CourseLesson lesson : courseLessonRepository.findByModuleId(module.getId())) {
				CourseLesson clonedLesson = new CourseLesson(clone.getTenantId(), clonedModule.getId(),
						lesson.getTitle(), lesson.getSequence());
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
	 * -scoped {@code UNIQUE (tenant_id, slug)} constraint - {@code source}'s
	 * own slug is never reused verbatim. Appends a short random suffix rather
	 * than a fixed "-copy" literal so a course cloned more than once never
	 * collides with an earlier clone either.
	 */
	private String uniqueCloneSlug(String sourceSlug) {
		String candidate = sourceSlug + "-copy-" + UUID.randomUUID().toString().substring(0, 8);
		return candidate.length() <= 160 ? candidate : candidate.substring(0, 160);
	}

	private Course loadCourse(UUID id) {
		// CourseRepository#findById is tenant-scoped by TenantAwareRepositoryImpl
		// - a cross-tenant id is structurally invisible here, surfacing as
		// empty (-> 404), never a cross-tenant read.
		return courseRepository.findById(id).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	/**
	 * Confirms a candidate teacher id exists in the caller's own tenant and
	 * has {@code role = TEACHER}, via {@link UserProvisioningApi
	 * #findTenantUserSummaries} - already sufficient for this, per plan §9.
	 * The composite {@code fk_course_teacher} FK (V11) remains the schema
	 * -level backstop; this is the primary, client-safe validation path.
	 */
	private void validateTeacherCandidate(UUID teacherId) {
		List<TenantUserSummary> summaries = userProvisioningApi.findTenantUserSummaries(List.of(teacherId));
		Optional<TenantUserSummary> summary = summaries.stream().findFirst();
		if (summary.isEmpty() || !"TEACHER".equals(summary.get().roleCode())) {
			throw new InvalidTeacherAssignmentException(
					"teacherId does not reference an active Teacher in this tenant");
		}
	}

	/**
	 * Single-course convenience wrapper around {@link #toView(Course, Map)} -
	 * every mutation/detail method here operates on exactly one course, so a
	 * single-entry batch resolution is the correct (never N+1-prone, since
	 * there is only ever one course) call shape for those call sites.
	 */
	private CourseView toView(Course course) {
		Map<UUID, CheckoutAmount> resolvedAmounts = courseCheckoutAmountResolver.resolveBatch(List.of(course));
		return toView(course, resolvedAmounts);
	}

	private static CourseView toView(Course course, Map<UUID, CheckoutAmount> resolvedAmounts) {
		CheckoutAmount resolved = resolvedAmounts.get(course.getId());
		return new CourseView(course.getId(), course.getTeacherId(), course.getName(), course.getSlug(),
				course.getCategory(), course.getSubject(), course.getStream(), course.getGrade(),
				course.getAcademicYear(), course.getDescription(), course.getPrice(),
				course.getAccessDurationDays(), course.getEnrollmentRule(), course.getStatus(),
				course.getPricingModel(), course.getArchivedAt(), course.getCreatedAt(), course.getUpdatedAt(),
				resolved != null ? resolved.amount() : null, resolved != null ? resolved.currency() : null,
				resolved != null && resolved.requiresManualQuote());
	}

}

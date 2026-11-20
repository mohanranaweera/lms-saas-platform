package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseAuditEvent;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceSupport;
import com.lms.coursemanagement.api.LessonResourceTypeCatalog;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseLesson;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.repository.CourseLessonRepository;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Course structure (lessons) CRUD - "structure only", per plan §9/§10. Every
 * public method resolves the parent course AND the parent module (verifying
 * the module actually belongs to that course, not merely to the same
 * tenant) before touching a lesson, then re-runs {@link CourseAccessGuard
 * #requireCourseAccess} - same defense-in-depth discipline as {@link
 * CourseModuleService}.
 *
 * <p>KLASS-T4a ("Sessions"): time window, resource type, leaf payment
 * policy, label/description and server-derived Monthly billing month, validated with
 * stable 400 codes ({@code ENDS_BEFORE_START}, {@code
 * PRICE_REQUIRED_FOR_PAID}, {@code PRICE_NOT_ALLOWED}, {@code
 * RESOURCE_TYPE_UNSUPPORTED}); payment-policy/price changes are audited;
 * persisted reordering; {@code 409 LESSON_IN_USE} when purchased.
 */
@Service
@Transactional
public class CourseLessonService {

	private final CourseRepository courseRepository;

	private final CourseModuleRepository courseModuleRepository;

	private final CourseLessonRepository courseLessonRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantConfigApi tenantConfigApi;

	public CourseLessonService(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseLessonRepository courseLessonRepository, CourseAccessGuard courseAccessGuard,
			ApplicationEventPublisher eventPublisher, TenantConfigApi tenantConfigApi) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseLessonRepository = courseLessonRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.eventPublisher = eventPublisher;
		this.tenantConfigApi = tenantConfigApi;
	}

	@Transactional(readOnly = true)
	public List<CourseLessonView> listLessons(UUID courseId, UUID moduleId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		CourseModule module = loadModule(courseId, moduleId);
		Instant now = Instant.now();
		return courseLessonRepository.findByModuleId(module.getId()).stream().map(l -> toView(l, now)).toList();
	}

	public CourseLessonView createLesson(UUID courseId, UUID moduleId, String title, Integer sequence) {
		return createLesson(courseId, moduleId, LessonInput.titleOnly(title, sequence));
	}

	public CourseLessonView createLesson(UUID courseId, UUID moduleId, LessonInput input) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);
		String title = validate(input);

		int sequence = input.sequence() != null ? input.sequence() : courseLessonRepository.nextSequence(module.getId());
		if (courseLessonRepository.existsByModuleIdAndSequence(module.getId(), sequence)) {
			throw new ConflictException("A lesson with this sequence already exists in this module");
		}

		CourseLesson lesson = new CourseLesson(course.getTenantId(), module.getId(), title, sequence);
		apply(lesson, input, derivedBillingMonth(course, input));
		lesson = courseLessonRepository.save(lesson);
		if (lesson.getPaymentType() == LessonPaymentType.PAID) {
			publishPriceAudit(course, lesson, null, null);
		}
		return toView(lesson, Instant.now());
	}

	/** Pre-KLASS shape: title + sequence only; every session field is left unchanged. */
	public CourseLessonView updateLesson(UUID courseId, UUID moduleId, UUID lessonId, String title,
			Integer sequence) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);
		CourseLesson lesson = loadLesson(module, lessonId);
		String cleanTitle = requireTitle(title);
		applySequence(module, lesson, lessonId, sequence);
		lesson.setTitle(cleanTitle);
		return toView(lesson, Instant.now());
	}

	/** KLASS-T4a edit: replaces every session field; {@code sequence null} = unchanged. */
	public CourseLessonView updateLesson(UUID courseId, UUID moduleId, UUID lessonId, LessonInput input) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);
		CourseLesson lesson = loadLesson(module, lessonId);
		String title = validate(input);
		applySequence(module, lesson, lessonId, input.sequence());

		LessonPaymentType previousType = lesson.getPaymentType();
		BigDecimal previousPrice = lesson.getPrice();
		lesson.setTitle(title);
		apply(lesson, input, derivedBillingMonth(course, input));
		if (previousType != lesson.getPaymentType() || !samePrice(previousPrice, lesson.getPrice())) {
			publishPriceAudit(course, lesson, previousType, previousPrice);
		}
		return toView(lesson, Instant.now());
	}

	/**
	 * KLASS-T4a: rewrites the lesson order within one module. {@code
	 * lessonIds} must be exactly that module's lessons (400 {@code
	 * REORDER_MISMATCH} otherwise). Two-phase so V11's {@code UNIQUE
	 * (tenant_id, module_id, sequence)} is never transiently violated.
	 */
	public List<CourseLessonView> reorderLessons(UUID courseId, UUID moduleId, List<UUID> lessonIds) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);
		List<CourseLesson> lessons = courseLessonRepository.findByModuleId(module.getId());
		Map<UUID, CourseLesson> byId = new LinkedHashMap<>();
		lessons.forEach(lesson -> byId.put(lesson.getId(), lesson));
		CourseModuleService.requireExactPermutation(lessonIds, byId.keySet(), "lessonIds");

		int offset = lessons.stream().mapToInt(CourseLesson::getSequence).max().orElse(0) + 1;
		for (int i = 0; i < lessonIds.size(); i++) {
			byId.get(lessonIds.get(i)).setSequence(offset + i);
		}
		courseLessonRepository.flush();
		for (int i = 0; i < lessonIds.size(); i++) {
			byId.get(lessonIds.get(i)).setSequence(i + 1);
		}
		courseLessonRepository.flush();
		Instant now = Instant.now();
		return courseLessonRepository.findByModuleId(module.getId()).stream().map(l -> toView(l, now)).toList();
	}

	public void deleteLesson(UUID courseId, UUID moduleId, UUID lessonId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		CourseModule module = loadModule(courseId, moduleId);
		CourseLesson lesson = loadLesson(module, lessonId);
		try {
			courseLessonRepository.delete(lesson);
			courseLessonRepository.flush();
		}
		catch (DataIntegrityViolationException ex) {
			// Order lines / content entitlements reference the lesson (V86/V87,
			// no cascade): purchase history wins.
			throw CourseRuleException.inUse(CourseRuleException.LESSON_IN_USE,
					"This session has been purchased and cannot be deleted");
		}
	}

	/**
	 * Validates a lesson input; returns the trimmed title. Package-visible
	 * for unit tests.
	 */
	static String validate(LessonInput input) {
		String title = requireTitle(input.title());
		if (input.startsAt() != null && input.endsAt() != null && !input.endsAt().isAfter(input.startsAt())) {
			throw CourseRuleException.invalid(CourseRuleException.ENDS_BEFORE_START, "endsAt",
					"The end time must be after the start time");
		}
		LessonPaymentType paymentType = input.paymentType() == null ? LessonPaymentType.COVERED_BY_CLASS_FEES
				: input.paymentType();
		if (paymentType == LessonPaymentType.PAID && (input.price() == null || input.price().signum() <= 0)) {
			throw CourseRuleException.invalid(CourseRuleException.PRICE_REQUIRED_FOR_PAID, "price",
					"A price greater than 0 is required for a paid session");
		}
		if (paymentType != LessonPaymentType.PAID && input.price() != null) {
			throw CourseRuleException.invalid(CourseRuleException.PRICE_NOT_ALLOWED, "price",
					"Only paid sessions have a price");
		}
		if (input.resourceType() != null && LessonResourceTypeCatalog.descriptor(input.resourceType())
			.support() == LessonResourceSupport.UNSUPPORTED) {
			throw CourseRuleException.invalid(CourseRuleException.RESOURCE_TYPE_UNSUPPORTED, "resourceType",
					"This resource type is not available yet");
		}
		return title;
	}

	private record BillingMonth(Integer year, Integer month) {
	}

	/** The request's billing fields are compatibility-only, never payment-coverage authority. */
	private BillingMonth derivedBillingMonth(Course course, LessonInput input) {
		LessonPaymentType paymentType = input.paymentType() == null ? LessonPaymentType.COVERED_BY_CLASS_FEES
				: input.paymentType();
		if (course.getPricingModel() != CoursePricingModel.MONTHLY
				|| paymentType != LessonPaymentType.COVERED_BY_CLASS_FEES || input.startsAt() == null) {
			return new BillingMonth(null, null);
		}
		ZoneId zone = ZoneId.of("UTC");
		try {
			Object configured = tenantConfigApi.resolveValue(course.getTenantId(), ConfigDomain.GENERAL,
					"default_timezone").orElse(null);
			if (configured instanceof String name) {
				zone = ZoneId.of(name);
			}
		}
		catch (DateTimeException | IllegalArgumentException ex) {
			// Match the course-content timezone fallback for invalid legacy configuration.
		}
		ZonedDateTime localStart = input.startsAt().atZone(zone);
		if (localStart.getYear() < 2000 || localStart.getYear() > 2100) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "startsAt",
					"Monthly session start must be between 2000 and 2100");
		}
		return new BillingMonth(localStart.getYear(), localStart.getMonthValue());
	}

	private static void apply(CourseLesson lesson, LessonInput input, BillingMonth billingMonth) {
		lesson.setSchedule(input.startsAt(), input.endsAt());
		lesson.setResourceType(input.resourceType());
		lesson.setPaymentPolicy(input.paymentType(), input.price());
		lesson.setDescription(blankToNull(input.description()));
		lesson.setLessonLabel(blankToNull(input.lessonLabel()));
		lesson.setBillingMonth(billingMonth.year(), billingMonth.month());
	}

	private void applySequence(CourseModule module, CourseLesson lesson, UUID lessonId, Integer sequence) {
		if (sequence == null || lesson.getSequence().equals(sequence)) {
			return;
		}
		if (courseLessonRepository.existsByModuleIdAndSequenceAndIdNot(module.getId(), sequence, lessonId)) {
			throw new ConflictException("A lesson with this sequence already exists in this module");
		}
		lesson.setSequence(sequence);
	}

	private static String requireTitle(String title) {
		String clean = title == null ? "" : title.strip();
		if (clean.isEmpty()) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "title", "Name is required");
		}
		return clean;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static boolean samePrice(BigDecimal a, BigDecimal b) {
		if (a == null || b == null) {
			return Objects.equals(a, b);
		}
		return a.compareTo(b) == 0;
	}

	private void publishPriceAudit(Course course, CourseLesson lesson, LessonPaymentType previousType,
			BigDecimal previousPrice) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("courseId", course.getId());
		metadata.put("moduleId", lesson.getModuleId());
		metadata.put("previousPaymentType", previousType == null ? null : previousType.name());
		metadata.put("previousPrice", previousPrice);
		metadata.put("newPaymentType", lesson.getPaymentType().name());
		metadata.put("newPrice", lesson.getPrice());
		eventPublisher.publishEvent(new CourseAuditEvent(course.getTenantId(),
				AuthenticatedPrincipalHolder.get().userId(), "course_lesson.price_changed", "course_lesson",
				lesson.getId(), metadata, Instant.now()));
	}

	private Course loadCourse(UUID courseId) {
		return courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private CourseModule loadModule(UUID courseId, UUID moduleId) {
		return courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
			.orElseThrow(() -> new NotFoundException("Module not found"));
	}

	private CourseLesson loadLesson(CourseModule module, UUID lessonId) {
		return courseLessonRepository.findByIdAndModuleId(lessonId, module.getId())
			.orElseThrow(() -> new NotFoundException("Lesson not found"));
	}

	private static CourseLessonView toView(CourseLesson lesson, Instant now) {
		return new CourseLessonView(lesson.getId(), lesson.getModuleId(), lesson.getTitle(), lesson.getSequence(),
				lesson.getStartsAt(), lesson.getEndsAt(), lesson.getResourceType(), lesson.getPaymentType(),
				lesson.getPrice(), lesson.getDescription(), lesson.getLessonLabel(), lesson.getBillingYear(),
				lesson.getBillingMonth(), lesson.isExpired(now), lesson.getCreatedAt(), lesson.getUpdatedAt());
	}

}

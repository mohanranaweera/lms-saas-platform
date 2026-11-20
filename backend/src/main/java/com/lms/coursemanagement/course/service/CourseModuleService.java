package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.coursemanagement.api.CourseAuditEvent;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseModule;
import com.lms.coursemanagement.course.repository.CourseModuleRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Course structure (modules) CRUD - "structure only", per plan §9/§10. Every
 * public method loads the parent course through the same tenant-scoped
 * {@link CourseRepository#findById} used by {@code CourseService}, then
 * re-runs {@link CourseAccessGuard#requireCourseAccess} before touching
 * anything - independent re-verification per method, matching {@code
 * StaffService}'s defense-in-depth discipline. All module/lesson mutations
 * (create/edit/delete/reorder) are gated on {@code CREATE_EDIT}, not {@code
 * DELETE} - the API contract table names "Staff CREATE_EDIT, or Teacher if
 * owner" for every one of these rows, including delete, unlike course-level
 * delete which is Tenant-Admin-only.
 *
 * <p>KLASS-T4a: optional MODULE-offer {@code price} ({@code null} or {@code
 * > 0}; changes audited), optional {@code sequence} on create (appended),
 * persisted reordering, and {@code 409 MODULE_IN_USE} when an order line or
 * entitlement references the module (or one of its lessons).
 */
@Service
@Transactional
public class CourseModuleService {

	private final CourseRepository courseRepository;

	private final CourseModuleRepository courseModuleRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final ApplicationEventPublisher eventPublisher;

	public CourseModuleService(CourseRepository courseRepository, CourseModuleRepository courseModuleRepository,
			CourseAccessGuard courseAccessGuard, ApplicationEventPublisher eventPublisher) {
		this.courseRepository = courseRepository;
		this.courseModuleRepository = courseModuleRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.eventPublisher = eventPublisher;
	}

	@Transactional(readOnly = true)
	public List<CourseModuleView> listModules(UUID courseId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		return courseModuleRepository.findByCourseId(courseId).stream().map(CourseModuleService::toView).toList();
	}

	public CourseModuleView createModule(UUID courseId, String title, Integer sequence) {
		return createModule(courseId, title, sequence, null);
	}

	public CourseModuleView createModule(UUID courseId, String title, Integer sequence, BigDecimal price) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		String cleanTitle = requireTitle(title);
		validatePrice(price);

		int effectiveSequence = sequence != null ? sequence : courseModuleRepository.nextSequence(courseId);
		if (courseModuleRepository.existsByCourseIdAndSequence(courseId, effectiveSequence)) {
			throw new ConflictException("A module with this sequence already exists in this course");
		}

		CourseModule module = new CourseModule(course.getTenantId(), courseId, cleanTitle, effectiveSequence);
		module.setPrice(price);
		module = courseModuleRepository.save(module);
		if (price != null) {
			publishPriceAudit(course, module, null, price);
		}
		return toView(module);
	}

	/** Pre-KLASS shape: title + sequence only; the module price is left unchanged. */
	public CourseModuleView updateModule(UUID courseId, UUID moduleId, String title, Integer sequence) {
		return update(courseId, moduleId, title, sequence, null, false);
	}

	/** KLASS-T4a edit: {@code price} is replaced ({@code null} clears it); {@code sequence null} = unchanged. */
	public CourseModuleView updateModule(UUID courseId, UUID moduleId, String title, Integer sequence,
			BigDecimal price) {
		return update(courseId, moduleId, title, sequence, price, true);
	}

	private CourseModuleView update(UUID courseId, UUID moduleId, String title, Integer sequence, BigDecimal price,
			boolean replacePrice) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		String cleanTitle = requireTitle(title);

		CourseModule module = courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
			.orElseThrow(() -> new NotFoundException("Module not found"));

		if (sequence != null && !module.getSequence().equals(sequence)
				&& courseModuleRepository.existsByCourseIdAndSequenceAndIdNot(courseId, sequence, moduleId)) {
			throw new ConflictException("A module with this sequence already exists in this course");
		}

		module.setTitle(cleanTitle);
		if (sequence != null) {
			module.setSequence(sequence);
		}
		if (replacePrice) {
			validatePrice(price);
			BigDecimal previous = module.getPrice();
			if (!samePrice(previous, price)) {
				module.setPrice(price);
				publishPriceAudit(course, module, previous, price);
			}
		}
		return toView(module);
	}

	/**
	 * KLASS-T4a: rewrites the module order. {@code moduleIds} must be exactly
	 * this course's modules, each once (400 {@code REORDER_MISMATCH}
	 * otherwise - a foreign id is never resolved, so its existence is never
	 * revealed). Sequences are moved out of the way first and then set to
	 * {@code 1..n}, so V11's non-deferrable {@code UNIQUE (tenant_id,
	 * course_id, sequence)} is never transiently violated.
	 */
	public List<CourseModuleView> reorderModules(UUID courseId, List<UUID> moduleIds) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		List<CourseModule> modules = courseModuleRepository.findByCourseId(courseId);
		Map<UUID, CourseModule> byId = new LinkedHashMap<>();
		modules.forEach(module -> byId.put(module.getId(), module));
		requireExactPermutation(moduleIds, byId.keySet(), "moduleIds");

		int offset = modules.stream().mapToInt(CourseModule::getSequence).max().orElse(0) + 1;
		for (int i = 0; i < moduleIds.size(); i++) {
			byId.get(moduleIds.get(i)).setSequence(offset + i);
		}
		courseModuleRepository.flush();
		for (int i = 0; i < moduleIds.size(); i++) {
			byId.get(moduleIds.get(i)).setSequence(i + 1);
		}
		courseModuleRepository.flush();
		return courseModuleRepository.findByCourseId(courseId).stream().map(CourseModuleService::toView).toList();
	}

	public void deleteModule(UUID courseId, UUID moduleId) {
		Course course = loadCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);

		CourseModule module = courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
			.orElseThrow(() -> new NotFoundException("Module not found"));

		// fk_course_lesson_module declares ON DELETE CASCADE (V14) - the
		// database removes this module's course_lesson rows itself. Order
		// lines / content entitlements referencing the module or one of its
		// lessons do NOT cascade (V86/V87): financial history wins -> 409.
		try {
			courseModuleRepository.delete(module);
			courseModuleRepository.flush();
		}
		catch (DataIntegrityViolationException ex) {
			throw CourseRuleException.inUse(CourseRuleException.MODULE_IN_USE,
					"This module (or one of its sessions) has been purchased and cannot be deleted");
		}
	}

	static void requireExactPermutation(List<UUID> requested, java.util.Set<UUID> actual, String field) {
		if (requested == null || requested.size() != actual.size()
				|| new java.util.HashSet<>(requested).size() != requested.size() || !actual.containsAll(requested)) {
			throw CourseRuleException.invalid(CourseRuleException.REORDER_MISMATCH, field,
					"The list must contain every item of this parent exactly once");
		}
	}

	private static String requireTitle(String title) {
		String clean = title == null ? "" : title.strip();
		if (clean.isEmpty()) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "title", "Name is required");
		}
		return clean;
	}

	private static void validatePrice(BigDecimal price) {
		if (price != null && price.signum() <= 0) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "price", "A module price must be greater than 0");
		}
	}

	private static boolean samePrice(BigDecimal a, BigDecimal b) {
		if (a == null || b == null) {
			return Objects.equals(a, b);
		}
		return a.compareTo(b) == 0;
	}

	private void publishPriceAudit(Course course, CourseModule module, BigDecimal previous, BigDecimal price) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("courseId", course.getId());
		metadata.put("previousPrice", previous);
		metadata.put("newPrice", price);
		eventPublisher.publishEvent(new CourseAuditEvent(course.getTenantId(),
				AuthenticatedPrincipalHolder.get().userId(), "course_module.price_changed", "course_module",
				module.getId(), metadata, Instant.now()));
	}

	private Course loadCourse(UUID courseId) {
		return courseRepository.findById(courseId).orElseThrow(() -> new NotFoundException("Course not found"));
	}

	private static CourseModuleView toView(CourseModule module) {
		return new CourseModuleView(module.getId(), module.getCourseId(), module.getTitle(), module.getSequence(),
				module.getPrice(), module.getCreatedAt(), module.getUpdatedAt());
	}

}

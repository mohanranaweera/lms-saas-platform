package com.lms.coursemanagement.course.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseCommissionRate;
import com.lms.coursemanagement.course.domain.CourseLink;
import com.lms.coursemanagement.course.domain.CourseTag;
import com.lms.coursemanagement.course.domain.CourseTagAssignment;
import com.lms.coursemanagement.course.domain.CourseTeacherAssignment;
import com.lms.coursemanagement.course.repository.CourseCommissionRateRepository;
import com.lms.coursemanagement.course.repository.CourseLinkRepository;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseTagAssignmentRepository;
import com.lms.coursemanagement.course.repository.CourseTagRepository;
import com.lms.coursemanagement.course.repository.CourseTeacherAssignmentRepository;
import com.lms.identityaccessservice.api.PermissionAction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KLASS-T4a course associations: teacher assignments (D12), tags, linked
 * courses (D13) and the append-only Institute Commission (D11). Called from
 * {@link CourseService} inside its transaction AFTER that service has
 * already authorized the caller on the course; this class never trusts a
 * caller-supplied tenant id - every read/write goes through a tenant-aware
 * repository.
 */
@Service
@Transactional
public class CourseRelationsService {

	private final CourseRepository courseRepository;

	private final CourseTeacherAssignmentRepository teacherAssignmentRepository;

	private final CourseTagRepository tagRepository;

	private final CourseTagAssignmentRepository tagAssignmentRepository;

	private final CourseLinkRepository linkRepository;

	private final CourseCommissionRateRepository commissionRateRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final TenantContext tenantContext;

	private final EntityManager entityManager;

	public CourseRelationsService(CourseRepository courseRepository,
			CourseTeacherAssignmentRepository teacherAssignmentRepository, CourseTagRepository tagRepository,
			CourseTagAssignmentRepository tagAssignmentRepository, CourseLinkRepository linkRepository,
			CourseCommissionRateRepository commissionRateRepository, CourseAccessGuard courseAccessGuard,
			TenantContext tenantContext, EntityManager entityManager) {
		this.courseRepository = courseRepository;
		this.teacherAssignmentRepository = teacherAssignmentRepository;
		this.tagRepository = tagRepository;
		this.tagAssignmentRepository = tagAssignmentRepository;
		this.linkRepository = linkRepository;
		this.commissionRateRepository = commissionRateRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.tenantContext = tenantContext;
		this.entityManager = entityManager;
	}

	// ------------------------------------------------------------------
	// Teachers (D12)
	// ------------------------------------------------------------------

	/** Assigned teacher ids, primary first; falls back to {@code course.teacher_id} when no rows exist. */
	@Transactional(readOnly = true)
	public List<UUID> currentTeacherIds(Course course) {
		List<UUID> ids = teacherAssignmentRepository.findByCourseId(course.getId())
			.stream()
			.map(CourseTeacherAssignment::getTeacherId)
			.toList();
		return ids.isEmpty() ? List.of(course.getTeacherId()) : ids;
	}

	/**
	 * Makes the assignment rows equal {@code orderedTeacherIds} (first =
	 * primary) and sets {@code course.teacher_id} to the primary. Callers MUST
	 * have validated every id as a teacher of the current tenant and
	 * authorized any primary change. The old primary row is cleared and
	 * flushed before the new primary is written, so V85's partial unique
	 * index (one primary per course) is never transiently violated.
	 *
	 * @return {@code true} if anything changed.
	 */
	public boolean syncTeachers(Course course, List<UUID> orderedTeacherIds) {
		List<UUID> desired = List.copyOf(new LinkedHashSet<>(orderedTeacherIds));
		if (desired.isEmpty()) {
			throw new IllegalArgumentException("At least one teacher is required");
		}
		UUID primary = desired.get(0);
		List<CourseTeacherAssignment> existing = teacherAssignmentRepository.findByCourseId(course.getId());
		Map<UUID, CourseTeacherAssignment> byTeacher = existing.stream()
			.collect(Collectors.toMap(CourseTeacherAssignment::getTeacherId, Function.identity(), (a, b) -> a,
					LinkedHashMap::new));
		boolean changed = false;

		List<CourseTeacherAssignment> removed = existing.stream()
			.filter(assignment -> !desired.contains(assignment.getTeacherId()))
			.toList();
		if (!removed.isEmpty()) {
			teacherAssignmentRepository.deleteAll(removed);
			changed = true;
		}
		boolean demoted = false;
		for (CourseTeacherAssignment assignment : existing) {
			if (assignment.isPrimary() && !assignment.getTeacherId().equals(primary)
					&& desired.contains(assignment.getTeacherId())) {
				assignment.setPrimary(false);
				demoted = true;
				changed = true;
			}
		}
		if (demoted || !removed.isEmpty()) {
			teacherAssignmentRepository.flush();
		}
		for (UUID teacherId : desired) {
			boolean shouldBePrimary = teacherId.equals(primary);
			CourseTeacherAssignment assignment = byTeacher.get(teacherId);
			if (assignment == null) {
				teacherAssignmentRepository.save(new CourseTeacherAssignment(tenantContext.getTenantId(),
						course.getId(), teacherId, shouldBePrimary));
				changed = true;
			}
			else if (assignment.isPrimary() != shouldBePrimary) {
				assignment.setPrimary(shouldBePrimary);
				changed = true;
			}
		}
		if (!primary.equals(course.getTeacherId())) {
			course.setTeacherId(primary);
			changed = true;
		}
		teacherAssignmentRepository.flush();
		return changed;
	}

	/** Re-points the primary teacher while keeping every co-teacher (used by {@code reassignTeacher}). */
	public void makePrimary(Course course, UUID newPrimaryTeacherId) {
		List<UUID> desired = new ArrayList<>();
		desired.add(newPrimaryTeacherId);
		for (UUID id : currentTeacherIds(course)) {
			if (!id.equals(course.getTeacherId()) && !id.equals(newPrimaryTeacherId)) {
				desired.add(id);
			}
		}
		syncTeachers(course, desired);
	}

	// ------------------------------------------------------------------
	// Tags
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<CourseTag> listTenantTags() {
		return tagRepository.findAllOrderedByName();
	}

	/**
	 * Replaces the course's tags with {@code tagNames}: each name is trimmed
	 * and its inner whitespace collapsed, blanks are dropped, duplicates are
	 * removed case-insensitively (first spelling wins), and tags the tenant
	 * does not have yet are created.
	 *
	 * @return {@code true} if the course's tag set changed.
	 */
	public boolean replaceTags(UUID courseId, List<String> tagNames) {
		Map<String, String> wanted = normalizeTagNames(tagNames);
		Map<String, CourseTag> tagsByKey = new LinkedHashMap<>();
		tagRepository.findByNormalizedNameIn(wanted.keySet()).forEach(tag -> tagsByKey.put(tag.getNormalizedName(), tag));
		for (Map.Entry<String, String> entry : wanted.entrySet()) {
			if (!tagsByKey.containsKey(entry.getKey())) {
				tagsByKey.put(entry.getKey(),
						tagRepository.save(new CourseTag(tenantContext.getTenantId(), entry.getValue())));
			}
		}
		Set<UUID> desiredTagIds = wanted.keySet()
			.stream()
			.map(key -> tagsByKey.get(key).getId())
			.collect(Collectors.toCollection(LinkedHashSet::new));

		List<CourseTagAssignment> existing = tagAssignmentRepository.findByCourseId(courseId);
		Set<UUID> existingTagIds = existing.stream().map(CourseTagAssignment::getTagId).collect(Collectors.toSet());
		if (existingTagIds.equals(desiredTagIds)) {
			return false;
		}
		List<CourseTagAssignment> removed = existing.stream()
			.filter(assignment -> !desiredTagIds.contains(assignment.getTagId()))
			.toList();
		tagAssignmentRepository.deleteAll(removed);
		for (UUID tagId : desiredTagIds) {
			if (!existingTagIds.contains(tagId)) {
				tagAssignmentRepository.save(new CourseTagAssignment(tenantContext.getTenantId(), courseId, tagId));
			}
		}
		return true;
	}

	/** normalized key -> cleaned display name, in first-seen order. Package-visible for unit tests. */
	static Map<String, String> normalizeTagNames(Collection<String> tagNames) {
		Map<String, String> wanted = new LinkedHashMap<>();
		if (tagNames == null) {
			return wanted;
		}
		for (String raw : tagNames) {
			String display = CourseTag.cleanDisplayName(raw);
			if (display == null) {
				continue;
			}
			if (display.length() > CourseTag.MAX_NAME_LENGTH) {
				throw CourseRuleException.invalid("VALIDATION_ERROR", "tagNames",
						"Each tag must be at most " + CourseTag.MAX_NAME_LENGTH + " characters");
			}
			wanted.putIfAbsent(CourseTag.normalize(display), display);
		}
		return wanted;
	}

	// ------------------------------------------------------------------
	// Linked courses (D13)
	// ------------------------------------------------------------------

	/**
	 * Replaces the source courses linked into {@code parent}. Rules: every
	 * id must be a course of the current tenant that the caller may view
	 * (404 otherwise - unknown and cross-tenant ids are indistinguishable);
	 * no self link; one level only - {@code parent} must not itself be linked
	 * into another course, and no source may itself have linked courses
	 * (400 {@code COURSE_LINK_INVALID}). The parent and source course rows
	 * are locked ({@code SELECT ... FOR UPDATE}, in id order) before the
	 * checks, so two concurrent link edits cannot together create a two-level
	 * chain; V85's trigger is the final backstop.
	 *
	 * @return {@code true} if the link set changed.
	 */
	public boolean replaceLinks(Course parent, List<UUID> sourceCourseIds, UUID actorId) {
		List<UUID> desired = sourceCourseIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(sourceCourseIds));
		for (UUID sourceId : desired) {
			if (sourceId.equals(parent.getId())) {
				throw CourseRuleException.invalid(CourseRuleException.COURSE_LINK_INVALID, "linkedCourseIds",
						"A course cannot be linked to itself");
			}
		}
		Map<UUID, Course> sources = new LinkedHashMap<>();
		for (UUID sourceId : desired) {
			Course source = courseRepository.findById(sourceId)
				.orElseThrow(() -> new NotFoundException("Linked course not found"));
			courseAccessGuard.requireCourseAccess(source, PermissionAction.VIEW);
			sources.put(sourceId, source);
		}

		List<CourseLink> existing = linkRepository.findByParentCourseId(parent.getId());
		Set<UUID> existingSourceIds = existing.stream()
			.map(CourseLink::getSourceCourseId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (existingSourceIds.equals(new LinkedHashSet<>(desired))) {
			return false;
		}

		lockInIdOrder(parent, sources.values());
		if (!desired.isEmpty() && linkRepository.existsBySourceCourseId(parent.getId())) {
			throw CourseRuleException.invalid(CourseRuleException.COURSE_LINK_INVALID, "linkedCourseIds",
					"This course is itself linked into another course; links are one level only");
		}
		if (!linkRepository.findByParentCourseIdIn(desired).isEmpty()) {
			throw CourseRuleException.invalid(CourseRuleException.COURSE_LINK_INVALID, "linkedCourseIds",
					"A linked course cannot itself have linked courses; links are one level only");
		}

		List<CourseLink> removed = existing.stream()
			.filter(link -> !desired.contains(link.getSourceCourseId()))
			.toList();
		linkRepository.deleteAll(removed);
		try {
			for (UUID sourceId : desired) {
				if (!existingSourceIds.contains(sourceId)) {
					linkRepository.save(new CourseLink(tenantContext.getTenantId(), parent.getId(), sourceId, actorId));
				}
			}
			linkRepository.flush();
		}
		catch (DataIntegrityViolationException ex) {
			// V85's one-level trigger / self-link CHECK (a concurrent edit that
			// slipped past the locks above).
			throw CourseRuleException.invalid(CourseRuleException.COURSE_LINK_INVALID, "linkedCourseIds",
					"Linked courses must be one level deep and must not include this course");
		}
		return true;
	}

	private void lockInIdOrder(Course parent, Collection<Course> sources) {
		List<Course> toLock = new ArrayList<>(sources);
		toLock.add(parent);
		toLock.sort((a, b) -> a.getId().compareTo(b.getId()));
		// A just-created parent must exist in the database before it can be
		// locked with SELECT ... FOR UPDATE.
		entityManager.flush();
		for (Course course : toLock) {
			entityManager.lock(course, LockModeType.PESSIMISTIC_WRITE);
		}
	}

	// ------------------------------------------------------------------
	// Institute Commission (D11) - append-only
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public Optional<BigDecimal> currentCommissionRate(UUID courseId) {
		return commissionRateRepository.findCurrent(courseId).map(CourseCommissionRate::getRate);
	}

	/** A commission rate change; {@code previousRate} is {@code null} for the first rate. */
	public record CommissionChange(BigDecimal previousRate, BigDecimal newRate) {

	}

	/**
	 * Appends a new {@code course_commission_rate} row only when {@code rate}
	 * differs from the current one. Never updates or deletes an existing row.
	 *
	 * @return the change when a row was appended; empty when nothing changed.
	 */
	public Optional<CommissionChange> appendCommissionRateIfChanged(UUID courseId, BigDecimal rate, UUID actorId) {
		Optional<BigDecimal> current = currentCommissionRate(courseId);
		if (current.isPresent() && current.get().compareTo(rate) == 0) {
			return Optional.empty();
		}
		commissionRateRepository.save(
				new CourseCommissionRate(tenantContext.getTenantId(), courseId, rate, Instant.now(), actorId));
		return Optional.of(new CommissionChange(current.orElse(null), rate));
	}

}

package com.lms.coursemanagement.calendar.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.calendar.domain.CourseClassScheduleRevision;
import com.lms.coursemanagement.calendar.domain.CourseClassScheduleSlot;
import com.lms.coursemanagement.calendar.repository.CourseClassScheduleRevisionRepository;
import com.lms.coursemanagement.calendar.repository.CourseClassScheduleSlotRepository;
import com.lms.coursemanagement.calendar.service.ClassScheduleValidator.SlotInput;
import com.lms.coursemanagement.calendar.service.ClassScheduleValidator.ValidSlot;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.service.CourseAccessGuard;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and saves a course's weekly class schedule (CAL-01 spec §2, §6.1,
 * §6.2). Course lookup is tenant-scoped (another tenant's id is a 404); access
 * is {@link CourseAccessGuard} - VIEW to read, CREATE_EDIT to save (staff
 * matrix, or the owning teacher). Students never read the editor view (404).
 *
 * <p>A save appends a new revision (never updates one) effective at the
 * server clock, in one transaction that locks the course row, checks the
 * optimistic {@code expectedRevisionId} (409 {@code SCHEDULE_CONFLICT}) and
 * writes the {@code COURSE_CLASS_SCHEDULE_UPDATED} audit entry. A save never
 * touches lessons, sessions, attendance, enrollment or payments.
 */
@Service
@Transactional
public class ClassScheduleService {

	public static final String AUDIT_ACTION = "COURSE_CLASS_SCHEDULE_UPDATED";

	private static final String STUDENT_ROLE = "STUDENT";

	private final CourseRepository courseRepository;

	private final CourseClassScheduleRevisionRepository revisionRepository;

	private final CourseClassScheduleSlotRepository slotRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	private final TenantZoneResolver zoneResolver;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final EntityManager entityManager;

	private final Clock clock;

	public ClassScheduleService(CourseRepository courseRepository,
			CourseClassScheduleRevisionRepository revisionRepository, CourseClassScheduleSlotRepository slotRepository,
			CourseAccessGuard courseAccessGuard, TenantContext tenantContext, AuditLogApi auditLogApi,
			TenantZoneResolver zoneResolver, TeacherLookupApi teacherLookupApi,
			UserProvisioningApi userProvisioningApi, EntityManager entityManager, Clock clock) {
		this.courseRepository = courseRepository;
		this.revisionRepository = revisionRepository;
		this.slotRepository = slotRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
		this.zoneResolver = zoneResolver;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	/** A save request: the revision the editor loaded ({@code null} = none), bounds and rows. */
	public record SaveCommand(UUID expectedRevisionId, LocalDate effectiveFrom, LocalDate effectiveTo,
			List<SlotInput> slots) {
	}

	@Transactional(readOnly = true)
	public ClassScheduleView getSchedule(UUID courseId) {
		Course course = loadEditorCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.VIEW);
		Optional<CourseClassScheduleRevision> current = revisionRepository.findCurrent(course.getId());
		return toView(course, current.orElse(null), canEdit(course));
	}

	public ClassScheduleView saveSchedule(UUID courseId, SaveCommand command) {
		Course course = loadEditorCourse(courseId);
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		List<ValidSlot> slots = ClassScheduleValidator.validate(command.effectiveFrom(), command.effectiveTo(),
				command.slots());

		// Serializes concurrent saves of one course (SELECT ... FOR UPDATE).
		entityManager.lock(course, LockModeType.PESSIMISTIC_WRITE);
		CourseClassScheduleRevision previous = revisionRepository.findCurrent(course.getId()).orElse(null);
		UUID currentId = previous == null ? null : previous.getId();
		if (!Objects.equals(currentId, command.expectedRevisionId())) {
			throw new ScheduleConflictException();
		}
		List<CourseClassScheduleSlot> previousSlots = previous == null ? List.of()
				: slotRepository.findByRevisionIdIn(List.of(previous.getId()));

		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant effectiveAt = previous != null && !now.isAfter(previous.getEffectiveAt())
				? previous.getEffectiveAt().plus(1, ChronoUnit.MICROS) : now;
		UUID tenantId = tenantContext.getTenantId();
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		CourseClassScheduleRevision revision = revisionRepository.save(new CourseClassScheduleRevision(tenantId,
				course.getId(), effectiveAt, command.effectiveFrom(), command.effectiveTo(), principal.userId(), now));
		List<CourseClassScheduleSlot> saved = slotRepository.saveAll(slots.stream()
			.map(slot -> new CourseClassScheduleSlot(tenantId, revision.getId(), slot.dayOfWeek(), slot.startTime(),
					slot.endTime()))
			.toList());
		slotRepository.flush();

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("revisionId", revision.getId().toString());
		if (previous != null) {
			metadata.put("previousRevisionId", previous.getId().toString());
		}
		metadata.put("effectiveAt", effectiveAt.toString());
		metadata.put("before", snapshot(previous, previousSlots));
		metadata.put("after", snapshot(revision, saved));
		auditLogApi.record(new AuditLogEntry(principal.userId(), AUDIT_ACTION, "course", course.getId(), null,
				metadata));

		return toView(course, revision, saved, true);
	}

	/** Tenant-scoped load; a Student never reaches the editor view (404, no existence leak). */
	private Course loadEditorCourse(UUID courseId) {
		Course course = courseRepository.findById(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role())) {
			throw new NotFoundException("Course not found");
		}
		return course;
	}

	private boolean canEdit(Course course) {
		try {
			courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
			return true;
		}
		catch (AccessDeniedException | NotFoundException ex) {
			return false;
		}
	}

	private ClassScheduleView toView(Course course, CourseClassScheduleRevision revision, boolean canEdit) {
		List<CourseClassScheduleSlot> slots = revision == null ? List.of()
				: slotRepository.findByRevisionIdIn(List.of(revision.getId()));
		return toView(course, revision, slots, canEdit);
	}

	private ClassScheduleView toView(Course course, CourseClassScheduleRevision revision,
			List<CourseClassScheduleSlot> slots, boolean canEdit) {
		String timezone = zoneResolver.zoneOf(tenantContext.getTenantId()).getId();
		List<ClassScheduleView.Slot> views = slots.stream()
			.sorted(Comparator.comparing(CourseClassScheduleSlot::getDayOfWeek)
				.thenComparing(CourseClassScheduleSlot::getStartTime)
				.thenComparing(CourseClassScheduleSlot::getEndTime))
			.map(slot -> new ClassScheduleView.Slot(slot.getId(), slot.getDayOfWeek(), slot.getStartTime(),
					slot.getEndTime()))
			.toList();
		if (revision == null) {
			return new ClassScheduleView(course.getId(), course.getName(), timezone, null, null, null, null, null,
					canEdit, views);
		}
		return new ClassScheduleView(course.getId(), course.getName(), timezone, revision.getId(),
				revision.getEffectiveFrom(), revision.getEffectiveTo(), revision.getCreatedAt(),
				displayName(revision.getCreatedBy()), canEdit, views);
	}

	private String displayName(UUID userId) {
		Optional<String> teacherName = teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> teacher.userId().equals(userId) && teacher.name() != null && !teacher.name().isBlank())
			.map(TeacherLookupApi.TeacherSummary::name)
			.findFirst();
		if (teacherName.isPresent()) {
			return teacherName.get();
		}
		return userProvisioningApi.findTenantUserSummaries(List.of(userId))
			.stream()
			.map(TenantUserSummary::email)
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(null);
	}

	private static Map<String, Object> snapshot(CourseClassScheduleRevision revision,
			List<CourseClassScheduleSlot> slots) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("effectiveFrom", revision == null || revision.getEffectiveFrom() == null ? null
				: revision.getEffectiveFrom().toString());
		snapshot.put("effectiveTo", revision == null || revision.getEffectiveTo() == null ? null
				: revision.getEffectiveTo().toString());
		List<Map<String, Object>> rows = new ArrayList<>();
		slots.stream()
			.sorted(Comparator.comparing(CourseClassScheduleSlot::getDayOfWeek)
				.thenComparing(CourseClassScheduleSlot::getStartTime))
			.forEach(slot -> {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("dayOfWeek", slot.getDayOfWeek().name());
				row.put("startTime", slot.getStartTime().toString());
				row.put("endTime", slot.getEndTime().toString());
				rows.add(row);
			});
		snapshot.put("slots", rows);
		return snapshot;
	}

}

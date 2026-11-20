package com.lms.coursemanagement.calendar.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CalendarOccurrence;
import com.lms.coursemanagement.api.CalendarOccurrences;
import com.lms.coursemanagement.api.ClassCalendarApi;
import com.lms.coursemanagement.calendar.domain.CourseClassScheduleRevision;
import com.lms.coursemanagement.calendar.domain.CourseClassScheduleSlot;
import com.lms.coursemanagement.calendar.repository.CourseClassScheduleRevisionRepository;
import com.lms.coursemanagement.calendar.repository.CourseClassScheduleSlotRepository;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.CourseRules;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.RevisionRule;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.SessionInput;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.SlotRule;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.domain.CourseTeacherAssignment;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.coursemanagement.course.repository.CourseSpecifications;
import com.lms.coursemanagement.course.repository.CourseTeacherAssignmentRepository;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.liveclassmanagement.api.ClassSessionLookupApi;
import com.lms.liveclassmanagement.api.ClassSessionSummary;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAL-01 calendar reads (docs/specs/class-calendar.md §4-§6): resolves the
 * caller's course scope server-side (§5), loads the schedule revisions and
 * the overlapping {@code class_session} rows of exactly those courses, and
 * hands them to {@link ClassOccurrenceGenerator}. Read-only: an occurrence
 * never creates a lesson/session, starts a meeting, marks attendance, grants
 * access or changes enrollment.
 *
 * <p>Scopes: Teacher - courses whose owner ({@code course.teacher_id}) is the
 * caller, the same rule as the course list and {@code CourseAccessGuard};
 * Student - courses the caller participates in or is currently enrolled in,
 * PUBLIC/PRIVATE only; any other role - {@code COURSES}/{@code VIEW} required
 * (403 otherwise), every course. Archived courses never appear; DRAFT courses
 * only for staff/teachers. A {@code courseId} filter outside the scope is a
 * 404. Times are cached nowhere - every call recomputes (see the module
 * report: caching was deliberately not added).
 */
@Service
@Transactional(readOnly = true)
public class ClassOccurrenceService implements ClassCalendarApi {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String STUDENT_ROLE = "STUDENT";

	private static final Duration COVER_URL_TTL = Duration.ofMinutes(15);

	private static final Logger log = LoggerFactory.getLogger(ClassOccurrenceService.class);

	private final ObjectStorageApi objectStorageApi;

	private final CourseRepository courseRepository;

	private final CourseTeacherAssignmentRepository assignmentRepository;

	private final CourseClassScheduleRevisionRepository revisionRepository;

	private final CourseClassScheduleSlotRepository slotRepository;

	private final ClassSessionLookupApi classSessionLookupApi;

	private final ContentCoverageApi contentCoverageApi;

	private final EnrollmentAccessApi enrollmentAccessApi;

	private final PermissionCheckService permissionCheckService;

	private final TeacherLookupApi teacherLookupApi;

	private final TenantContext tenantContext;

	private final TenantZoneResolver zoneResolver;

	private final Clock clock;

	public ClassOccurrenceService(CourseRepository courseRepository,
			CourseTeacherAssignmentRepository assignmentRepository,
			CourseClassScheduleRevisionRepository revisionRepository, CourseClassScheduleSlotRepository slotRepository,
			ClassSessionLookupApi classSessionLookupApi, ContentCoverageApi contentCoverageApi,
			EnrollmentAccessApi enrollmentAccessApi, PermissionCheckService permissionCheckService,
			TeacherLookupApi teacherLookupApi, TenantContext tenantContext, TenantZoneResolver zoneResolver,
			ObjectStorageApi objectStorageApi, Clock clock) {
		this.objectStorageApi = objectStorageApi;
		this.courseRepository = courseRepository;
		this.assignmentRepository = assignmentRepository;
		this.revisionRepository = revisionRepository;
		this.slotRepository = slotRepository;
		this.classSessionLookupApi = classSessionLookupApi;
		this.contentCoverageApi = contentCoverageApi;
		this.enrollmentAccessApi = enrollmentAccessApi;
		this.permissionCheckService = permissionCheckService;
		this.teacherLookupApi = teacherLookupApi;
		this.tenantContext = tenantContext;
		this.zoneResolver = zoneResolver;
		this.clock = clock;
	}

	/** One course filter option. */
	public record CourseOption(UUID id, String name) {
	}

	/** One public "Today's Classes" entry (allowlisted fields only). */
	public record PublicOccurrence(String id, String courseName, String courseSlug, String coverUrl,
			String teacherName, Instant start, Instant end, java.time.LocalTime startLocalTime,
			java.time.LocalTime endLocalTime, String status) {
	}

	/** Public today projection; {@code enabled = false} carries an empty list. */
	public record PublicToday(boolean enabled, String timezone, Instant serverNow, LocalDate date,
			List<PublicOccurrence> occurrences) {
	}

	@Override
	public CalendarOccurrences listCallerOccurrences(LocalDate from, LocalDate to, UUID courseId) {
		validateRange(from, to);
		Scope scope = callerScope();
		List<Course> courses = scope.courses();
		String courseName = null;
		if (courseId != null) {
			courses = courses.stream().filter(course -> course.getId().equals(courseId)).toList();
			if (courses.isEmpty()) {
				throw new NotFoundException("Course not found");
			}
			courseName = courses.get(0).getName();
		}
		ZoneId zone = zoneResolver.zoneOf(tenantContext.getTenantId());
		Instant now = clock.instant();
		// Students see cancelled recurring occurrences (struck through) but
		// never standalone cancelled sessions (staff only).
		List<CalendarOccurrence> occurrences = generate(zone, from, to, courses, teacherNamesByCourse(courses),
				scope.staff(), now);
		return new CalendarOccurrences(zone.getId(), now, from, to, courseId, courseName, occurrences);
	}

	/** Filter options of {@code GET /api/v1/calendar/courses}: the caller's scope, by name. */
	public List<CourseOption> listCallerCourses() {
		return callerScope().courses()
			.stream()
			.sorted(Comparator.comparing(Course::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Course::getId))
			.map(course -> new CourseOption(course.getId(), course.getName()))
			.toList();
	}

	/**
	 * Today's (tenant-local) occurrences of PUBLIC, non-archived courses for the
	 * anonymous public home. {@code publicEnabled} is the caller's already
	 * -resolved {@code public_page_enabled && show_todays_classes}.
	 */
	public PublicToday publicToday(boolean publicEnabled) {
		ZoneId zone = zoneResolver.zoneOf(tenantContext.getTenantId());
		Instant now = clock.instant();
		LocalDate today = now.atZone(zone).toLocalDate();
		if (!publicEnabled) {
			return new PublicToday(false, zone.getId(), now, today, List.of());
		}
		List<Course> courses = courseRepository.findAll(CourseSpecifications.withStatus(CourseStatus.PUBLIC)
			.and(CourseSpecifications.excludeArchivedUnless(false)));
		Map<UUID, String> primaryTeacher = publicTeacherNames(courses);
		Map<UUID, List<String>> teacherNames = new HashMap<>();
		primaryTeacher.forEach((courseId, name) -> teacherNames.put(courseId, List.of(name)));
		List<CalendarOccurrence> occurrences = generate(zone, today, today.plusDays(1), courses, teacherNames, false,
				now);
		Map<UUID, Course> byId = courses.stream().collect(Collectors.toMap(Course::getId, course -> course));
		Map<UUID, String> covers = new HashMap<>();
		List<PublicOccurrence> projected = occurrences.stream()
			.filter(occurrence -> !occurrence.cancelled())
			.map(occurrence -> new PublicOccurrence(occurrence.id(), occurrence.courseName(),
					occurrence.courseSlug(),
					covers.computeIfAbsent(occurrence.courseId(), id -> coverUrl(byId.get(id))),
					primaryTeacher.get(occurrence.courseId()),
					occurrence.start(), occurrence.end(), occurrence.startLocalTime(), occurrence.endLocalTime(),
					occurrence.status()))
			.toList();
		return new PublicToday(true, zone.getId(), now, today, projected);
	}

	// ------------------------------------------------------------------

	private static void validateRange(LocalDate from, LocalDate to) {
		if (from == null || to == null) {
			throw new FieldValidationException("Choose a date range.",
					List.of(new FieldError(from == null ? "from" : "to", "This date is required.")));
		}
		if (!to.isAfter(from)) {
			throw new FieldValidationException("Choose a valid date range.",
					List.of(new FieldError("to", "The end date must be after the start date.")));
		}
		if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
			throw new FieldValidationException("Choose a shorter date range.", List
				.of(new FieldError("to", "A calendar request can cover at most " + MAX_RANGE_DAYS + " days.")));
		}
	}

	private record Scope(boolean staff, List<Course> courses) {
	}

	private Scope callerScope() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Specification<Course> active = CourseSpecifications.excludeArchivedUnless(false);
		if (TEACHER_ROLE.equals(principal.role())) {
			return new Scope(true,
					courseRepository.findAll(active.and(CourseSpecifications.withTeacherId(principal.userId()))));
		}
		if (STUDENT_ROLE.equals(principal.role())) {
			Set<UUID> ids = new HashSet<>(contentCoverageApi.findCallerCourseIds());
			ids.addAll(enrollmentAccessApi.listCurrentlyEnrolledCourseIds(principal.userId()));
			if (ids.isEmpty()) {
				return new Scope(false, List.of());
			}
			List<Course> courses = courseRepository.findAllById(ids)
				.stream()
				.filter(course -> !course.isArchived() && course.getStatus() != CourseStatus.DRAFT)
				.toList();
			return new Scope(false, courses);
		}
		permissionCheckService.requirePermission(DomainArea.COURSES, PermissionAction.VIEW);
		return new Scope(true, courseRepository.findAll(active));
	}

	private List<CalendarOccurrence> generate(ZoneId zone, LocalDate from, LocalDate to, List<Course> courses,
			Map<UUID, List<String>> teacherNames, boolean includeStandaloneCancelled, Instant now) {
		if (courses.isEmpty()) {
			return List.of();
		}
		Instant windowStart = from.minusDays(1).atStartOfDay(zone).toInstant();
		Instant windowEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
		List<UUID> courseIds = courses.stream().map(Course::getId).toList();

		Map<UUID, List<CourseClassScheduleRevision>> revisionsByCourse = revisionRepository
			.findAllForCourses(courseIds)
			.stream()
			.collect(Collectors.groupingBy(CourseClassScheduleRevision::getCourseId));
		// A course's first revision governs without a lower bound (there is no earlier
		// schedule history to preserve); every later revision governs from its save
		// instant. Only the revision in force at the window start and the later ones
		// starting before the window end can govern an occurrence in the window.
		List<CourseClassScheduleRevision> relevant = new ArrayList<>();
		Set<UUID> firstRevisionIds = new HashSet<>();
		revisionsByCourse.values().forEach(list -> {
			List<CourseClassScheduleRevision> sorted = list.stream()
				.sorted(Comparator.comparing(CourseClassScheduleRevision::getEffectiveAt))
				.toList();
			firstRevisionIds.add(sorted.get(0).getId());
			int first = 0;
			int last = 0;
			for (int index = 0; index < sorted.size(); index++) {
				Instant effectiveAt = sorted.get(index).getEffectiveAt();
				if (!effectiveAt.isAfter(windowStart)) {
					first = index;
				}
				if (index == 0 || effectiveAt.isBefore(windowEnd)) {
					last = index;
				}
			}
			relevant.addAll(sorted.subList(first, last + 1));
		});
		Map<UUID, List<SlotRule>> slotsByRevision = slotRepository
			.findByRevisionIdIn(relevant.stream().map(CourseClassScheduleRevision::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(CourseClassScheduleSlot::getRevisionId, Collectors.mapping(
					slot -> new SlotRule(slot.getId(), slot.getDayOfWeek(), slot.getStartTime(), slot.getEndTime()),
					Collectors.toList())));
		Map<UUID, List<RevisionRule>> rulesByCourse = relevant.stream()
			.collect(Collectors.groupingBy(CourseClassScheduleRevision::getCourseId,
					Collectors.mapping(revision -> new RevisionRule(revision.getId(),
							firstRevisionIds.contains(revision.getId()) ? Instant.MIN : revision.getEffectiveAt(),
							revision.getEffectiveFrom(), revision.getEffectiveTo(),
							slotsByRevision.getOrDefault(revision.getId(), List.of())), Collectors.toList())));

		List<CourseRules> rules = courses.stream()
			.map(course -> new CourseRules(course.getId(), course.getName(), course.getSlug(),
					teacherNames.getOrDefault(course.getId(), List.of()),
					rulesByCourse.getOrDefault(course.getId(), List.of())))
			.toList();

		List<ClassSessionSummary> sessions = classSessionLookupApi.findOverlapping(courseIds, windowStart, windowEnd);
		Map<UUID, String> sessionTeacherNames = teacherNamesById(
				sessions.stream().map(ClassSessionSummary::teacherId).collect(Collectors.toSet()));
		List<SessionInput> inputs = sessions.stream()
			.map(session -> new SessionInput(session.id(), session.courseId(), session.title(),
					session.scheduledStart(), session.scheduledEnd(),
					ClassSessionSummary.STATUS_CANCELLED.equals(session.status()),
					sessionTeacherNames.get(session.teacherId())))
			.toList();
		return ClassOccurrenceGenerator.generate(zone, from, to, rules, inputs, includeStandaloneCancelled, now);
	}

	/** Display names of every assigned teacher of each course (primary first); no email fallback. */
	private Map<UUID, List<String>> teacherNamesByCourse(List<Course> courses) {
		if (courses.isEmpty()) {
			return Map.of();
		}
		List<UUID> courseIds = courses.stream().map(Course::getId).toList();
		Map<UUID, List<CourseTeacherAssignment>> assignments = assignmentRepository.findByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.groupingBy(CourseTeacherAssignment::getCourseId, LinkedHashMap::new,
					Collectors.toList()));
		Set<UUID> teacherIds = new HashSet<>();
		courses.forEach(course -> teacherIds.add(course.getTeacherId()));
		assignments.values().forEach(list -> list.forEach(a -> teacherIds.add(a.getTeacherId())));
		Map<UUID, String> names = teacherNamesById(teacherIds);
		Map<UUID, List<String>> result = new HashMap<>();
		for (Course course : courses) {
			List<UUID> ids = assignments.containsKey(course.getId())
					? assignments.get(course.getId()).stream().map(CourseTeacherAssignment::getTeacherId).toList()
					: List.of(course.getTeacherId());
			result.put(course.getId(), ids.stream().map(names::get).filter(name -> name != null).distinct().toList());
		}
		return result;
	}

	private Map<UUID, String> teacherNamesById(Set<UUID> teacherIds) {
		if (teacherIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>();
		teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> teacherIds.contains(teacher.userId()) && teacher.name() != null
					&& !teacher.name().isBlank())
			.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		return names;
	}

	private Map<UUID, String> approvedTeacherNames() {
		Map<UUID, String> names = new HashMap<>();
		teacherLookupApi.listApprovedActiveTeachers()
			.stream()
			.filter(teacher -> teacher.name() != null && !teacher.name().isBlank())
			.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		return names;
	}

	/** Short-lived signed cover URL (same lifetime as the storefront's), {@code null} when absent. */
	private String coverUrl(Course course) {
		if (course == null || course.getCoverImageObjectKey() == null) {
			return null;
		}
		try {
			return objectStorageApi.generateSignedDownloadUrl(course.getCoverImageObjectKey(), COVER_URL_TTL).url();
		}
		catch (RuntimeException ex) {
			log.warn("Could not sign the cover image URL of course {}", course.getId(), ex);
			return null;
		}
	}

	/** Public: the primary teacher's name, only for an approved, active teacher (as the storefront). */
	private Map<UUID, String> publicTeacherNames(List<Course> courses) {
		if (courses.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> approved = approvedTeacherNames();
		Map<UUID, String> result = new HashMap<>();
		courses.forEach(course -> {
			String name = approved.get(course.getTeacherId());
			if (name != null) {
				result.put(course.getId(), name);
			}
		});
		return result;
	}

}

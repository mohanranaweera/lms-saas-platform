package com.lms.coursemanagement.calendar.service;

import com.lms.coursemanagement.api.CalendarOccurrence;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CAL-01 occurrence generation (docs/specs/class-calendar.md §3-§4) - a pure
 * function of its inputs: no repository, clock or request context, so every
 * time rule is unit-testable with explicit zones and instants.
 *
 * <ul>
 * <li>Recurring slots are tenant-local wall-clock times: start = {@code
 * ZonedDateTime.of(date, startTime, zone)} ({@code java.time} DST policy - a
 * nonexistent local time moves forward by the gap, an ambiguous one takes the
 * earlier offset). An occurrence whose DST-adjusted end is not after its start
 * does not exist that day.</li>
 * <li>A revision governs occurrences starting in {@code [effectiveAt, next
 * revision's effectiveAt)} (the occurrence service passes {@link Instant#MIN}
 * for a course's first revision, which has no history to preserve); its optional {@code effectiveFrom}/{@code
 * effectiveTo} bound the tenant-local start date, inclusive.</li>
 * <li>Intervals are half-open; an occurrence is in range iff {@code start <
 * rangeEnd && end > rangeStart}. Range days are {@code date.atStartOfDay(zone)}.</li>
 * <li>A non-cancelled session overlapping a recurring occurrence of the same
 * course on the same tenant-local start date replaces it; a cancelled one marks
 * it cancelled. Never both the original and the replacement.</li>
 * </ul>
 */
public final class ClassOccurrenceGenerator {

	public static final String STATUS_SCHEDULED = "SCHEDULED";

	public static final String STATUS_LIVE = "LIVE";

	public static final String STATUS_OVER = "OVER";

	public static final String STATUS_CANCELLED = "CANCELLED";

	/** Deterministic order: start, end, course name (case-insensitive), id. */
	public static final Comparator<CalendarOccurrence> ORDER = Comparator.comparing(CalendarOccurrence::start)
		.thenComparing(CalendarOccurrence::end)
		.thenComparing(CalendarOccurrence::courseName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
		.thenComparing(CalendarOccurrence::id);

	private static final DateTimeFormatter ID_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private ClassOccurrenceGenerator() {
	}

	/** One weekly row of a revision. */
	public record SlotRule(UUID slotId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
	}

	/** One saved revision of a course's schedule. */
	public record RevisionRule(UUID revisionId, Instant effectiveAt, LocalDate effectiveFrom, LocalDate effectiveTo,
			List<SlotRule> slots) {

		public RevisionRule {
			slots = slots == null ? List.of() : List.copyOf(slots);
		}

		boolean coversDate(LocalDate date) {
			return (effectiveFrom == null || !date.isBefore(effectiveFrom))
					&& (effectiveTo == null || !date.isAfter(effectiveTo));
		}

	}

	/** An in-scope course with its revisions (any order) and display teacher names. */
	public record CourseRules(UUID courseId, String courseName, String courseSlug, List<String> teacherNames,
			List<RevisionRule> revisions) {

		public CourseRules {
			teacherNames = teacherNames == null ? List.of() : List.copyOf(teacherNames);
			revisions = revisions == null ? List.of()
					: revisions.stream().sorted(Comparator.comparing(RevisionRule::effectiveAt)).toList();
		}

	}

	/** A {@code class_session} row of an in-scope course. {@code teacherName} may be {@code null}. */
	public record SessionInput(UUID sessionId, UUID courseId, String title, Instant start, Instant end,
			boolean cancelled, String teacherName) {
	}

	/**
	 * @param zone tenant zone
	 * @param from first tenant-local date (inclusive)
	 * @param to end tenant-local date (exclusive)
	 * @param courses in-scope courses; sessions of other courses are ignored
	 * @param sessions sessions overlapping the range (a wider set is fine)
	 * @param includeStandaloneCancelled emit cancelled sessions that replace
	 * no recurring occurrence (staff only)
	 * @param now the instant {@code status} is computed at
	 */
	public static List<CalendarOccurrence> generate(ZoneId zone, LocalDate from, LocalDate to,
			List<CourseRules> courses, List<SessionInput> sessions, boolean includeStandaloneCancelled, Instant now) {
		Instant rangeStart = from.atStartOfDay(zone).toInstant();
		Instant rangeEnd = to.atStartOfDay(zone).toInstant();
		Map<UUID, CourseRules> byId = new LinkedHashMap<>();
		courses.forEach(course -> byId.put(course.courseId(), course));

		// Recurring occurrences over one extra day on each side, so a session
		// that crosses a range edge still finds (and replaces) its slot.
		List<Recurring> recurring = new ArrayList<>();
		for (LocalDate date = from.minusDays(1); date.isBefore(to.plusDays(1)); date = date.plusDays(1)) {
			for (CourseRules course : byId.values()) {
				addRecurring(recurring, zone, date, course);
			}
		}

		List<SessionInput> ordered = sessions.stream()
			.filter(session -> byId.containsKey(session.courseId()))
			.filter(session -> session.end().isAfter(session.start()))
			.sorted(Comparator.comparing(SessionInput::start).thenComparing(SessionInput::sessionId))
			.toList();

		List<CalendarOccurrence> result = new ArrayList<>();
		Set<Recurring> replaced = new HashSet<>();
		for (SessionInput session : ordered) {
			if (session.cancelled()) {
				continue;
			}
			List<Recurring> matches = matching(recurring, replaced, session, zone);
			replaced.addAll(matches);
			String replacesId = matches.isEmpty() ? null : matches.get(0).id();
			result.add(sessionOccurrence(byId.get(session.courseId()), session, zone, false, replacesId));
		}
		Set<Recurring> cancelled = new HashSet<>();
		for (SessionInput session : ordered) {
			if (!session.cancelled()) {
				continue;
			}
			List<Recurring> matches = matching(recurring, replaced, session, zone);
			if (!matches.isEmpty()) {
				cancelled.addAll(matches);
			}
			else if (includeStandaloneCancelled) {
				result.add(sessionOccurrence(byId.get(session.courseId()), session, zone, true, null));
			}
		}
		for (Recurring occurrence : recurring) {
			if (!replaced.contains(occurrence)) {
				result.add(occurrence.toOccurrence(zone, cancelled.contains(occurrence)));
			}
		}

		return result.stream()
			.filter(occurrence -> occurrence.start().isBefore(rangeEnd) && occurrence.end().isAfter(rangeStart))
			.map(occurrence -> withStatus(occurrence, now))
			.sorted(ORDER)
			.toList();
	}

	/** Schedule-based status at {@code now}; a cancelled occurrence is always CANCELLED. */
	public static String statusAt(Instant start, Instant end, boolean cancelled, Instant now) {
		if (cancelled) {
			return STATUS_CANCELLED;
		}
		if (now.isBefore(start)) {
			return STATUS_SCHEDULED;
		}
		return now.isBefore(end) ? STATUS_LIVE : STATUS_OVER;
	}

	public static String recurringId(UUID slotId, LocalDate date) {
		return "r_" + slotId + "_" + ID_DATE.format(date);
	}

	public static String sessionId(UUID sessionId) {
		return "s_" + sessionId;
	}

	private static CalendarOccurrence withStatus(CalendarOccurrence o, Instant now) {
		return new CalendarOccurrence(o.id(), o.source(), o.courseId(), o.courseName(), o.courseSlug(), o.start(),
				o.end(), o.localDate(), o.startLocalTime(), o.endLocalTime(), o.teacherNames(),
				statusAt(o.start(), o.end(), o.cancelled(), now), o.cancelled(), o.sessionId(), o.sessionTitle(),
				o.replacesOccurrenceId());
	}

	private static void addRecurring(List<Recurring> into, ZoneId zone, LocalDate date, CourseRules course) {
		List<RevisionRule> revisions = course.revisions();
		for (int index = 0; index < revisions.size(); index++) {
			RevisionRule revision = revisions.get(index);
			Instant nextEffectiveAt = index + 1 < revisions.size() ? revisions.get(index + 1).effectiveAt() : null;
			if (!revision.coversDate(date)) {
				continue;
			}
			for (SlotRule slot : revision.slots()) {
				if (slot.dayOfWeek() != date.getDayOfWeek()) {
					continue;
				}
				Instant start = ZonedDateTime.of(date, slot.startTime(), zone).toInstant();
				Instant end = ZonedDateTime.of(date, slot.endTime(), zone).toInstant();
				if (!end.isAfter(start) || start.isBefore(revision.effectiveAt())
						|| (nextEffectiveAt != null && !start.isBefore(nextEffectiveAt))) {
					continue;
				}
				into.add(new Recurring(recurringId(slot.slotId(), date), course, date, start, end));
			}
		}
	}

	/** Not-yet-replaced recurring occurrences of the session's course and local start date that overlap it. */
	private static List<Recurring> matching(List<Recurring> recurring, Set<Recurring> replaced, SessionInput session,
			ZoneId zone) {
		LocalDate sessionDate = session.start().atZone(zone).toLocalDate();
		return recurring.stream()
			.filter(occurrence -> !replaced.contains(occurrence))
			.filter(occurrence -> occurrence.course().courseId().equals(session.courseId()))
			.filter(occurrence -> occurrence.date().equals(sessionDate))
			.filter(occurrence -> occurrence.start().isBefore(session.end())
					&& occurrence.end().isAfter(session.start()))
			.sorted(Comparator.comparing(Recurring::start).thenComparing(Recurring::id))
			.toList();
	}

	private static CalendarOccurrence sessionOccurrence(CourseRules course, SessionInput session, ZoneId zone,
			boolean cancelled, String replacesId) {
		ZonedDateTime start = session.start().atZone(zone);
		ZonedDateTime end = session.end().atZone(zone);
		List<String> teachers = session.teacherName() != null && !session.teacherName().isBlank()
				? List.of(session.teacherName()) : course.teacherNames();
		return new CalendarOccurrence(sessionId(session.sessionId()), CalendarOccurrence.SOURCE_SESSION,
				course.courseId(), course.courseName(), course.courseSlug(), session.start(), session.end(),
				start.toLocalDate(), start.toLocalTime().withSecond(0).withNano(0),
				end.toLocalTime().withSecond(0).withNano(0), teachers, null, cancelled, session.sessionId(),
				session.title(), replacesId);
	}

	private record Recurring(String id, CourseRules course, LocalDate date, Instant start, Instant end) {

		CalendarOccurrence toOccurrence(ZoneId zone, boolean cancelled) {
			return new CalendarOccurrence(id, CalendarOccurrence.SOURCE_RECURRING, course.courseId(),
					course.courseName(), course.courseSlug(), start, end, date, start.atZone(zone).toLocalTime(),
					end.atZone(zone).toLocalTime(), course.teacherNames(), null, cancelled, null, null, null);
		}

		// Identity is the stable id (slot + date); records' structural
		// equality over the course rules would be needlessly expensive.
		@Override
		public boolean equals(Object other) {
			return other instanceof Recurring that && id.equals(that.id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}

	}

}

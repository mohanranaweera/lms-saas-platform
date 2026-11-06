package com.lms.coursemanagement.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.api.CalendarOccurrence;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.CourseRules;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.RevisionRule;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.SessionInput;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceGenerator.SlotRule;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CAL-01 occurrence generation (docs/specs/class-calendar.md §3-§4): pure,
 * deterministic - explicit zones and instants, no clock, no sleeps.
 */
class ClassOccurrenceGeneratorTest {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

	/** Before every generated range below - all occurrences SCHEDULED unless a test says otherwise. */
	private static final Instant EARLY_NOW = Instant.parse("2019-01-01T00:00:00Z");

	private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-00000000c001");

	private static int slotSeq;

	private static SlotRule slot(DayOfWeek day, String start, String end) {
		slotSeq++;
		return new SlotRule(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", slotSeq)), day,
				LocalTime.parse(start), LocalTime.parse(end));
	}

	private static RevisionRule revision(Instant effectiveAt, LocalDate from, LocalDate to, SlotRule... slots) {
		return new RevisionRule(UUID.randomUUID(), effectiveAt, from, to, List.of(slots));
	}

	private static CourseRules course(UUID id, String name, RevisionRule... revisions) {
		return new CourseRules(id, name, name.toLowerCase().replace(' ', '-'), List.of("Teacher One"),
				List.of(revisions));
	}

	private static List<CalendarOccurrence> generate(ZoneId zone, String from, String to, List<CourseRules> courses,
			List<SessionInput> sessions) {
		return ClassOccurrenceGenerator.generate(zone, LocalDate.parse(from), LocalDate.parse(to), courses, sessions,
				true, EARLY_NOW);
	}

	private static List<String> dates(List<CalendarOccurrence> occurrences) {
		return occurrences.stream().map(o -> o.localDate().toString()).toList();
	}

	// ------------------------------------------------------------------

	@Test
	void emptyScheduleProducesNothing() {
		assertThat(generate(COLOMBO, "2026-11-01", "2026-12-06", List.of(), List.of())).isEmpty();
		assertThat(generate(COLOMBO, "2026-11-01", "2026-12-06", List.of(course(COURSE, "Bio")), List.of()))
			.isEmpty();
		assertThat(generate(COLOMBO, "2026-11-01", "2026-12-06",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null))), List.of()))
			.isEmpty();
	}

	@Test
	void singleDayUsesTenantLocalWallClockAndStableIds() {
		SlotRule monday = slot(DayOfWeek.MONDAY, "08:00", "10:00");
		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-02", "2026-11-03",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, monday))), List.of());

		assertThat(result).hasSize(1);
		CalendarOccurrence o = result.get(0);
		assertThat(o.id()).isEqualTo("r_" + monday.slotId() + "_20261102");
		assertThat(o.source()).isEqualTo("RECURRING");
		assertThat(o.start()).isEqualTo(Instant.parse("2026-11-02T02:30:00Z"));
		assertThat(o.end()).isEqualTo(Instant.parse("2026-11-02T04:30:00Z"));
		assertThat(o.localDate()).isEqualTo(LocalDate.parse("2026-11-02"));
		assertThat(o.startLocalTime()).isEqualTo(LocalTime.of(8, 0));
		assertThat(o.endLocalTime()).isEqualTo(LocalTime.of(10, 0));
		assertThat(o.teacherNames()).containsExactly("Teacher One");
		assertThat(o.status()).isEqualTo("SCHEDULED");
		assertThat(o.cancelled()).isFalse();
		assertThat(o.sessionId()).isNull();
		assertThat(o.replacesOccurrenceId()).isNull();
	}

	@Test
	void multipleDaysAndSeveralSlotsPerDayAreSorted() {
		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-01", "2026-11-08",
				List.of(course(COURSE, "Bio",
						revision(LONG_AGO, null, null, slot(DayOfWeek.WEDNESDAY, "09:00", "10:00"),
								slot(DayOfWeek.MONDAY, "14:00", "16:00"), slot(DayOfWeek.MONDAY, "08:00", "10:00")))),
				List.of());

		assertThat(result).extracting(o -> o.localDate() + " " + o.startLocalTime())
			.containsExactly("2026-11-02 08:00", "2026-11-02 14:00", "2026-11-04 09:00");
	}

	@Test
	void effectiveBoundsAreInclusiveTenantLocalDates() {
		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-01", "2026-12-01",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, LocalDate.parse("2026-11-09"),
						LocalDate.parse("2026-11-16"), slot(DayOfWeek.MONDAY, "08:00", "10:00")))),
				List.of());

		assertThat(dates(result)).containsExactly("2026-11-09", "2026-11-16");
	}

	@Test
	void rangeIsHalfOpenAndCrossesMonthAndYearBoundaries() {
		CourseRules daily = course(COURSE, "Bio",
				revision(LONG_AGO, null, null, slot(DayOfWeek.MONDAY, "08:00", "09:00"),
						slot(DayOfWeek.TUESDAY, "08:00", "09:00"), slot(DayOfWeek.WEDNESDAY, "08:00", "09:00"),
						slot(DayOfWeek.THURSDAY, "08:00", "09:00"), slot(DayOfWeek.FRIDAY, "08:00", "09:00"),
						slot(DayOfWeek.SATURDAY, "08:00", "09:00"), slot(DayOfWeek.SUNDAY, "08:00", "09:00")));

		assertThat(dates(generate(COLOMBO, "2026-11-29", "2026-12-02", List.of(daily), List.of())))
			.containsExactly("2026-11-29", "2026-11-30", "2026-12-01");
		List<CalendarOccurrence> newYear = generate(COLOMBO, "2026-12-30", "2027-01-02", List.of(daily), List.of());
		assertThat(dates(newYear)).containsExactly("2026-12-30", "2026-12-31", "2027-01-01");
		assertThat(newYear.get(2).id()).endsWith("_20270101");
	}

	@Test
	void leapDayIsGenerated() {
		List<CalendarOccurrence> result = generate(COLOMBO, "2028-02-28", "2028-03-02",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, slot(DayOfWeek.TUESDAY, "08:00", "10:00")))),
				List.of());

		assertThat(dates(result)).containsExactly("2028-02-29");
		assertThat(result.get(0).id()).endsWith("_20280229");
	}

	@Test
	void tenantZoneWinsOverTheJvmDefaultZone() {
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
			List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-02", "2026-11-03",
					List.of(course(COURSE, "Bio",
							revision(LONG_AGO, null, null, slot(DayOfWeek.MONDAY, "00:30", "01:30")))),
					List.of());

			assertThat(result).hasSize(1);
			assertThat(result.get(0).start()).isEqualTo(Instant.parse("2026-11-01T19:00:00Z"));
			assertThat(result.get(0).localDate()).isEqualTo(LocalDate.parse("2026-11-02"));
		}
		finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	void dstGapShiftsTheNonexistentStartForward() {
		// 2026-03-08: New York clocks jump 02:00 EST -> 03:00 EDT.
		List<CalendarOccurrence> result = generate(NEW_YORK, "2026-03-08", "2026-03-09",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, slot(DayOfWeek.SUNDAY, "02:30", "04:00")))),
				List.of());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).start()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z")); // 03:30 EDT
		assertThat(result.get(0).end()).isEqualTo(Instant.parse("2026-03-08T08:00:00Z")); // 04:00 EDT
		assertThat(result.get(0).startLocalTime()).isEqualTo(LocalTime.of(3, 30));
	}

	@Test
	void dstGapThatSwallowsTheWholeSlotProducesNoOccurrence() {
		List<CalendarOccurrence> result = generate(NEW_YORK, "2026-03-08", "2026-03-09",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, slot(DayOfWeek.SUNDAY, "02:30", "03:00")))),
				List.of());

		assertThat(result).isEmpty();
	}

	@Test
	void dstOverlapTakesTheEarlierOffset() {
		// 2026-11-01: New York clocks fall back 02:00 EDT -> 01:00 EST.
		List<CalendarOccurrence> result = generate(NEW_YORK, "2026-11-01", "2026-11-02",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, slot(DayOfWeek.SUNDAY, "01:30", "02:30")))),
				List.of());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).start()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z")); // 01:30 EDT
		assertThat(result.get(0).end()).isEqualTo(Instant.parse("2026-11-01T07:30:00Z")); // 02:30 EST
	}

	@Test
	void overlapWithTheRangeIsHalfOpen() {
		CourseRules bio = course(COURSE, "Bio");
		// Ends exactly at the range start (Colombo midnight of 11-02) -> excluded.
		SessionInput touching = new SessionInput(UUID.randomUUID(), COURSE, "Late", Instant.parse("2026-11-01T17:30:00Z"),
				Instant.parse("2026-11-01T18:30:00Z"), false, null);
		// Ends one minute after the range start -> included.
		SessionInput crossing = new SessionInput(UUID.randomUUID(), COURSE, "Later",
				Instant.parse("2026-11-01T17:31:00Z"), Instant.parse("2026-11-01T18:31:00Z"), false, null);
		// Starts exactly at the range end -> excluded.
		SessionInput atEnd = new SessionInput(UUID.randomUUID(), COURSE, "Next", Instant.parse("2026-11-02T18:30:00Z"),
				Instant.parse("2026-11-02T19:30:00Z"), false, null);

		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-02", "2026-11-03", List.of(bio),
				List.of(touching, crossing, atEnd));

		assertThat(result).extracting(CalendarOccurrence::sessionTitle).containsExactly("Later");
	}

	@Test
	void revisionGovernsOccurrencesStartingFromItsEffectiveInstant() {
		SlotRule oldMorning = slot(DayOfWeek.MONDAY, "08:00", "10:00");
		SlotRule newNoon = slot(DayOfWeek.MONDAY, "12:00", "14:00");
		// Saved Monday 2026-11-09 08:30 Colombo - while the 08:00 class is in progress.
		Instant savedAt = Instant.parse("2026-11-09T03:00:00Z");
		CourseRules bio = course(COURSE, "Bio", revision(LONG_AGO, null, null, oldMorning),
				revision(savedAt, null, null, newNoon));

		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-02", "2026-11-17", List.of(bio), List.of());

		assertThat(result).extracting(o -> o.localDate() + " " + o.startLocalTime())
			.containsExactly("2026-11-02 08:00", "2026-11-09 08:00", "2026-11-09 12:00", "2026-11-16 12:00");
	}

	@Test
	void clearedScheduleProducesNothingAfterItAndARevisionNeverGovernsBeforeItsEffectiveInstant() {
		Instant firstSave = Instant.parse("2026-11-04T00:00:00Z");
		Instant cleared = Instant.parse("2026-11-20T00:00:00Z");
		CourseRules bio = course(COURSE, "Bio", revision(firstSave, null, null, slot(DayOfWeek.MONDAY, "08:00", "10:00")),
				revision(cleared, null, null));

		assertThat(dates(generate(COLOMBO, "2026-11-01", "2026-12-06", List.of(bio), List.of())))
			.containsExactly("2026-11-09", "2026-11-16");
	}

	@Test
	void activeSessionReplacesTheOverlappingRecurringOccurrence() {
		SlotRule monday = slot(DayOfWeek.MONDAY, "08:00", "10:00");
		UUID sessionId = UUID.randomUUID();
		SessionInput rescheduled = new SessionInput(sessionId, COURSE, "Moved class",
				Instant.parse("2026-11-02T03:30:00Z"), Instant.parse("2026-11-02T05:30:00Z"), false, "Guest Teacher");

		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-02", "2026-11-03",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, monday))), List.of(rescheduled));

		assertThat(result).hasSize(1);
		CalendarOccurrence o = result.get(0);
		assertThat(o.id()).isEqualTo("s_" + sessionId);
		assertThat(o.source()).isEqualTo("SESSION");
		assertThat(o.replacesOccurrenceId()).isEqualTo("r_" + monday.slotId() + "_20261102");
		assertThat(o.startLocalTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(o.sessionTitle()).isEqualTo("Moved class");
		assertThat(o.teacherNames()).containsExactly("Guest Teacher");
	}

	@Test
	void cancelledSessionCancelsTheRecurringOccurrenceInsteadOfAddingOne() {
		SlotRule monday = slot(DayOfWeek.MONDAY, "08:00", "10:00");
		SessionInput cancelled = new SessionInput(UUID.randomUUID(), COURSE, "Cancelled",
				Instant.parse("2026-11-02T02:30:00Z"), Instant.parse("2026-11-02T04:30:00Z"), true, null);

		List<CalendarOccurrence> result = generate(COLOMBO, "2026-11-02", "2026-11-03",
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, monday))), List.of(cancelled));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).source()).isEqualTo("RECURRING");
		assertThat(result.get(0).cancelled()).isTrue();
		assertThat(result.get(0).status()).isEqualTo("CANCELLED");
	}

	@Test
	void activeSessionWinsOverACancelledOneForTheSameSlot() {
		SlotRule monday = slot(DayOfWeek.MONDAY, "08:00", "10:00");
		SessionInput cancelled = new SessionInput(UUID.randomUUID(), COURSE, "Old",
				Instant.parse("2026-11-02T02:30:00Z"), Instant.parse("2026-11-02T04:30:00Z"), true, null);
		SessionInput active = new SessionInput(UUID.randomUUID(), COURSE, "New",
				Instant.parse("2026-11-02T03:00:00Z"), Instant.parse("2026-11-02T05:00:00Z"), false, null);

		List<CalendarOccurrence> result = ClassOccurrenceGenerator.generate(COLOMBO, LocalDate.parse("2026-11-02"),
				LocalDate.parse("2026-11-03"), List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, monday))),
				List.of(cancelled, active), false, EARLY_NOW);

		assertThat(result).extracting(CalendarOccurrence::sessionTitle).containsExactly("New");
	}

	@Test
	void standaloneSessionsAreEmittedAndStandaloneCancelledOnlyForStaff() {
		SessionInput extra = new SessionInput(UUID.randomUUID(), COURSE, "Extra", Instant.parse("2026-11-03T10:00:00Z"),
				Instant.parse("2026-11-03T11:00:00Z"), false, null);
		SessionInput dropped = new SessionInput(UUID.randomUUID(), COURSE, "Dropped",
				Instant.parse("2026-11-03T12:00:00Z"), Instant.parse("2026-11-03T13:00:00Z"), true, null);
		SessionInput otherCourse = new SessionInput(UUID.randomUUID(), UUID.randomUUID(), "Not in scope",
				Instant.parse("2026-11-03T12:00:00Z"), Instant.parse("2026-11-03T13:00:00Z"), false, null);
		List<CourseRules> courses = List.of(course(COURSE, "Bio"));
		LocalDate from = LocalDate.parse("2026-11-01");
		LocalDate to = LocalDate.parse("2026-11-08");

		List<CalendarOccurrence> staff = ClassOccurrenceGenerator.generate(COLOMBO, from, to, courses,
				List.of(extra, dropped, otherCourse), true, EARLY_NOW);
		List<CalendarOccurrence> student = ClassOccurrenceGenerator.generate(COLOMBO, from, to, courses,
				List.of(extra, dropped, otherCourse), false, EARLY_NOW);

		assertThat(staff).extracting(CalendarOccurrence::sessionTitle).containsExactly("Extra", "Dropped");
		assertThat(staff.get(1).cancelled()).isTrue();
		assertThat(staff.get(0).replacesOccurrenceId()).isNull();
		assertThat(student).extracting(CalendarOccurrence::sessionTitle).containsExactly("Extra");
	}

	@Test
	void outputIsDeterministicallySorted() {
		UUID alphaId = UUID.fromString("00000000-0000-0000-0000-00000000c00a");
		UUID betaId = UUID.fromString("00000000-0000-0000-0000-00000000c00b");
		List<CourseRules> courses = List.of(
				course(betaId, "beta course", revision(LONG_AGO, null, null, slot(DayOfWeek.MONDAY, "08:00", "10:00"))),
				course(alphaId, "Alpha course", revision(LONG_AGO, null, null, slot(DayOfWeek.MONDAY, "08:00", "10:00"),
						slot(DayOfWeek.MONDAY, "08:00", "09:00"))));

		List<CalendarOccurrence> first = generate(COLOMBO, "2026-11-02", "2026-11-03", courses, List.of());
		List<CalendarOccurrence> second = generate(COLOMBO, "2026-11-02", "2026-11-03", courses, List.of());

		assertThat(first).isEqualTo(second);
		assertThat(first).extracting(o -> o.courseName() + " " + o.endLocalTime())
			.containsExactly("Alpha course 09:00", "Alpha course 10:00", "beta course 10:00");
	}

	@Test
	void statusIsLiveAtTheExactStartAndOverAtTheExactEnd() {
		Instant start = Instant.parse("2026-11-02T02:30:00Z");
		Instant end = Instant.parse("2026-11-02T04:30:00Z");

		assertThat(ClassOccurrenceGenerator.statusAt(start, end, false, start.minusNanos(1))).isEqualTo("SCHEDULED");
		assertThat(ClassOccurrenceGenerator.statusAt(start, end, false, start)).isEqualTo("LIVE");
		assertThat(ClassOccurrenceGenerator.statusAt(start, end, false, end.minusNanos(1))).isEqualTo("LIVE");
		assertThat(ClassOccurrenceGenerator.statusAt(start, end, false, end)).isEqualTo("OVER");
		assertThat(ClassOccurrenceGenerator.statusAt(start, end, true, start)).isEqualTo("CANCELLED");

		List<CalendarOccurrence> atStart = ClassOccurrenceGenerator.generate(COLOMBO, LocalDate.parse("2026-11-02"),
				LocalDate.parse("2026-11-03"),
				List.of(course(COURSE, "Bio", revision(LONG_AGO, null, null, slot(DayOfWeek.MONDAY, "08:00", "10:00")))),
				List.of(), true, start);
		assertThat(atStart.get(0).status()).isEqualTo("LIVE");
	}

}

package com.lms.coursemanagement.calendar.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side validation of a class schedule save (CAL-01 spec §2.3): weekday
 * names, {@code HH:mm} times, same-day rows only (no overnight), no duplicate
 * or overlapping rows on one weekday of the same course, at most {@link
 * #MAX_SLOTS} rows, {@code effectiveFrom <= effectiveTo}. Every failure is a
 * field error; all failures are reported together as one 400 {@code
 * VALIDATION_ERROR}.
 */
public final class ClassScheduleValidator {

	public static final int MAX_SLOTS = 50;

	public static final String OVERNIGHT_MESSAGE = "End time must be after start time. Overnight classes aren't "
			+ "supported — split them into two rows.";

	private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
		.withResolverStyle(ResolverStyle.STRICT);

	private ClassScheduleValidator() {
	}

	/** One submitted row, exactly as received. */
	public record SlotInput(String dayOfWeek, String startTime, String endTime) {
	}

	/** One accepted row. */
	public record ValidSlot(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
	}

	/**
	 * @return the accepted rows sorted Monday to Sunday, then by start and end
	 * @throws FieldValidationException listing every invalid field
	 */
	public static List<ValidSlot> validate(LocalDate effectiveFrom, LocalDate effectiveTo, List<SlotInput> slots) {
		List<FieldError> errors = new ArrayList<>();
		if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
			errors.add(new FieldError("effectiveTo", "The end date must be on or after the start date."));
		}
		if (slots == null) {
			errors.add(new FieldError("slots", "Class times are required (send an empty list to clear them)."));
			throw new FieldValidationException("Check the class schedule and try again.", errors);
		}
		if (slots.size() > MAX_SLOTS) {
			errors.add(new FieldError("slots", "A course can have at most " + MAX_SLOTS + " class times."));
		}

		List<Indexed> parsed = new ArrayList<>();
		for (int index = 0; index < slots.size(); index++) {
			SlotInput slot = slots.get(index);
			String prefix = "slots[" + index + "].";
			if (slot == null) {
				errors.add(new FieldError("slots[" + index + "]", "This class time is empty."));
				continue;
			}
			DayOfWeek day = parseDay(slot.dayOfWeek());
			if (day == null) {
				errors.add(new FieldError(prefix + "dayOfWeek", "Choose a day of the week (MONDAY to SUNDAY)."));
			}
			LocalTime start = parseTime(slot.startTime());
			if (start == null) {
				errors.add(new FieldError(prefix + "startTime", "Enter a start time as HH:mm."));
			}
			LocalTime end = parseTime(slot.endTime());
			if (end == null) {
				errors.add(new FieldError(prefix + "endTime", "Enter an end time as HH:mm."));
			}
			if (start != null && end != null && !end.isAfter(start)) {
				errors.add(new FieldError(prefix + "endTime", OVERNIGHT_MESSAGE));
				continue;
			}
			if (day != null && start != null && end != null) {
				parsed.add(new Indexed(index, new ValidSlot(day, start, end)));
			}
		}

		for (int i = 0; i < parsed.size(); i++) {
			Indexed later = parsed.get(i);
			for (int j = 0; j < i; j++) {
				Indexed earlier = parsed.get(j);
				ValidSlot a = earlier.slot();
				ValidSlot b = later.slot();
				if (a.dayOfWeek() != b.dayOfWeek()) {
					continue;
				}
				String dayName = a.dayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
				if (a.startTime().equals(b.startTime()) && a.endTime().equals(b.endTime())) {
					errors.add(new FieldError("slots[" + later.index() + "]",
							"This " + dayName + " class time is listed twice."));
					break;
				}
				if (a.startTime().isBefore(b.endTime()) && b.startTime().isBefore(a.endTime())) {
					errors.add(new FieldError("slots[" + later.index() + "]",
							"This class overlaps another " + dayName + " class of this course."));
					break;
				}
			}
		}

		if (!errors.isEmpty()) {
			throw new FieldValidationException("Check the class schedule and try again.", errors);
		}
		return parsed.stream()
			.map(Indexed::slot)
			.sorted(Comparator.comparing(ValidSlot::dayOfWeek)
				.thenComparing(ValidSlot::startTime)
				.thenComparing(ValidSlot::endTime))
			.toList();
	}

	private static DayOfWeek parseDay(String value) {
		if (value == null) {
			return null;
		}
		try {
			return DayOfWeek.valueOf(value.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static LocalTime parseTime(String value) {
		if (value == null || !TIME_PATTERN.matcher(value.strip()).matches()) {
			return null;
		}
		try {
			return LocalTime.parse(value.strip(), TIME);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private record Indexed(int index, ValidSlot slot) {
	}

}

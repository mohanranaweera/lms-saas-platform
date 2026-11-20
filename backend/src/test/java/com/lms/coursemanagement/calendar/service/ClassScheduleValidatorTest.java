package com.lms.coursemanagement.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.coursemanagement.calendar.service.ClassScheduleValidator.SlotInput;
import com.lms.coursemanagement.calendar.service.ClassScheduleValidator.ValidSlot;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CAL-01 spec §2.3 server-side schedule validation. */
class ClassScheduleValidatorTest {

	private static List<FieldError> errors(LocalDate from, LocalDate to, List<SlotInput> slots) {
		try {
			ClassScheduleValidator.validate(from, to, slots);
		}
		catch (FieldValidationException ex) {
			assertThat(ex.getErrorCode()).isEqualTo("VALIDATION_ERROR");
			return ex.getFieldErrors();
		}
		throw new AssertionError("expected a validation failure");
	}

	private static List<FieldError> errors(SlotInput... slots) {
		return errors(null, null, List.of(slots));
	}

	@Test
	void validRowsAreAcceptedAndSortedMondayFirst() {
		List<ValidSlot> result = ClassScheduleValidator.validate(LocalDate.parse("2026-11-01"),
				LocalDate.parse("2026-11-01"),
				List.of(new SlotInput("WEDNESDAY", "09:00", "10:00"), new SlotInput("monday", "14:00", "16:00"),
						new SlotInput("MONDAY", "08:00", "10:00"), new SlotInput("MONDAY", "10:00", "12:00")));

		assertThat(result).extracting(s -> s.dayOfWeek() + " " + s.startTime())
			.containsExactly("MONDAY 08:00", "MONDAY 10:00", "MONDAY 14:00", "WEDNESDAY 09:00");
		assertThat(result.get(0).endTime()).isEqualTo(LocalTime.of(10, 0));
		assertThat(ClassScheduleValidator.validate(null, null, List.of())).isEmpty();
	}

	@Test
	void endNotAfterStartIsRejectedAsUnsupportedOvernight() {
		assertThat(errors(new SlotInput("MONDAY", "22:00", "01:00")))
			.containsExactly(new FieldError("slots[0].endTime", ClassScheduleValidator.OVERNIGHT_MESSAGE));
		assertThat(errors(new SlotInput("MONDAY", "08:00", "08:00")))
			.containsExactly(new FieldError("slots[0].endTime", ClassScheduleValidator.OVERNIGHT_MESSAGE));
		assertThat(ClassScheduleValidator.OVERNIGHT_MESSAGE).contains("Overnight classes aren't supported");
	}

	@Test
	void duplicateRowsAreRejected() {
		assertThat(errors(new SlotInput("MONDAY", "08:00", "10:00"), new SlotInput("MONDAY", "08:00", "10:00")))
			.extracting(FieldError::field)
			.containsExactly("slots[1]");
	}

	@Test
	void overlappingRowsOfTheSameDayAreRejectedButOtherDaysAndAdjacentRowsAreFine() {
		assertThat(errors(new SlotInput("MONDAY", "08:00", "10:00"), new SlotInput("MONDAY", "09:30", "11:00")))
			.extracting(FieldError::field)
			.containsExactly("slots[1]");
		assertThat(ClassScheduleValidator.validate(null, null,
				List.of(new SlotInput("MONDAY", "08:00", "10:00"), new SlotInput("TUESDAY", "09:00", "11:00"),
						new SlotInput("MONDAY", "10:00", "11:00"))))
			.hasSize(3);
	}

	@Test
	void effectiveFromAfterEffectiveToIsRejected() {
		assertThat(errors(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-11-30"), List.of()))
			.extracting(FieldError::field)
			.containsExactly("effectiveTo");
	}

	@Test
	void invalidWeekdayAndTimesAreRejected() {
		assertThat(errors(new SlotInput("FUNDAY", "08:00", "10:00"))).extracting(FieldError::field)
			.containsExactly("slots[0].dayOfWeek");
		assertThat(errors(new SlotInput(null, "8:00", "24:00"))).extracting(FieldError::field)
			.containsExactly("slots[0].dayOfWeek", "slots[0].startTime", "slots[0].endTime");
		assertThat(errors(new SlotInput("MONDAY", "08:00:00", "10:00"))).extracting(FieldError::field)
			.containsExactly("slots[0].startTime");
	}

	@Test
	void missingSlotListAndTooManySlotsAreRejected() {
		assertThat(errors(null, null, null)).extracting(FieldError::field).containsExactly("slots");
		List<SlotInput> many = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			DayOfWeek day = DayOfWeek.of(i % 7 + 1);
			int hour = i / 7;
			many.add(new SlotInput(day.name(), String.format("%02d:00", hour), String.format("%02d:30", hour)));
		}
		assertThat(errors(null, null, many)).extracting(FieldError::field).containsExactly("slots");
		assertThatThrownBy(() -> ClassScheduleValidator.validate(null, null, many))
			.isInstanceOf(FieldValidationException.class);
	}

}

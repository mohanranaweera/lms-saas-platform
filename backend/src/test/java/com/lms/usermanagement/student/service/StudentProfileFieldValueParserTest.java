package com.lms.usermanagement.student.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.FieldError;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.usermanagement.student.domain.GuardianDetails;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** STU-PROF-01: per-type validation and normalisation of profile field values. */
class StudentProfileFieldValueParserTest {

	private final List<FieldError> errors = new ArrayList<>();

	private Object parse(StudentProfileField field, Object raw) {
		return StudentProfileFieldValueParser.parse(field, raw, "values." + field.name(), errors);
	}

	@Test
	void textIsTrimmedAndBlankBecomesNull() {
		assertThat(parse(StudentProfileField.SCHOOL, "  Royal College  ")).isEqualTo("Royal College");
		assertThat(parse(StudentProfileField.SCHOOL, "   ")).isNull();
		assertThat(parse(StudentProfileField.SCHOOL, null)).isNull();
		assertThat(errors).isEmpty();
	}

	@Test
	void textLongerThanTheFieldLimitIsRejectedWithoutEchoingTheValue() {
		String secret = "X".repeat(21);
		assertThat(parse(StudentProfileField.NIC_NUMBER, secret)).isNull();
		assertThat(errors).singleElement().satisfies(error -> {
			assertThat(error.field()).isEqualTo("values.NIC_NUMBER");
			assertThat(error.message()).doesNotContain(secret);
		});
	}

	@Test
	void nonTextAndControlCharactersAreRejected() {
		parse(StudentProfileField.PROFESSION, 42);
		parse(StudentProfileField.PROFESSION, "bad\u0000value");
		assertThat(errors).hasSize(2);
	}

	@Test
	void phoneAcceptsCommonFormatsAndRejectsLetters() {
		assertThat(parse(StudentProfileField.MOBILE, "+94 77 123-4567")).isEqualTo("+94 77 123-4567");
		assertThat(parse(StudentProfileField.WHATSAPP_NUMBER, "(011) 2345678")).isEqualTo("(011) 2345678");
		assertThat(errors).isEmpty();
		parse(StudentProfileField.MOBILE, "call me");
		parse(StudentProfileField.MOBILE, "123");
		assertThat(errors).hasSize(2);
	}

	@Test
	void birthdayMustBeAnIsoPastDateAfter1900() {
		assertThat(parse(StudentProfileField.BIRTHDAY, "2008-04-01")).isEqualTo(LocalDate.of(2008, 4, 1));
		assertThat(errors).isEmpty();
		parse(StudentProfileField.BIRTHDAY, "01/04/2008");
		parse(StudentProfileField.BIRTHDAY, LocalDate.now().plusDays(1).toString());
		parse(StudentProfileField.BIRTHDAY, "1899-12-31");
		assertThat(errors).hasSize(3);
	}

	@Test
	void alYearAcceptsNumbersAndNumericStringsInRange() {
		assertThat(parse(StudentProfileField.AL_YEAR, 2026)).isEqualTo((short) 2026);
		assertThat(parse(StudentProfileField.AL_YEAR, "2027")).isEqualTo((short) 2027);
		assertThat(errors).isEmpty();
		parse(StudentProfileField.AL_YEAR, 1989);
		parse(StudentProfileField.AL_YEAR, "soon");
		parse(StudentProfileField.AL_YEAR, 2026.5);
		assertThat(errors).hasSize(3);
	}

	@Test
	void booleanMustBeAJsonBoolean() {
		assertThat(parse(StudentProfileField.HAS_SIBLINGS, true)).isEqualTo(true);
		parse(StudentProfileField.HAS_SIBLINGS, "yes");
		assertThat(errors).hasSize(1);
	}

	@Test
	void countryIsUppercasedAndMustBeAnIsoCode() {
		assertThat(parse(StudentProfileField.COUNTRY, "lk")).isEqualTo("LK");
		assertThat(errors).isEmpty();
		parse(StudentProfileField.COUNTRY, "ZZ");
		parse(StudentProfileField.COUNTRY, "Sri Lanka");
		assertThat(errors).hasSize(2);
	}

	@Test
	void guardianParsesBothPartsAndReportsCompleteness() {
		Object full = parse(StudentProfileField.GUARDIAN, Map.of("name", " Parent ", "phone", "+94771234567"));
		assertThat(full).isEqualTo(new GuardianDetails("Parent", "+94771234567"));
		assertThat(StudentProfileFieldValueParser.isFilled(full)).isTrue();

		Map<String, Object> partial = new HashMap<>();
		partial.put("name", "Parent");
		partial.put("phone", null);
		Object half = parse(StudentProfileField.GUARDIAN, partial);
		assertThat(StudentProfileFieldValueParser.isFilled(half)).isFalse();

		assertThat(parse(StudentProfileField.GUARDIAN, Map.of("name", " ", "phone", " "))).isNull();
		assertThat(errors).isEmpty();

		parse(StudentProfileField.GUARDIAN, Map.of("name", "Parent", "phone", "not a phone"));
		parse(StudentProfileField.GUARDIAN, "Parent");
		assertThat(errors).extracting(FieldError::field).containsExactly("values.GUARDIAN.phone", "values.GUARDIAN");
	}

	@Test
	void toJsonRendersDatesYearsAndGuardian() {
		assertThat(StudentProfileFieldValueParser.toJson(LocalDate.of(2008, 4, 1))).isEqualTo("2008-04-01");
		assertThat(StudentProfileFieldValueParser.toJson((short) 2026)).isEqualTo(2026);
		Map<String, Object> expected = new HashMap<>();
		expected.put("name", "Parent");
		expected.put("phone", null);
		assertThat(StudentProfileFieldValueParser.toJson(new GuardianDetails("Parent", null))).isEqualTo(expected);
	}

}

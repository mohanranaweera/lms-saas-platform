package com.lms.coursemanagement.course.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link CourseStatus} to/from the DB values ('DRAFT'/'PRIVATE'/'PUBLIC'
 * - V11's {@code ck_course_status} CHECK constraint), mirroring the shape of
 * {@code identityaccessservice.domain.AccountStatusConverter}. Unlike that
 * converter, no case transformation is needed here - the enum constant names
 * already match the DB column values exactly - but the converter is kept
 * explicit (rather than a bare {@code @Enumerated(EnumType.STRING)}) so a
 * future rename of either side doesn't silently drift without a compile-time
 * signal here.
 */
@Converter(autoApply = true)
public class CourseStatusConverter implements AttributeConverter<CourseStatus, String> {

	@Override
	public String convertToDatabaseColumn(CourseStatus attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public CourseStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : CourseStatus.valueOf(dbData);
	}

}

package com.lms.liveclassmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link ClassSessionStatus} to/from the DB values ('SCHEDULED'/'LIVE'/
 * 'COMPLETED'/'CANCELLED' - V49's {@code ck_class_session_status} CHECK
 * constraint), mirroring {@code coursemanagement.course.domain
 * .CourseStatusConverter}'s exact shape.
 */
@Converter(autoApply = true)
public class ClassSessionStatusConverter implements AttributeConverter<ClassSessionStatus, String> {

	@Override
	public String convertToDatabaseColumn(ClassSessionStatus attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public ClassSessionStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : ClassSessionStatus.valueOf(dbData);
	}

}

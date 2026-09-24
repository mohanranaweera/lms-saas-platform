package com.lms.liveclassmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Mirrors {@link ClassSessionStatusConverter}'s shape for {@link ClassSessionRecordingStatus}. */
@Converter(autoApply = true)
public class ClassSessionRecordingStatusConverter implements AttributeConverter<ClassSessionRecordingStatus, String> {

	@Override
	public String convertToDatabaseColumn(ClassSessionRecordingStatus attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public ClassSessionRecordingStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : ClassSessionRecordingStatus.valueOf(dbData);
	}

}

package com.lms.liveclassmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Mirrors {@link ClassSessionStatusConverter}'s shape for {@link ClassSessionProviderStatus}. */
@Converter(autoApply = true)
public class ClassSessionProviderStatusConverter implements AttributeConverter<ClassSessionProviderStatus, String> {

	@Override
	public String convertToDatabaseColumn(ClassSessionProviderStatus attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public ClassSessionProviderStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : ClassSessionProviderStatus.valueOf(dbData);
	}

}

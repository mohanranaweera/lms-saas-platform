package com.lms.liveclassmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Mirrors {@link ClassSessionStatusConverter}'s shape for {@link MeetingProvider}. */
@Converter(autoApply = true)
public class MeetingProviderConverter implements AttributeConverter<MeetingProvider, String> {

	@Override
	public String convertToDatabaseColumn(MeetingProvider attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public MeetingProvider convertToEntityAttribute(String dbData) {
		return dbData == null ? null : MeetingProvider.valueOf(dbData);
	}

}

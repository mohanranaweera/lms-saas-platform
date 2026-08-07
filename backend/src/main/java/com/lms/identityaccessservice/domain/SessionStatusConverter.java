package com.lms.identityaccessservice.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link SessionStatus} to/from the lowercase DB values ('active'/'revoked'/'expired'). */
@Converter(autoApply = true)
public class SessionStatusConverter implements AttributeConverter<SessionStatus, String> {

	@Override
	public String convertToDatabaseColumn(SessionStatus attribute) {
		return attribute == null ? null : attribute.name().toLowerCase();
	}

	@Override
	public SessionStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : SessionStatus.valueOf(dbData.toUpperCase());
	}

}

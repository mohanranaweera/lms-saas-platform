package com.lms.identityaccessservice.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link DeviceRevocationSource} to/from the lowercase DB values. */
@Converter(autoApply = true)
public class DeviceRevocationSourceConverter implements AttributeConverter<DeviceRevocationSource, String> {

	@Override
	public String convertToDatabaseColumn(DeviceRevocationSource attribute) {
		return attribute == null ? null : attribute.name().toLowerCase();
	}

	@Override
	public DeviceRevocationSource convertToEntityAttribute(String dbData) {
		return dbData == null ? null : DeviceRevocationSource.valueOf(dbData.toUpperCase());
	}

}

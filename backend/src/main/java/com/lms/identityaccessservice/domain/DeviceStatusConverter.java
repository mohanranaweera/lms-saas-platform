package com.lms.identityaccessservice.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Maps {@link DeviceStatus} to/from the lowercase DB values ('active'/'revoked'). */
@Converter(autoApply = true)
public class DeviceStatusConverter implements AttributeConverter<DeviceStatus, String> {

	@Override
	public String convertToDatabaseColumn(DeviceStatus attribute) {
		return attribute == null ? null : attribute.name().toLowerCase();
	}

	@Override
	public DeviceStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : DeviceStatus.valueOf(dbData.toUpperCase());
	}

}

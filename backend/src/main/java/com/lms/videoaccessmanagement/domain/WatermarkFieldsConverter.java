package com.lms.videoaccessmanagement.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code tenant_video_configuration.watermark_fields} (V71): a comma-separated
 * list of {@link WatermarkField} names, in enum order, matched by the
 * column's CHECK regex.
 */
@Converter
public class WatermarkFieldsConverter implements AttributeConverter<Set<WatermarkField>, String> {

	@Override
	public String convertToDatabaseColumn(Set<WatermarkField> attribute) {
		if (attribute == null) {
			return null;
		}
		if (attribute.isEmpty()) {
			// Rejected by the column CHECK - the service requires at least one field.
			return "";
		}
		return EnumSet.copyOf(attribute).stream().map(Enum::name).collect(Collectors.joining(","));
	}

	@Override
	public Set<WatermarkField> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) {
			return EnumSet.noneOf(WatermarkField.class);
		}
		return Arrays.stream(dbData.split(","))
			.map(WatermarkField::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(WatermarkField.class)));
	}

}

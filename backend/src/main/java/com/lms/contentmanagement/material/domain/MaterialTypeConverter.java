package com.lms.contentmanagement.material.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link MaterialType} to/from the DB values (V50's {@code
 * ck_material_type} CHECK constraint), mirroring {@link
 * MaterialVisibilityConverter}'s shape exactly.
 */
@Converter(autoApply = true)
public class MaterialTypeConverter implements AttributeConverter<MaterialType, String> {

	@Override
	public String convertToDatabaseColumn(MaterialType attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public MaterialType convertToEntityAttribute(String dbData) {
		return dbData == null ? null : MaterialType.valueOf(dbData);
	}

}

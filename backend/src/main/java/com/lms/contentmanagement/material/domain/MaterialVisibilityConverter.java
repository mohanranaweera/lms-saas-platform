package com.lms.contentmanagement.material.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link MaterialVisibility} to/from the DB values ('VISIBLE'/'HIDDEN' -
 * V16's {@code ck_material_visibility} CHECK constraint), mirroring {@code
 * coursemanagement.course.domain.CourseStatusConverter}'s shape exactly.
 */
@Converter(autoApply = true)
public class MaterialVisibilityConverter implements AttributeConverter<MaterialVisibility, String> {

	@Override
	public String convertToDatabaseColumn(MaterialVisibility attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public MaterialVisibility convertToEntityAttribute(String dbData) {
		return dbData == null ? null : MaterialVisibility.valueOf(dbData);
	}

}

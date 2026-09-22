package com.lms.coursemanagement.course.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link CoursePricingModel} to/from the DB values ('FREE'/'ONE_TIME'/
 * 'MONTHLY'/'SESSION'/'CUSTOM' - V37's {@code ck_course_pricing_model} CHECK
 * constraint), mirroring {@link CourseStatusConverter}'s exact shape and
 * rationale.
 */
@Converter(autoApply = true)
public class CoursePricingModelConverter implements AttributeConverter<CoursePricingModel, String> {

	@Override
	public String convertToDatabaseColumn(CoursePricingModel attribute) {
		return attribute == null ? null : attribute.name();
	}

	@Override
	public CoursePricingModel convertToEntityAttribute(String dbData) {
		return dbData == null ? null : CoursePricingModel.valueOf(dbData);
	}

}

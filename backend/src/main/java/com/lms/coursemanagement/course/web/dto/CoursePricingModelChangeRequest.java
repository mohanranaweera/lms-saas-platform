package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CoursePricingModelChangeRequest(@NotNull CoursePricingModel pricingModel) {

}

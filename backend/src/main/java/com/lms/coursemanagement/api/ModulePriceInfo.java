package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A module's MODULE-offer price (V85 {@code course_module.price}, D09),
 * returned by {@link CourseLookupApi#findModulePrices(UUID)}. {@code price}
 * is {@code null} when the module is not sold on its own.
 */
public record ModulePriceInfo(UUID moduleId, UUID courseId, String title, int sequence, BigDecimal price) {

}

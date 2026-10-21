package com.lms.usermanagement.student.service;

import com.lms.tenantmanagement.api.StudentProfileField;

/**
 * One enabled student profile field with its stored value (STU-PROF-01).
 * {@code value} is JSON-friendly: String, Integer (year), Boolean, an ISO date
 * String, or a {@code name}/{@code phone} map for the guardian; null when unset.
 */
public record StudentProfileFieldValue(StudentProfileField field, boolean required, Object value) {

}

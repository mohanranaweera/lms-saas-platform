package com.lms.usermanagement.student.service;

import com.lms.tenantmanagement.api.StudentProfileField;
import java.util.List;

/** A student profile field set as shown to the student or staff (STU-PROF-01). */
public record StudentProfileFieldsView(List<StudentProfileFieldValue> fields,
		List<StudentProfileField> missingRequiredFields) {

}

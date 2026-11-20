package com.lms.usermanagement.student.domain;

/**
 * Value of the composite {@code GUARDIAN} student profile field
 * (STU-PROF-01): {@code student_profile.guardian_name} +
 * {@code guardian_phone} (V44). Either part may be null.
 */
public record GuardianDetails(String name, String phone) {

	/** Both parts present - what "required" means for this field. */
	public boolean isComplete() {
		return name != null && phone != null;
	}

}

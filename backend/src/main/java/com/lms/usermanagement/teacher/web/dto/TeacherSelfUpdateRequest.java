package com.lms.usermanagement.teacher.web.dto;

import jakarta.validation.constraints.Size;

/**
 * TP-03: the only fields a Teacher may change on their own profile. Any
 * other property in the body (name, contact, commission, role, ...) is not
 * part of this type and is never written.
 */
public record TeacherSelfUpdateRequest(@Size(max = 2000) String qualifications, @Size(max = 5000) String description) {
}

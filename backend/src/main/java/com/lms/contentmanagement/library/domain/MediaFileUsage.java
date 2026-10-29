package com.lms.contentmanagement.library.domain;

/**
 * Purpose ("file area") of a stored file, fixed at upload. Only {@link #COURSE_LIBRARY}
 * files appear in the Media Library and can be attached to lessons; site files such as
 * teacher profile photos are managed where they are used.
 */
public enum MediaFileUsage {
	COURSE_LIBRARY, TEACHER_PROFILE
}

package com.lms.usermanagement.student.service;

/** Test-only bridge to {@link StudentDirectoryService}'s package-private CSV escaping. */
public final class StudentDirectoryServiceTestAccess {

	private StudentDirectoryServiceTestAccess() {
	}

	public static String cell(String value) {
		return StudentDirectoryService.cell(value);
	}

}

package com.lms.identityaccessservice.api;

/** Klass-style permission matrix sections, in display order (STAFF-MGMT-02 §3). */
public enum CapabilityGroup {

	FINANCE("Finance"), TEACHERS("Teachers"), COURSES("Courses"), MODULES("Modules"), STUDENTS("Students"),
	EXAMS("Exams"), ATTENDANCE("Attendance"), PAYMENTS("Payments"), MEDIA_LIBRARY("Media Library"),
	NOTIFICATIONS("Notifications"), TIMETABLE("Timetable"), INTEGRATIONS("Integrations"), TUTE("Tute"),
	REPORTS("Reports"), TRANSLATIONS("Translations"), STAFF("Staff");

	private final String label;

	CapabilityGroup(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

}

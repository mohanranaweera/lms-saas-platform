package com.lms.attendancemanagement.domain;

/**
 * Mirrors {@code ck_attendance_record_status} (V25) exactly - {@code
 * PRESENT}/{@code ABSENT}/{@code LATE}. {@code Excused} exists in the
 * design-system vocabulary but is explicitly out of this MVP's scope (plan
 * §11) and must never be added here without a corresponding migration/plan
 * update.
 */
public enum AttendanceStatus {

	PRESENT, ABSENT, LATE

}

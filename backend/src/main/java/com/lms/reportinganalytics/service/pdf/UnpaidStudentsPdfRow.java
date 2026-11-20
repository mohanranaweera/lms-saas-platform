package com.lms.reportinganalytics.service.pdf;

/** Display-only row; qualification already happened in reporting service. */
public final class UnpaidStudentsPdfRow {

	private final String rowNo;
	private final String indexNumber;
	private final String studentName;
	private final String mobile;
	private final String attendanceCount;

	public UnpaidStudentsPdfRow(String rowNo, String indexNumber, String studentName, String mobile,
			String attendanceCount) {
		this.rowNo = rowNo;
		this.indexNumber = indexNumber;
		this.studentName = studentName;
		this.mobile = mobile;
		this.attendanceCount = attendanceCount;
	}

	public String getRowNo() { return rowNo; }
	public String getIndexNumber() { return indexNumber; }
	public String getStudentName() { return studentName; }
	public String getMobile() { return mobile; }
	public String getAttendanceCount() { return attendanceCount; }

}

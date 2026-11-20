package com.lms.reportinganalytics.service.pdf;

/** Display-only Registration Report PDF row. */
public final class RegistrationPdfRow {
	private final String rowNo, indexNumber, studentName, mobile, courses, registeredBy, lastPayment, registrationDate;
	public RegistrationPdfRow(String rowNo, String indexNumber, String studentName, String mobile, String courses,
			String registeredBy, String lastPayment, String registrationDate) {
		this.rowNo = rowNo; this.indexNumber = indexNumber; this.studentName = studentName; this.mobile = mobile;
		this.courses = courses; this.registeredBy = registeredBy; this.lastPayment = lastPayment; this.registrationDate = registrationDate;
	}
	public String getRowNo() { return rowNo; }
	public String getIndexNumber() { return indexNumber; }
	public String getStudentName() { return studentName; }
	public String getMobile() { return mobile; }
	public String getCourses() { return courses; }
	public String getRegisteredBy() { return registeredBy; }
	public String getLastPayment() { return lastPayment; }
	public String getRegistrationDate() { return registrationDate; }
}

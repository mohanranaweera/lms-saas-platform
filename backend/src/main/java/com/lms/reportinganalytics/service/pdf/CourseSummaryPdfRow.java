package com.lms.reportinganalytics.service.pdf;

/** One weekly row in the reference-shaped RPT-05 PDF table. */
public record CourseSummaryPdfRow(String weekLabel, String daysLabel, String newRegistrations, String classes,
		String attendance, String payments, String amountOne, String amountTwo, String amountThree) {

	public String getWeekLabel() { return weekLabel; }
	public String getDaysLabel() { return daysLabel; }
	public String getNewRegistrations() { return newRegistrations; }
	public String getClasses() { return classes; }
	public String getAttendance() { return attendance; }
	public String getPayments() { return payments; }
	public String getAmountOne() { return amountOne; }
	public String getAmountTwo() { return amountTwo; }
	public String getAmountThree() { return amountThree; }

}

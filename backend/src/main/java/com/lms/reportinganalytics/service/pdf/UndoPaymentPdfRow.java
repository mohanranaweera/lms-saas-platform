package com.lms.reportinganalytics.service.pdf;

public final class UndoPaymentPdfRow {
	private final String rowNo;
	private final String student;
	private final String indexNumber;
	private final String course;
	private final String reason;
	private final String date;
	private final String amount;

	public UndoPaymentPdfRow(String rowNo, String student, String indexNumber, String course, String reason,
			String date, String amount) {
		this.rowNo = rowNo;
		this.student = student;
		this.indexNumber = indexNumber;
		this.course = course;
		this.reason = reason;
		this.date = date;
		this.amount = amount;
	}

	public String getRowNo() { return rowNo; }
	public String getStudent() { return student; }
	public String getIndexNumber() { return indexNumber; }
	public String getCourse() { return course; }
	public String getReason() { return reason; }
	public String getDate() { return date; }
	public String getAmount() { return amount; }
}

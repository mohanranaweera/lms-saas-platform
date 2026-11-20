package com.lms.reportinganalytics.service.pdf;

/** One printed row in one date band of the RPT-03 PDF. */
public final class AttendancePdfRow {

	private final Integer band;
	private final String rowNo;
	private final String indexNumber;
	private final String studentName;
	private final String date1;
	private final String mark1;
	private final String date2;
	private final String mark2;
	private final String date3;
	private final String mark3;
	private final String date4;
	private final String mark4;
	private final String date5;
	private final String mark5;
	private final String date6;
	private final String mark6;

	public AttendancePdfRow(Integer band, String rowNo, String indexNumber, String studentName,
			String date1, String mark1, String date2, String mark2, String date3, String mark3,
			String date4, String mark4, String date5, String mark5, String date6, String mark6) {
		this.band = band;
		this.rowNo = rowNo;
		this.indexNumber = indexNumber;
		this.studentName = studentName;
		this.date1 = date1;
		this.mark1 = mark1;
		this.date2 = date2;
		this.mark2 = mark2;
		this.date3 = date3;
		this.mark3 = mark3;
		this.date4 = date4;
		this.mark4 = mark4;
		this.date5 = date5;
		this.mark5 = mark5;
		this.date6 = date6;
		this.mark6 = mark6;
	}

	public Integer getBand() { return band; }
	public String getRowNo() { return rowNo; }
	public String getIndexNumber() { return indexNumber; }
	public String getStudentName() { return studentName; }
	public String getDate1() { return date1; }
	public String getMark1() { return mark1; }
	public String getDate2() { return date2; }
	public String getMark2() { return mark2; }
	public String getDate3() { return date3; }
	public String getMark3() { return mark3; }
	public String getDate4() { return date4; }
	public String getMark4() { return mark4; }
	public String getDate5() { return date5; }
	public String getMark5() { return mark5; }
	public String getDate6() { return date6; }
	public String getMark6() { return mark6; }

}

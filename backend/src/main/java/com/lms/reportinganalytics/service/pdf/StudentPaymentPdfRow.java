package com.lms.reportinganalytics.service.pdf;

/**
 * One flattened RPT-02 PDF row ({@code student-payment-report.jrxml}): the
 * currency section it belongs to (with its precomputed KPI and summary
 * texts), its group headers / subtotals ({@code g1} outer, {@code g2} nested)
 * and the detail cells. Every value is a display string formatted from the
 * stored run - the template does no arithmetic.
 */
public final class StudentPaymentPdfRow {

	private final String sectionKey;

	private final String sectionTitle;

	private final String kpiTotal;

	private final String kpiStudents;

	private final String kpiPayments;

	private final String summaryLines;

	private final String amountHeader;

	private final String g1Key;

	private final Boolean g1Show;

	private final String g1Title;

	private final String g1Label;

	private final String g1Amount;

	private final String g1Fee;

	private final String g1Postage;

	private final String g2Key;

	private final Boolean g2Show;

	private final String g2Title;

	private final String g2Label;

	private final String g2Amount;

	private final String g2Fee;

	private final String g2Postage;

	private final String rowNo;

	private final String indexNumber;

	private final String studentName;

	private final String courseName;

	private final String reason;

	private final String method;

	private final String date;

	private final String amount;

	private final String serviceFee;

	private final String postage;

	StudentPaymentPdfRow(Section section, Group g1, Group g2, Cells cells) {
		this.sectionKey = section.key();
		this.sectionTitle = section.title();
		this.kpiTotal = section.kpiTotal();
		this.kpiStudents = section.kpiStudents();
		this.kpiPayments = section.kpiPayments();
		this.summaryLines = section.summaryLines();
		this.amountHeader = section.amountHeader();
		this.g1Key = g1.key();
		this.g1Show = g1.show();
		this.g1Title = g1.title();
		this.g1Label = g1.label();
		this.g1Amount = g1.amount();
		this.g1Fee = g1.fee();
		this.g1Postage = g1.postage();
		this.g2Key = g2.key();
		this.g2Show = g2.show();
		this.g2Title = g2.title();
		this.g2Label = g2.label();
		this.g2Amount = g2.amount();
		this.g2Fee = g2.fee();
		this.g2Postage = g2.postage();
		this.rowNo = cells.rowNo();
		this.indexNumber = cells.indexNumber();
		this.studentName = cells.studentName();
		this.courseName = cells.courseName();
		this.reason = cells.reason();
		this.method = cells.method();
		this.date = cells.date();
		this.amount = cells.amount();
		this.serviceFee = cells.serviceFee();
		this.postage = cells.postage();
	}

	/** Currency-section header values (identical on every row of the section). */
	record Section(String key, String title, String kpiTotal, String kpiStudents, String kpiPayments,
			String summaryLines, String amountHeader) {

	}

	/** A group header / subtotal (identical on every row of the group); {@code show = false} for no group. */
	record Group(String key, Boolean show, String title, String label, String amount, String fee, String postage) {

	}

	record Cells(String rowNo, String indexNumber, String studentName, String courseName, String reason,
			String method, String date, String amount, String serviceFee, String postage) {

	}

	public String getSectionKey() {
		return sectionKey;
	}

	public String getSectionTitle() {
		return sectionTitle;
	}

	public String getKpiTotal() {
		return kpiTotal;
	}

	public String getKpiStudents() {
		return kpiStudents;
	}

	public String getKpiPayments() {
		return kpiPayments;
	}

	public String getSummaryLines() {
		return summaryLines;
	}

	public String getAmountHeader() {
		return amountHeader;
	}

	public String getG1Key() {
		return g1Key;
	}

	public Boolean getG1Show() {
		return g1Show;
	}

	public String getG1Title() {
		return g1Title;
	}

	public String getG1Label() {
		return g1Label;
	}

	public String getG1Amount() {
		return g1Amount;
	}

	public String getG1Fee() {
		return g1Fee;
	}

	public String getG1Postage() {
		return g1Postage;
	}

	public String getG2Key() {
		return g2Key;
	}

	public Boolean getG2Show() {
		return g2Show;
	}

	public String getG2Title() {
		return g2Title;
	}

	public String getG2Label() {
		return g2Label;
	}

	public String getG2Amount() {
		return g2Amount;
	}

	public String getG2Fee() {
		return g2Fee;
	}

	public String getG2Postage() {
		return g2Postage;
	}

	public String getRowNo() {
		return rowNo;
	}

	public String getIndexNumber() {
		return indexNumber;
	}

	public String getStudentName() {
		return studentName;
	}

	public String getCourseName() {
		return courseName;
	}

	public String getReason() {
		return reason;
	}

	public String getMethod() {
		return method;
	}

	public String getDate() {
		return date;
	}

	public String getAmount() {
		return amount;
	}

	public String getServiceFee() {
		return serviceFee;
	}

	public String getPostage() {
		return postage;
	}

}

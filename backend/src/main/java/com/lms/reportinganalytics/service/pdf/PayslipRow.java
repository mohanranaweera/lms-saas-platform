package com.lms.reportinganalytics.service.pdf;

import java.math.BigDecimal;

/**
 * One flattened payslip row ({@code teacher-payslip.jrxml}): a currency group
 * plus one course line. Values come from the stored run only.
 */
public final class PayslipRow {

	private final String currencyKey;

	private final String amountsInLabel;

	private final String amountPattern;

	private final BigDecimal totalIncome;

	private final Boolean hasCourse;

	private final String courseName;

	private final String paymentLabel;

	private final BigDecimal teacherAmount;

	private final String rateNote;

	PayslipRow(String currencyKey, String amountsInLabel, String amountPattern, BigDecimal totalIncome,
			Boolean hasCourse, String courseName, String paymentLabel, BigDecimal teacherAmount, String rateNote) {
		this.currencyKey = currencyKey;
		this.amountsInLabel = amountsInLabel;
		this.amountPattern = amountPattern;
		this.totalIncome = totalIncome;
		this.hasCourse = hasCourse;
		this.courseName = courseName;
		this.paymentLabel = paymentLabel;
		this.teacherAmount = teacherAmount;
		this.rateNote = rateNote;
	}

	public String getCurrencyKey() {
		return currencyKey;
	}

	public String getAmountsInLabel() {
		return amountsInLabel;
	}

	public String getAmountPattern() {
		return amountPattern;
	}

	public BigDecimal getTotalIncome() {
		return totalIncome;
	}

	public Boolean getHasCourse() {
		return hasCourse;
	}

	public String getCourseName() {
		return courseName;
	}

	public String getPaymentLabel() {
		return paymentLabel;
	}

	public BigDecimal getTeacherAmount() {
		return teacherAmount;
	}

	public String getRateNote() {
		return rateNote;
	}

}

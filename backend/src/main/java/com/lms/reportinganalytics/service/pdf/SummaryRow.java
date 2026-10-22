package com.lms.reportinganalytics.service.pdf;

import java.math.BigDecimal;

/**
 * One flattened row fed to {@code teacher-income-summary.jrxml} through a
 * {@code JRBeanCollectionDataSource}: section (currency) values, teacher values
 * and one course row. Group headers read the section/teacher values of their
 * first row. All values are precomputed from the stored run - the template only
 * displays/formats them. A teacher with payouts but no course row gets one row
 * with {@code hasCourse = false}.
 */
public final class SummaryRow {

	private final String sectionKey;

	private final String currency;

	private final String amountPattern;

	private final BigDecimal sectionRevenue;

	private final BigDecimal sectionTeacherIncome;

	private final BigDecimal sectionCommission;

	private final Integer sectionTeacherCount;

	private final BigDecimal sectionServiceFees;

	private final String teacherKey;

	private final String teacherName;

	private final BigDecimal teacherIncome;

	private final BigDecimal teacherPayouts;

	private final BigDecimal teacherBalance;

	private final Boolean hasCourse;

	private final String courseName;

	private final Long paymentCount;

	private final BigDecimal revenue;

	private final BigDecimal courseTeacherIncome;

	private final BigDecimal commission;

	private final String rateNote;

	SummaryRow(String sectionKey, String currency, String amountPattern, BigDecimal sectionRevenue,
			BigDecimal sectionTeacherIncome, BigDecimal sectionCommission, Integer sectionTeacherCount,
			BigDecimal sectionServiceFees, String teacherKey, String teacherName, BigDecimal teacherIncome,
			BigDecimal teacherPayouts, BigDecimal teacherBalance, Boolean hasCourse, String courseName,
			Long paymentCount, BigDecimal revenue, BigDecimal courseTeacherIncome, BigDecimal commission,
			String rateNote) {
		this.sectionKey = sectionKey;
		this.currency = currency;
		this.amountPattern = amountPattern;
		this.sectionRevenue = sectionRevenue;
		this.sectionTeacherIncome = sectionTeacherIncome;
		this.sectionCommission = sectionCommission;
		this.sectionTeacherCount = sectionTeacherCount;
		this.sectionServiceFees = sectionServiceFees;
		this.teacherKey = teacherKey;
		this.teacherName = teacherName;
		this.teacherIncome = teacherIncome;
		this.teacherPayouts = teacherPayouts;
		this.teacherBalance = teacherBalance;
		this.hasCourse = hasCourse;
		this.courseName = courseName;
		this.paymentCount = paymentCount;
		this.revenue = revenue;
		this.courseTeacherIncome = courseTeacherIncome;
		this.commission = commission;
		this.rateNote = rateNote;
	}

	public String getSectionKey() {
		return sectionKey;
	}

	public String getCurrency() {
		return currency;
	}

	public String getAmountPattern() {
		return amountPattern;
	}

	public BigDecimal getSectionRevenue() {
		return sectionRevenue;
	}

	public BigDecimal getSectionTeacherIncome() {
		return sectionTeacherIncome;
	}

	public BigDecimal getSectionCommission() {
		return sectionCommission;
	}

	public Integer getSectionTeacherCount() {
		return sectionTeacherCount;
	}

	public BigDecimal getSectionServiceFees() {
		return sectionServiceFees;
	}

	public String getTeacherKey() {
		return teacherKey;
	}

	public String getTeacherName() {
		return teacherName;
	}

	public BigDecimal getTeacherIncome() {
		return teacherIncome;
	}

	public BigDecimal getTeacherPayouts() {
		return teacherPayouts;
	}

	public BigDecimal getTeacherBalance() {
		return teacherBalance;
	}

	public Boolean getHasCourse() {
		return hasCourse;
	}

	public String getCourseName() {
		return courseName;
	}

	public Long getPaymentCount() {
		return paymentCount;
	}

	public BigDecimal getRevenue() {
		return revenue;
	}

	public BigDecimal getCourseTeacherIncome() {
		return courseTeacherIncome;
	}

	public BigDecimal getCommission() {
		return commission;
	}

	public String getRateNote() {
		return rateNote;
	}

}

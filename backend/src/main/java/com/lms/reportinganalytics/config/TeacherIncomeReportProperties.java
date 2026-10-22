package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RPT-01 resource bounds ({@code app.reporting.teacher-income.*}), enforced
 * before/while a run is computed so one request cannot exhaust memory, Redis
 * or the database.
 * <ul>
 * <li>{@code maxLedgerEntries} (50,000): ledger entries a run may load,
 * checked with a count query BEFORE enrichment; above it the run is {@code 422
 * REPORT_TOO_LARGE}.</li>
 * <li>{@code maxCourseRows} (5,000): course rows in one run dataset (same bound
 * as the PDF export).</li>
 * <li>{@code maxRunBytes} (2 MB): serialized size of the stored run snapshot.</li>
 * <li>{@code runsPerWindow} / {@code runWindow} (10 per minute): {@code POST
 * /runs} per (tenant, user); beyond it {@code 429}.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.reporting.teacher-income")
public class TeacherIncomeReportProperties {

	private int maxLedgerEntries = 50_000;

	private int maxCourseRows = 5_000;

	private int maxRunBytes = 2 * 1024 * 1024;

	private int runsPerWindow = 10;

	private Duration runWindow = Duration.ofMinutes(1);

	public int getMaxLedgerEntries() {
		return maxLedgerEntries;
	}

	public void setMaxLedgerEntries(int maxLedgerEntries) {
		this.maxLedgerEntries = maxLedgerEntries;
	}

	public int getMaxCourseRows() {
		return maxCourseRows;
	}

	public void setMaxCourseRows(int maxCourseRows) {
		this.maxCourseRows = maxCourseRows;
	}

	public int getMaxRunBytes() {
		return maxRunBytes;
	}

	public void setMaxRunBytes(int maxRunBytes) {
		this.maxRunBytes = maxRunBytes;
	}

	public int getRunsPerWindow() {
		return runsPerWindow;
	}

	public void setRunsPerWindow(int runsPerWindow) {
		this.runsPerWindow = runsPerWindow;
	}

	public Duration getRunWindow() {
		return runWindow;
	}

	public void setRunWindow(Duration runWindow) {
		this.runWindow = runWindow;
	}

}

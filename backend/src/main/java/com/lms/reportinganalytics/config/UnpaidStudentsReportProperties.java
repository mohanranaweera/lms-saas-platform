package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Resource limits for Unpaid Students Report snapshots and exports. */
@Component
@ConfigurationProperties(prefix = "app.reporting.unpaid-students")
public class UnpaidStudentsReportProperties {

	private int maxParticipants = 10_000;

	private int maxLedgerEntries = 50_000;

	private int maxAttendanceFacts = 50_000;

	private int maxRows = 10_000;

	private int maxPdfRows = 10_000;

	private int maxRunBytes = 8 * 1024 * 1024;

	private int runsPerWindow = 10;

	private int pdfPerWindow = 6;

	private int rowsPerWindow = 120;

	private Duration runWindow = Duration.ofMinutes(1);

	public int getMaxParticipants() { return maxParticipants; }
	public void setMaxParticipants(int value) { this.maxParticipants = value; }
	public int getMaxLedgerEntries() { return maxLedgerEntries; }
	public void setMaxLedgerEntries(int value) { this.maxLedgerEntries = value; }
	public int getMaxAttendanceFacts() { return maxAttendanceFacts; }
	public void setMaxAttendanceFacts(int value) { this.maxAttendanceFacts = value; }
	public int getMaxRows() { return maxRows; }
	public void setMaxRows(int value) { this.maxRows = value; }
	public int getMaxPdfRows() { return maxPdfRows; }
	public void setMaxPdfRows(int value) { this.maxPdfRows = value; }
	public int getMaxRunBytes() { return maxRunBytes; }
	public void setMaxRunBytes(int value) { this.maxRunBytes = value; }
	public int getRunsPerWindow() { return runsPerWindow; }
	public void setRunsPerWindow(int value) { this.runsPerWindow = value; }
	public int getPdfPerWindow() { return pdfPerWindow; }
	public void setPdfPerWindow(int value) { this.pdfPerWindow = value; }
	public int getRowsPerWindow() { return rowsPerWindow; }
	public void setRowsPerWindow(int value) { this.rowsPerWindow = value; }
	public Duration getRunWindow() { return runWindow; }
	public void setRunWindow(Duration value) { this.runWindow = value; }

}

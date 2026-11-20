package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RPT-03 Attendance Report resource bounds. A run is a short-lived Redis
 * snapshot; limits apply before a wide date matrix can consume excessive
 * database, heap, PDF or Redis resources.
 */
@Component
@ConfigurationProperties(prefix = "app.reporting.attendance")
public class AttendanceReportProperties {

	private int maxFacts = 50_000;

	private int maxStudents = 5_000;

	private int maxDateColumns = 366;

	private int maxMatrixCells = 250_000;

	private int maxPdfRows = 5_000;

	private int maxRunBytes = 8 * 1024 * 1024;

	private int runsPerWindow = 10;

	private int pdfPerWindow = 6;

	private Duration runWindow = Duration.ofMinutes(1);

	public int getMaxFacts() {
		return maxFacts;
	}

	public void setMaxFacts(int maxFacts) {
		this.maxFacts = maxFacts;
	}

	public int getMaxStudents() {
		return maxStudents;
	}

	public void setMaxStudents(int maxStudents) {
		this.maxStudents = maxStudents;
	}

	public int getMaxDateColumns() {
		return maxDateColumns;
	}

	public void setMaxDateColumns(int maxDateColumns) {
		this.maxDateColumns = maxDateColumns;
	}

	public int getMaxMatrixCells() {
		return maxMatrixCells;
	}

	public void setMaxMatrixCells(int maxMatrixCells) {
		this.maxMatrixCells = maxMatrixCells;
	}

	public int getMaxPdfRows() {
		return maxPdfRows;
	}

	public void setMaxPdfRows(int maxPdfRows) {
		this.maxPdfRows = maxPdfRows;
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

	public int getPdfPerWindow() {
		return pdfPerWindow;
	}

	public void setPdfPerWindow(int pdfPerWindow) {
		this.pdfPerWindow = pdfPerWindow;
	}

	public Duration getRunWindow() {
		return runWindow;
	}

	public void setRunWindow(Duration runWindow) {
		this.runWindow = runWindow;
	}

}

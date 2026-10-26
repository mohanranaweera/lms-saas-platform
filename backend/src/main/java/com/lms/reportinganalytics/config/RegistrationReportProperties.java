package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Resource limits for Registration Report snapshots and exports. */
@Component
@ConfigurationProperties(prefix = "app.reporting.registration")
public class RegistrationReportProperties {
	private int maxRows = 20_000;
	private int maxPdfRows = 10_000;
	private int maxRunBytes = 12 * 1024 * 1024;
	private int runsPerWindow = 10;
	private int pdfPerWindow = 6;
	private int rowsPerWindow = 120;
	private Duration runWindow = Duration.ofMinutes(1);
	public int getMaxRows() { return maxRows; }
	public void setMaxRows(int value) { maxRows = value; }
	public int getMaxPdfRows() { return maxPdfRows; }
	public void setMaxPdfRows(int value) { maxPdfRows = value; }
	public int getMaxRunBytes() { return maxRunBytes; }
	public void setMaxRunBytes(int value) { maxRunBytes = value; }
	public int getRunsPerWindow() { return runsPerWindow; }
	public void setRunsPerWindow(int value) { runsPerWindow = value; }
	public int getPdfPerWindow() { return pdfPerWindow; }
	public void setPdfPerWindow(int value) { pdfPerWindow = value; }
	public int getRowsPerWindow() { return rowsPerWindow; }
	public void setRowsPerWindow(int value) { rowsPerWindow = value; }
	public Duration getRunWindow() { return runWindow; }
	public void setRunWindow(Duration value) { runWindow = value; }
}

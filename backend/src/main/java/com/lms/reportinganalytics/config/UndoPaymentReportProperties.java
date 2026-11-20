package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.reporting.undo-payments")
public class UndoPaymentReportProperties {
	private int maxRows = 20000;
	private int maxPdfRows = 10000;
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

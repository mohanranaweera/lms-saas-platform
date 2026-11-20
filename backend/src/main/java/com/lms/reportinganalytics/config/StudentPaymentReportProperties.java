package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RPT-02 Student Payment Report resource bounds ({@code
 * app.reporting.student-payments.*}, spec section 8), enforced before/while a
 * run is computed so one request cannot exhaust memory, Redis or the database.
 * <ul>
 * <li>{@code maxLedgerEntries} (50,000): ledger entries a run may load, counted
 * BEFORE enrichment; above it the run is {@code 422 REPORT_TOO_LARGE}.</li>
 * <li>{@code maxRows} (20,000): detail rows in one stored run.</li>
 * <li>{@code maxPdfRows} (10,000): rows one PDF export may render.</li>
 * <li>{@code maxRunBytes} (8 MB): serialized size of the stored run snapshot
 * (every page / PDF request deserializes it, so it is kept small; on very wide
 * runs this byte bound can apply before the row bound).</li>
 * <li>{@code runsPerWindow} / {@code runWindow} (10 per minute): {@code POST
 * /runs} per (tenant, user); beyond it {@code 429}.</li>
 * <li>{@code pdfPerWindow} (6 per minute) and {@code rowsPerWindow} (120 per
 * minute), same window: {@code GET .../pdf} and {@code GET .../rows} per
 * (tenant, user); beyond them {@code 429}.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.reporting.student-payments")
public class StudentPaymentReportProperties {

	private int maxLedgerEntries = 50_000;

	private int maxRows = 20_000;

	private int maxPdfRows = 10_000;

	private int maxRunBytes = 8 * 1024 * 1024;

	private int runsPerWindow = 10;

	private Duration runWindow = Duration.ofMinutes(1);

	private int pdfPerWindow = 6;

	private int rowsPerWindow = 120;

	public int getPdfPerWindow() {
		return pdfPerWindow;
	}

	public void setPdfPerWindow(int pdfPerWindow) {
		this.pdfPerWindow = pdfPerWindow;
	}

	public int getRowsPerWindow() {
		return rowsPerWindow;
	}

	public void setRowsPerWindow(int rowsPerWindow) {
		this.rowsPerWindow = rowsPerWindow;
	}

	public int getMaxLedgerEntries() {
		return maxLedgerEntries;
	}

	public void setMaxLedgerEntries(int maxLedgerEntries) {
		this.maxLedgerEntries = maxLedgerEntries;
	}

	public int getMaxRows() {
		return maxRows;
	}

	public void setMaxRows(int maxRows) {
		this.maxRows = maxRows;
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

	public Duration getRunWindow() {
		return runWindow;
	}

	public void setRunWindow(Duration runWindow) {
		this.runWindow = runWindow;
	}

}

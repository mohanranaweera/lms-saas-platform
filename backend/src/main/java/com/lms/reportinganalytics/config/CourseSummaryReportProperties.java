package com.lms.reportinganalytics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** RPT-05 database, heap, snapshot, and PDF workload bounds. */
@Component
@ConfigurationProperties(prefix = "app.reporting.course-summary")
public class CourseSummaryReportProperties {

	private int maxFacts = 50_000;
	private int maxLedgerEntries = 50_000;
	private int maxRunBytes = 4 * 1024 * 1024;
	private int runsPerWindow = 10;
	private int pdfPerWindow = 6;
	private Duration runWindow = Duration.ofMinutes(1);

	public int getMaxFacts() { return maxFacts; }
	public void setMaxFacts(int value) { maxFacts = value; }
	public int getMaxLedgerEntries() { return maxLedgerEntries; }
	public void setMaxLedgerEntries(int value) { maxLedgerEntries = value; }
	public int getMaxRunBytes() { return maxRunBytes; }
	public void setMaxRunBytes(int value) { maxRunBytes = value; }
	public int getRunsPerWindow() { return runsPerWindow; }
	public void setRunsPerWindow(int value) { runsPerWindow = value; }
	public int getPdfPerWindow() { return pdfPerWindow; }
	public void setPdfPerWindow(int value) { pdfPerWindow = value; }
	public Duration getRunWindow() { return runWindow; }
	public void setRunWindow(Duration value) { runWindow = value; }

}

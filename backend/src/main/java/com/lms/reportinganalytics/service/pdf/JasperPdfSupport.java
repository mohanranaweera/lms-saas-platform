package com.lms.reportinganalytics.service.pdf;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.pdf.SimplePdfExporterConfiguration;

/**
 * Shared by RPT-01 and RPT-02: fill a precompiled, application-owned template
 * from typed beans with a fresh parameter map, export it to PDF, and the
 * common display formats. One PDF engine (JasperReports) for every report.
 */
final class JasperPdfSupport {

	/** Generated-at timestamp shown in report headers and footers. */
	static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH);

	private JasperPdfSupport() {
	}

	/** {@code #,##0.00} with the currency prefix: "Rs." for LKR, else the ISO code. */
	static String amountPattern(String currency) {
		String prefix = "LKR".equals(currency) ? "Rs." : currency.replace("'", "");
		return "'" + prefix + "' #,##0.00;'" + prefix + "' -#,##0.00";
	}

	static JasperPrint fill(JasperReport report, Map<String, Object> params, List<?> beans) {
		try {
			return JasperFillManager.getInstance(DefaultJasperReportsContext.getInstance())
				.fill(report, params, new JRBeanCollectionDataSource(beans));
		}
		catch (JRException ex) {
			throw new IllegalStateException("Report rendering failed", ex);
		}
	}

	static byte[] export(JasperPrint print, String title) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			JRPdfExporter exporter = new JRPdfExporter(DefaultJasperReportsContext.getInstance());
			exporter.setExporterInput(new SimpleExporterInput(print));
			exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
			SimplePdfExporterConfiguration configuration = new SimplePdfExporterConfiguration();
			configuration.setMetadataTitle(title);
			configuration.setMetadataCreator("LMS");
			exporter.setConfiguration(configuration);
			exporter.exportReport();
			return out.toByteArray();
		}
		catch (JRException ex) {
			throw new IllegalStateException("PDF export failed", ex);
		}
	}

}

package com.lms.reportinganalytics.service.pdf;

import com.lms.common.error.NotFoundException;
import com.lms.reportinganalytics.service.ReportValidationException;
import com.lms.reportinganalytics.service.TeacherIncomeReport;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CourseRow;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Section;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherBlock;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherRef;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.pdf.SimplePdfExporterConfiguration;
import org.springframework.stereotype.Component;

/**
 * RPT-01 PDF rendering. Input is ONLY a stored {@link TeacherIncomeReport}
 * run: this class flattens it into typed row beans + typed parameters and
 * fills the shared, precompiled templates with a fresh parameter map per call.
 * It computes no financial figure (amount strings are parsed back to {@link
 * BigDecimal} exactly) and never touches the database.
 */
@Component
public class TeacherIncomePdfRenderer {

	/** Upper bound on course rows in one summary PDF (larger runs are a 422). */
	public static final int MAX_PDF_ROWS = 5000;

	static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH);

	private final ReportTemplates templates;

	public TeacherIncomePdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	// ------------------------------------------------------------------
	// Summary.
	// ------------------------------------------------------------------

	public byte[] renderSummary(TeacherIncomeReport report) {
		return export(fillSummary(report), "Teacher Income Summary Report");
	}

	public JasperPrint fillSummary(TeacherIncomeReport report) {
		int rows = report.sections().stream().flatMap(s -> s.teachers().stream()).mapToInt(t -> t.courses().size())
			.sum();
		if (rows > MAX_PDF_ROWS) {
			throw ReportValidationException.tooLarge(rows, MAX_PDF_ROWS);
		}
		List<SummaryRow> beans = new ArrayList<>();
		for (Section section : report.sections()) {
			String pattern = amountPattern(section.currency());
			for (TeacherBlock teacher : section.teachers()) {
				String teacherKey = section.currency() + ":" + teacher.teacherId();
				if (teacher.courses().isEmpty()) {
					beans.add(summaryRow(section, pattern, teacher, teacherKey, null));
				}
				for (CourseRow course : teacher.courses()) {
					beans.add(summaryRow(section, pattern, teacher, teacherKey, course));
				}
			}
		}
		Map<String, Object> params = baseParameters(report);
		params.put("FILTER_LINE", filterLine(report));
		params.put("GENERATED_LINE", "Generated: " + params.get("GENERATED_AT") + " (" + report.timezone() + ")");
		params.put("EMPTY", beans.isEmpty());
		params.put("NOTES", String.join("\n", report.reconciliation().notes()));
		return fill(templates.summary(), params, beans);
	}

	private static SummaryRow summaryRow(Section section, String pattern, TeacherBlock teacher, String teacherKey,
			CourseRow course) {
		String rateNote = course != null && course.rateMissing() ? "*" : null;
		return new SummaryRow(section.currency(), section.currency(), pattern, dec(section.totals().revenue()),
				dec(section.totals().teacherIncome()), dec(section.totals().commission()),
				section.totals().teacherCount(), dec(section.totals().serviceFees()), teacherKey, teacher.teacherName(),
				dec(teacher.totals().teacherIncome()), dec(teacher.totals().payouts()), dec(teacher.totals().balance()),
				course != null, course == null ? null : courseLabel(course),
				course == null ? null : course.paymentCount(), course == null ? null : dec(course.revenue()),
				course == null ? null : dec(course.teacherIncome()), course == null ? null : dec(course.commission()),
				rateNote);
	}

	// ------------------------------------------------------------------
	// Payslip (informational only - generating it writes nothing).
	// ------------------------------------------------------------------

	public byte[] renderPayslip(TeacherIncomeReport report, UUID teacherId) {
		return export(fillPayslip(report, teacherId), "Payslip");
	}

	public JasperPrint fillPayslip(TeacherIncomeReport report, UUID teacherId) {
		TeacherRef ref = report.filters().teachers().stream()
			.filter(t -> t.id().equals(teacherId))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Teacher is not part of this report run"));
		List<PayslipRow> beans = new ArrayList<>();
		for (Section section : report.sections()) {
			for (TeacherBlock teacher : section.teachers()) {
				if (!teacher.teacherId().equals(teacherId)) {
					continue;
				}
				String pattern = amountPattern(section.currency());
				String label = "Amounts in " + section.currency();
				BigDecimal total = dec(teacher.totals().teacherIncome());
				if (teacher.courses().isEmpty()) {
					beans.add(new PayslipRow(section.currency(), label, pattern, total, false, null, null, null, null));
				}
				// Courses are already sorted by name, id in the stored run.
				for (CourseRow course : teacher.courses()) {
					beans.add(new PayslipRow(section.currency(), label, pattern, total, true, courseLabel(course),
							course.paymentCount() + (course.paymentCount() == 1 ? " payment" : " payments"),
							dec(course.teacherIncome()), course.rateMissing() ? "*" : null));
				}
			}
		}
		Map<String, Object> params = baseParameters(report);
		params.put("TEACHER_NAME", ref.name());
		params.put("PERIOD_LABEL", report.filters().periodLabel());
		params.put("RATE_LEGEND", beans.stream().anyMatch(row -> row.getRateNote() != null)
				? "* Part of this course has no teacher share rate; it is not included in the amount "
						+ "(— = no rated part)."
				: null);
		params.put("EMPTY", beans.isEmpty());
		return fill(templates.payslip(), params, beans);
	}

	// ------------------------------------------------------------------

	private static Map<String, Object> baseParameters(TeacherIncomeReport report) {
		Map<String, Object> params = new HashMap<>();
		String institute = report.institute() == null || report.institute().name() == null ? ""
				: report.institute().name();
		String generated = TIMESTAMP.format(report.generatedAt().atZone(ZoneId.of(report.timezone())));
		params.put("INSTITUTE", institute);
		params.put("GENERATED_AT", generated);
		params.put("TIMEZONE", report.timezone());
		params.put("FOOTER_TEXT", "Report generated on " + generated + " | " + institute);
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return params;
	}

	static String filterLine(TeacherIncomeReport report) {
		var filters = report.filters();
		String mode = "MONTHS".equals(filters.periodMode()) ? "Months: " : "Date range: ";
		List<String> names = filters.teachers().stream().map(TeacherRef::name).toList();
		return mode + filters.periodLabel() + "   |   Teachers: " + String.join(", ", names)
				+ "   |   Payment method: " + methodLabel(filters.paymentMethod());
	}

	static String methodLabel(String method) {
		if (method == null) {
			return "All";
		}
		return switch (method) {
			case "GATEWAY" -> "Online payment";
			case "MANUAL_SLIP" -> "Bank slip";
			case "MANUAL_RECEIPT" -> "Manual receipt";
			default -> method;
		};
	}

	/** Course name with the "*" footnote marker when part of the row has no share rate (legend in Notes). */
	static String courseLabel(CourseRow course) {
		return course.rateMissing() ? course.courseName() + " *" : course.courseName();
	}

	/** {@code #,##0.00} with the currency prefix: "Rs." for LKR, else the ISO code. */
	static String amountPattern(String currency) {
		String prefix = "LKR".equals(currency) ? "Rs." : currency.replace("'", "");
		return "'" + prefix + "' #,##0.00;'" + prefix + "' -#,##0.00";
	}

	private static BigDecimal dec(String amount) {
		return amount == null ? null : new BigDecimal(amount);
	}

	private static JasperPrint fill(JasperReport report, Map<String, Object> params, List<?> beans) {
		try {
			return JasperFillManager.getInstance(DefaultJasperReportsContext.getInstance())
				.fill(report, params, new JRBeanCollectionDataSource(beans));
		}
		catch (JRException ex) {
			throw new IllegalStateException("Report rendering failed", ex);
		}
	}

	private static byte[] export(JasperPrint print, String title) {
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

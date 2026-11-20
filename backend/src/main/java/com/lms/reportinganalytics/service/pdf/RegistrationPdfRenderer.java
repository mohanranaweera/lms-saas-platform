package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.RegistrationReport;
import com.lms.reportinganalytics.service.RegistrationRow;
import com.lms.reportinganalytics.service.RegistrationRun;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

/** Jasper renderer over one immutable Registration Report snapshot. */
@Component
public class RegistrationPdfRenderer {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
	private final ReportTemplates templates;
	public RegistrationPdfRenderer(ReportTemplates templates) { this.templates = templates; }
	public byte[] render(RegistrationRun run) { return JasperPdfSupport.export(fill(run), "Registration Report"); }
	public JasperPrint fill(RegistrationRun run) {
		RegistrationReport report = run.report(); ZoneId zone = ZoneId.of(report.timezone()); List<RegistrationPdfRow> rows = new ArrayList<>();
		for (int i = 0; i < run.rows().size(); i++) { RegistrationRow row = run.rows().get(i); rows.add(new RegistrationPdfRow(Integer.toString(i + 1), dash(row.indexNumber()), dash(row.studentName()), dash(row.mobile()), row.courses().isEmpty() ? "—" : String.join(", ", row.courses().stream().map(RegistrationReport.CourseRef::name).toList()), dash(row.registeredBy()), dash(row.lastPayment()), DATE.format(row.registrationDate().atZone(zone)))); }
		String generated = JasperPdfSupport.TIMESTAMP.format(report.generatedAt().atZone(zone)); Map<String,Object> params = new HashMap<>();
		params.put("REPORT_TITLE", "Registration Report"); params.put("INSTITUTE", report.institute().name()); params.put("DATE_RANGE", report.filters().startDate() + " to " + report.filters().endDate());
		params.put("COURSES", report.filters().courses().isEmpty() ? "All Courses" : String.join(", ", report.filters().courses().stream().map(RegistrationReport.CourseRef::name).toList()));
		params.put("OPERATOR", report.filters().operator() == null ? "All" : report.filters().operator().name()); params.put("METHOD", report.filters().registrationMethod() == null ? "All" : report.filters().registrationMethod());
		params.put("GENERATED", "Generated: " + generated + " (" + report.timezone() + ")"); params.put("TOTAL", Integer.toString(report.totalRegistrations())); params.put("FOOTER", "Registration Report generated on " + generated + " | " + report.institute().name()); params.put("EMPTY", rows.isEmpty()); params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.registrations(), params, rows);
	}
	private static String dash(String value) { return value == null || value.isBlank() ? "—" : value; }
}

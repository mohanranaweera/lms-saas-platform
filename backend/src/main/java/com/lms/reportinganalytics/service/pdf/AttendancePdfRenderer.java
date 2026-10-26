package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.AttendanceReport;
import com.lms.reportinganalytics.service.AttendanceReport.Row;
import com.lms.reportinganalytics.service.AttendanceReportRun;
import java.time.LocalDate;
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

/** RPT-03 landscape attendance matrix renderer. Date bands preserve readable cells. */
@Component
public class AttendancePdfRenderer {

	static final int DATES_PER_BAND = 6;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

	private static final DateTimeFormatter RANGE_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	private final ReportTemplates templates;

	public AttendancePdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	public byte[] render(AttendanceReportRun run) {
		AttendanceReport report = run.report();
		return JasperPdfSupport.export(fill(report), report.title());
	}

	public JasperPrint fill(AttendanceReport report) {
		ZoneId zone = ZoneId.of(report.timezone());
		List<AttendancePdfRow> rows = rows(report);
		Map<String, Object> params = new HashMap<>();
		params.put("TITLE", report.title());
		params.put("INSTITUTE", report.institute().name());
		params.put("COURSE", report.course().name());
		params.put("DATE_RANGE", RANGE_DATE.format(report.filters().startDate()) + " to "
				+ RANGE_DATE.format(report.filters().endDate()));
		String generated = JasperPdfSupport.TIMESTAMP.format(report.generatedAt().atZone(zone));
		params.put("GENERATED", "Generated: " + generated + " (" + report.timezone() + ")");
		params.put("FOOTER", report.title() + " generated on " + generated + " | " + report.institute().name());
		params.put("TOTAL_RECORDS", Integer.toString(report.totals().totalRecords()));
		params.put("PRESENT", Integer.toString(report.totals().present()));
		params.put("ABSENT", Integer.toString(report.totals().absent()));
		params.put("LEGEND", String.join("   |   ", report.legend().present(), report.legend().absent(), report.legend().unknown()));
		params.put("EMPTY", rows.isEmpty());
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.attendance(), params, rows);
	}

	private static List<AttendancePdfRow> rows(AttendanceReport report) {
		if (report.rows().isEmpty()) {
			return List.of();
		}
		List<AttendancePdfRow> result = new ArrayList<>();
		for (int offset = 0, band = 1; offset < report.dates().size(); offset += DATES_PER_BAND, band++) {
			int end = Math.min(offset + DATES_PER_BAND, report.dates().size());
			List<LocalDate> dates = report.dates().subList(offset, end);
			for (int rowIndex = 0; rowIndex < report.rows().size(); rowIndex++) {
				Row row = report.rows().get(rowIndex);
				String[] columns = new String[12];
				for (int i = 0; i < DATES_PER_BAND; i++) {
					columns[i * 2] = i < dates.size() ? DATE.format(dates.get(i)) : "";
					columns[i * 2 + 1] = i < dates.size() ? row.cells().get(offset + i) : "";
				}
				result.add(new AttendancePdfRow(band, Integer.toString(rowIndex + 1), row.indexNumber(), row.studentName(),
						columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], columns[6], columns[7],
						columns[8], columns[9], columns[10], columns[11]));
			}
		}
		return result;
	}

}

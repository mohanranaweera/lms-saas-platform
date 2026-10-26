package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.UnpaidStudentRow;
import com.lms.reportinganalytics.service.UnpaidStudentsReport;
import com.lms.reportinganalytics.service.UnpaidStudentsRun;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

/** Jasper PDF renderer over the immutable Unpaid Students run snapshot. */
@Component
public class UnpaidStudentsPdfRenderer {

	private final ReportTemplates templates;

	public UnpaidStudentsPdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	public byte[] render(UnpaidStudentsRun run) {
		return JasperPdfSupport.export(fill(run), "Unpaid Students Report");
	}

	public JasperPrint fill(UnpaidStudentsRun run) {
		UnpaidStudentsReport report = run.report();
		ZoneId zone = ZoneId.of(report.timezone());
		List<UnpaidStudentsPdfRow> rows = new ArrayList<>();
		for (int i = 0; i < run.rows().size(); i++) {
			UnpaidStudentRow row = run.rows().get(i);
			rows.add(new UnpaidStudentsPdfRow(Integer.toString(i + 1), dash(row.indexNumber()), row.studentName(),
					dash(row.mobile()), Integer.toString(row.attendanceCount())));
		}
		String generated = JasperPdfSupport.TIMESTAMP.format(report.generatedAt().atZone(zone));
		Map<String, Object> params = new HashMap<>();
		params.put("INSTITUTE", report.institute().name());
		params.put("COURSE", report.course().name());
		params.put("MONTH", report.filters().billingMonthLabel());
		params.put("ATTENDED", report.filters().atLeastOneAttended() ? "Yes" : "No");
		params.put("GENERATED", "Generated: " + generated + " (" + report.timezone() + ")");
		params.put("UNPAID_COUNT", Integer.toString(report.unpaidCount()));
		params.put("FOOTER", "Unpaid Students Report generated on " + generated + " | " + report.institute().name());
		params.put("EMPTY", rows.isEmpty());
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.unpaidStudents(), params, rows);
	}

	private static String dash(String value) {
		return value == null || value.isBlank() ? "—" : value;
	}

}

package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.CourseSummaryReport;
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

/** RPT-05 portrait PDF renderer. Presentation rows are already fully calculated. */
@Component
public class CourseSummaryPdfRenderer {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);

	private static final DateTimeFormatter GENERATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

	private final ReportTemplates templates;

	public CourseSummaryPdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	public byte[] render(CourseSummaryReport report) {
		return JasperPdfSupport.export(fill(report), report.title());
	}

	public JasperPrint fill(CourseSummaryReport report) {
		ZoneId zone = ZoneId.of(report.timezone());
		String generated = GENERATED.format(report.generatedAt().atZone(zone));
		Map<String, Object> params = new HashMap<>();
		params.put("TITLE", "Course Summary Report");
		params.put("INSTITUTE", report.institute().name());
		params.put("COURSE", report.course().name());
		params.put("TEACHER", report.teacher().name());
		params.put("PERIOD", "From " + DATE.format(report.filters().startDate()) + " to "
				+ DATE.format(report.filters().endDate()));
		params.put("GENERATED", generated);
		params.put("FOOTER_TEXT", "Report generated on " + generated + " | " + report.institute().name());
		params.put("TOTAL_STUDENTS", Integer.toString(report.kpis().totalStudents()));
		params.put("NEW_REGISTRATIONS", Integer.toString(report.kpis().newRegistrations()));
		params.put("STUDENTS_PAID", Integer.toString(report.kpis().payments()));
		params.put("SHARE_RATE", report.kpis().commissionPercent());
		params.put("CURRENCY", report.institute().currency());
		params.put("EMPTY", report.weekly().isEmpty());
		for (int index = 0; index < 4; index++) {
			var row = index < report.cumulative().size() ? report.cumulative().get(index) : null;
			String suffix = Integer.toString(index + 1);
			params.put("CUM_CATEGORY_" + suffix, row == null ? "" : row.category());
			params.put("CUM_COUNT_" + suffix, row == null ? "" : row.tracked() ? Integer.toString(row.count()) : "Not tracked");
			params.put("CUM_UNIT_" + suffix, row == null ? "" : row.unitPrice());
			params.put("CUM_GROSS_" + suffix, row == null ? "" : row.grossListValue());
			params.put("CUM_COMMISSION_" + suffix, row == null ? "" : row.commission());
			params.put("CUM_SHARE_" + suffix, row == null ? "" : row.teacherShare());
		}
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.courseSummary(), params, rows(report));
	}

	private static List<CourseSummaryPdfRow> rows(CourseSummaryReport report) {
		List<CourseSummaryPdfRow> rows = new ArrayList<>();
		for (var row : report.weekly()) {
			rows.add(new CourseSummaryPdfRow("Week\n" + row.week(), splitDays(row.days()),
					Integer.toString(row.newRegistrations()), Integer.toString(row.classes()),
					Integer.toString(row.attendance()), Integer.toString(row.payments()), row.revenue(),
					row.commission(), row.teacherShare()));
		}
		return rows;
	}

	private static String splitDays(String days) {
		if (days == null) {
			return "";
		}
		int separator = days.indexOf('-');
		return separator < 0 ? days : days.substring(0, separator + 1) + "\n" + days.substring(separator + 1);
	}

}

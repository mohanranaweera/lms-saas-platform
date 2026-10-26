package com.lms.reportinganalytics.service.pdf;

import static com.lms.reportinganalytics.service.pdf.TeacherIncomePdfRendererTest.printedText;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.reportinganalytics.service.CourseSummaryReport;
import com.lms.reportinganalytics.service.CourseSummaryReport.CourseRef;
import com.lms.reportinganalytics.service.CourseSummaryReport.Filters;
import com.lms.reportinganalytics.service.CourseSummaryReport.Institute;
import com.lms.reportinganalytics.service.CourseSummaryReport.Kpis;
import com.lms.reportinganalytics.service.CourseSummaryReport.TeacherRef;
import com.lms.reportinganalytics.service.CourseSummaryReport.WeeklyRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperPrint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CourseSummaryPdfRendererTest {

	private static CourseSummaryPdfRenderer renderer;

	@BeforeAll
	static void setup() { renderer = new CourseSummaryPdfRenderer(new ReportTemplates()); }

	@Test
	void rendersContextKpisWeeklyRowsAndNotTrackedCategory() {
		CourseSummaryReport report = sample(); JasperPrint print = renderer.fill(report); byte[] pdf = renderer.render(report);
		assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
		assertThat(print.getPageWidth()).isEqualTo(595);
		assertThat(print.getPageHeight()).isEqualTo(842);
		assertThat(print.getPages()).hasSize(1);
		assertThat(printedText(print)).contains("Course Summary Report", "Institute", "Course:", "Biology",
				"Teacher:", "Teacher", "Period:", "From 2026-10-01 to 2026-10-31", "Generated:",
				"TOTAL STUDENTS", "STUDENTS PAID", "COMMISSION %", "Weekly Breakdown", "WEEK", "DAYS",
				"NEW", "REG.", "CLASSES", "ATTENDANCE", "PAYMENTS", "Cumulated Summary", "CATEGORY",
				"COUNT", "Full Cards", "Discounted Cards", "Free Card Students", "Not Paid Full Cards",
				"Not tracked", "Report generated on");
		writeSample(pdf);
	}

	@Test
	void jrxmlCompiles() throws Exception {
		try (var in = getClass().getClassLoader().getResourceAsStream(ReportTemplates.COURSE_SUMMARY + ".jrxml")) {
			assertThat(JasperCompileManager.compileReport(in)).isNotNull();
		}
	}

	private static void writeSample(byte[] pdf) {
		try {
			Path directory = Path.of("target", "report-samples");
			Files.createDirectories(directory);
			Files.write(directory.resolve("course-summary-reference.pdf"), pdf);
		}
		catch (java.io.IOException ex) {
			throw new AssertionError("Cannot write PDF sample", ex);
		}
	}

	private static CourseSummaryReport sample() {
		UUID teacher = UUID.randomUUID(), course = UUID.randomUUID();
		return new CourseSummaryReport(UUID.randomUUID(), Instant.parse("2026-10-24T06:15:00Z"), Instant.parse("2026-10-24T06:00:00Z"), "Asia/Colombo", new Institute("Institute", "LKR"), new TeacherRef(teacher, "Teacher"), new CourseRef(course, "Biology"), "Teacher Report", new Filters(teacher, course, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "2026-10", "Teacher Report"), new Kpis(45, 11, 56, "25.00%", 6, 135, "224000.00", "56000.00", "168000.00"), List.of(new WeeklyRow(1, "1-7", 11, 2, 46, 56, "181100.00", "45275.00", "135825.00")), List.of(new CourseSummaryReport.CumulativeRow("Full Cards", 56, "4000.00", "224000.00", "56000.00", "168000.00", true), new CourseSummaryReport.CumulativeRow("Discounted Cards", 0, "0.00", "0.00", "0.00", "0.00", true), new CourseSummaryReport.CumulativeRow("Free Card Students", 0, "—", "—", "—", "—", true), new CourseSummaryReport.CumulativeRow("Not Paid Full Cards", 0, "—", "—", "—", "—", false)), List.of());
	}

}

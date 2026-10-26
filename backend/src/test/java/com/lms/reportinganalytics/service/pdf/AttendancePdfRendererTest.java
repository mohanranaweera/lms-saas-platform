package com.lms.reportinganalytics.service.pdf;

import static com.lms.reportinganalytics.service.pdf.TeacherIncomePdfRendererTest.pdfText;
import static com.lms.reportinganalytics.service.pdf.TeacherIncomePdfRendererTest.printedText;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.reportinganalytics.service.AttendanceReport;
import com.lms.reportinganalytics.service.AttendanceReport.CourseRef;
import com.lms.reportinganalytics.service.AttendanceReport.Filters;
import com.lms.reportinganalytics.service.AttendanceReport.Institute;
import com.lms.reportinganalytics.service.AttendanceReport.Legend;
import com.lms.reportinganalytics.service.AttendanceReport.Row;
import com.lms.reportinganalytics.service.AttendanceReport.Totals;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.type.OrientationEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AttendancePdfRendererTest {

	private static AttendancePdfRenderer renderer;

	@BeforeAll
	static void setUp() {
		renderer = new AttendancePdfRenderer(new ReportTemplates());
	}

	@Test
	void referencePdfIsLandscapeAndContainsTotalsRowsLegendAndLeadingZeros() throws Exception {
		AttendanceReport report = reference();
		JasperPrint print = renderer.fill(report);
		byte[] pdf = JasperPdfSupport.export(print, report.title());

		assertThat(print.getOrientation()).isEqualTo(OrientationEnum.LANDSCAPE);
		assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
		assertThat(printedText(print)).contains("Attendance Report", "Kandy Science Academy", "Biology", "45", "0",
				"0048", "P = Present", "A = Explicitly absent", "No recorded status", "Page 1");
		assertThat(pdfText(pdf)).contains("Attendance Report", "Student 45");
		writeSample("attendance-reference.pdf", pdf);
	}

	@Test
	void wideDateMatrixCreatesBandsAndKeepsAllDatesAndStudents() {
		List<LocalDate> dates = java.util.stream.IntStream.rangeClosed(1, 14)
			.mapToObj(day -> LocalDate.of(2026, 10, day)).toList();
		List<Row> rows = List.of(new Row(UUID.randomUUID(), "0001", "කමලා පෙරේරා",
				dates.stream().map(ignored -> "P").toList()));
		AttendanceReport report = report(dates, rows, new Totals(1, 14, 0));

		JasperPrint print = renderer.fill(report);
		String text = printedText(print);

		assertThat(print.getPages()).hasSizeGreaterThanOrEqualTo(3);
		for (LocalDate date : dates) {
			assertThat(text).contains(date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.ENGLISH)));
		}
		assertThat(text).contains("කමලා පෙරේරා", "0001");
		writeSample("attendance-wide.pdf", JasperPdfSupport.export(print, report.title()));
	}

	@Test
	void emptyReportRendersClearNoDataMessage() {
		AttendanceReport report = report(List.of(), List.of(), new Totals(0, 0, 0));
		assertThat(printedText(renderer.fill(report))).contains("No recorded attendance matches these filters", "0");
	}

	@Test
	void jrxmlCompilesAtRuntime() throws Exception {
		try (var in = getClass().getClassLoader().getResourceAsStream(ReportTemplates.ATTENDANCE + ".jrxml")) {
			assertThat(JasperCompileManager.compileReport(in)).isNotNull();
		}
	}

	private static void writeSample(String name, byte[] bytes) {
		try {
			Path directory = Path.of("target", "report-samples");
			Files.createDirectories(directory);
			Files.write(directory.resolve(name), bytes);
		}
		catch (java.io.IOException ex) {
			throw new AssertionError("Cannot write PDF sample", ex);
		}
	}

	private static AttendanceReport reference() {
		List<LocalDate> dates = List.of(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 7),
				LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17),
				LocalDate.of(2026, 10, 21));
		List<Row> rows = new ArrayList<>();
		for (int index = 1; index <= 45; index++) {
			rows.add(new Row(UUID.randomUUID(), String.format("%04d", index == 1 ? 48 : index),
					"Student " + index, List.of("P", "P", "P", "—", "—", "—")));
		}
		return report(dates, rows, new Totals(45, 135, 0));
	}

	private static AttendanceReport report(List<LocalDate> dates, List<Row> rows, Totals totals) {
		UUID courseId = UUID.randomUUID();
		return new AttendanceReport(UUID.randomUUID(), Instant.parse("2026-10-23T06:36:51Z"),
				Instant.parse("2026-10-23T06:21:51Z"), "Asia/Colombo", new Institute("Kandy Science Academy"),
				new CourseRef(courseId, "Biology"), "Attendance Report",
				new Filters(courseId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 24), "Attendance Report"),
				dates, rows, totals, new Legend("P = Present (including Late)", "A = Explicitly absent",
						"— = No recorded status"));
	}

}

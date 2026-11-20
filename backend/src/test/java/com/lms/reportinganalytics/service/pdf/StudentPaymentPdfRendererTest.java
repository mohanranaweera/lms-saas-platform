package com.lms.reportinganalytics.service.pdf;

import static com.lms.reportinganalytics.service.pdf.TeacherIncomePdfRendererTest.pdfText;
import static com.lms.reportinganalytics.service.pdf.TeacherIncomePdfRendererTest.printedText;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.reportinganalytics.service.StudentPaymentGrouping;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.Group;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.GroupBy;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.Grouped;
import com.lms.reportinganalytics.service.StudentPaymentReport.MethodTotal;
import com.lms.reportinganalytics.service.StudentPaymentReport.Summary;
import com.lms.reportinganalytics.service.StudentPaymentRow;
import com.lms.reportinganalytics.service.StudentPaymentRun;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import net.sf.jasperreports.engine.type.OrientationEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * RPT-02 PDF rendering (no database). Every run comes from {@link
 * StudentPaymentSampleReports}, i.e. from {@code StudentPaymentCalculator}; the
 * samples (and page-1 PNG previews) are written to {@code
 * target/report-samples/rpt02-*} for visual QA.
 */
class StudentPaymentPdfRendererTest {

	private static final Path SAMPLES = Path.of("target", "report-samples");

	private static StudentPaymentPdfRenderer renderer;

	@BeforeAll
	static void loadTemplates() {
		renderer = new StudentPaymentPdfRenderer(new ReportTemplates());
	}

	private static Grouped grouped(StudentPaymentRun run, GroupBy by) {
		return StudentPaymentGrouping.group(run.rows(), by);
	}

	@Test
	void a_m_referencePdfIsLandscapeAndShowsExactlyThePreviewTotals() throws Exception {
		StudentPaymentRun run = StudentPaymentSampleReports.reference();
		Summary summary = run.report().sections().get(0).summary();
		assertThat(summary.coursePaymentTotal()).isEqualTo("20000.00");
		assertThat(summary.serviceFeeTotal()).isEqualTo("500.00");
		JasperPrint print = renderer.fill(run, grouped(run, GroupBy.NONE));
		assertThat(print.getOrientation()).isEqualTo(OrientationEnum.LANDSCAPE);
		assertThat(print.getPageWidth()).isGreaterThan(print.getPageHeight());
		byte[] pdf = renderer.render(run, grouped(run, GroupBy.NONE));
		assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
		String text = pdfText(pdf);
		// M: KPI cards and summary lines = the preview summary.
		assertThat(text).contains("Student Payment Report", "Kandy Science Academy", "TOTAL AMOUNT", "TOTAL STUDENTS",
				"TOTAL PAYMENTS", "Rs. 20,000.00", "Rs. 500.00", "Rs. 20,500.00", "Rs. 12,000.00", "Rs. 8,000.00",
				"Service fees collected on top of these amounts", "Bank transfer (slip)", "Online (gateway)");
		String printed = printedText(print);
		assertThat(printed).contains("5", "Courses: 2026 A/L Combined Mathematics - Theory",
				"Dates: 08 Oct 2026   |", "Payment method: All", "Grouping: None",
				"Generated: 22-10-2026 15:30:00 (Asia/Colombo)", "AMOUNT (LKR)", "SERVICE FEE", "Page 1 of")
			.doesNotContain("POSTAGE");
		for (MethodTotal method : summary.byMethod()) {
			assertThat(text).contains(StudentPaymentPdfRenderer.money("LKR", method.total()));
		}
		// B: index numbers are text with their leading zeros.
		assertThat(printed).contains("0084", "0223", "0031", "0007");
		assertThat(text).contains("0084", "0007");
		assertThat(printed).contains("08 Oct 2026, 05:54 pm");
		write("rpt02-reference.pdf", pdf);
		preview("rpt02-reference.png", print);
	}

	@Test
	void i_groupedPdfsShowGroupHeadersAndSubtotalsFromTheStoredRun() throws Exception {
		StudentPaymentRun run = StudentPaymentSampleReports.mixed();
		for (GroupBy by : List.of(GroupBy.STUDENT, GroupBy.STUDENT_COURSE, GroupBy.COURSE)) {
			Grouped grouped = grouped(run, by);
			JasperPrint print = renderer.fill(run, grouped);
			String printed = printedText(print);
			for (Group group : grouped.groups().values()) {
				assertThat(printed).as(by.name()).contains(StudentPaymentPdfRenderer.amount(group.coursePayment()));
				assertThat(printed).contains(group.label());
			}
			assertThat(printed).contains("POSTAGE", "Refund – 2026-10", "Cash (manual)", "Bank transfer (manual)");
			String name = switch (by) {
				case STUDENT -> "rpt02-group-by-student";
				case STUDENT_COURSE -> "rpt02-group-by-student-course";
				default -> "rpt02-group-by-course";
			};
			write(name + ".pdf", renderer.render(run, grouped));
			preview(name + ".png", print);
		}
		assertThat(printedText(renderer.fill(run, grouped(run, GroupBy.STUDENT_COURSE)))).contains("Student subtotal",
				"Course subtotal", "Kasun Perera   ·   Index 0084");
	}

	@Test
	void k_refundAdjustmentAndPostageSample() throws Exception {
		StudentPaymentRun run = StudentPaymentSampleReports.refundAdjustmentPostage();
		Summary summary = run.report().sections().get(0).summary();
		assertThat(summary.refundCount()).isEqualTo(1);
		assertThat(run.report().reconciliation().byCurrency().get(0).staffAdjustments().count()).isEqualTo(1);
		JasperPrint print = renderer.fill(run, grouped(run, GroupBy.STUDENT));
		assertThat(printedText(print)).contains("POSTAGE", "Postage collected on top of these amounts",
				"Refunds included (net)", "Staff adjustments on manual receipts", "Months: Oct 2026");
		write("rpt02-refund-adjustment-postage.pdf", renderer.render(run, grouped(run, GroupBy.STUDENT)));
		preview("rpt02-refund-adjustment-postage.png", print);
	}

	@Test
	void n_manyRowsPdfContainsEveryRowOnSeveralPagesAndOnlyTheRunsFilters() throws Exception {
		StudentPaymentRun run = StudentPaymentSampleReports.manyRows();
		assertThat(run.rows()).hasSizeGreaterThan(200);
		JasperPrint print = renderer.fill(run, grouped(run, GroupBy.NONE));
		assertThat(print.getPages().size()).isGreaterThanOrEqualTo(3);
		String printed = printedText(print);
		assertThat(printed).contains("Courses: All courses", "Dates: 01 Oct 2026 to 31 Oct 2026");
		String pageTwo = printedPageText(print, 1);
		assertThat(pageTwo).contains("Student Payment Report", "Courses: All courses",
				"Dates: 01 Oct 2026 to 31 Oct 2026", "Generated: 22-10-2026 15:30:00 (Asia/Colombo)");
		for (StudentPaymentRow row : run.rows()) {
			assertThat(printed).contains(row.indexNumber());
		}
		assertThat(printed).contains(Integer.toString(run.rows().size()), "Courses: All courses",
				"Dates: 01 Oct 2026 to 31 Oct 2026", "Page " + print.getPages().size() + " of");
		byte[] pdf = renderer.render(run, grouped(run, GroupBy.NONE));
		assertThat(pdfText(pdf)).contains(StudentPaymentPdfRenderer.money("LKR",
				run.report().sections().get(0).summary().coursePaymentTotal()));
		write("rpt02-many-rows.pdf", pdf);
		write("rpt02-many-rows-group-by-student-course.pdf",
				renderer.render(run, grouped(run, GroupBy.STUDENT_COURSE)));
	}

	@Test
	void sinhalaTamilNamesRenderWithEmbeddedNotoFonts() throws Exception {
		StudentPaymentRun run = StudentPaymentSampleReports.sinhalaTamil();
		JasperPrint print = renderer.fill(run, grouped(run, GroupBy.STUDENT));
		assertThat(printedText(print)).contains("කමලා පෙරේරා (Kamala Perera)", "செல்வி ராஜேஸ்வரி",
				"இயற்பியல் - Physics", "10000", "Card (manual)");
		byte[] pdf = renderer.render(run, grouped(run, GroupBy.STUDENT));
		String raw = new String(pdf, StandardCharsets.ISO_8859_1);
		assertThat(raw).contains("NotoSans").contains("NotoSansSinhala").contains("NotoSansTamil");
		write("rpt02-sinhala-tamil.pdf", pdf);
		preview("rpt02-sinhala-tamil.png", print);
	}

	@Test
	void emptyResultRendersTheNoPaymentsMessage() throws Exception {
		StudentPaymentRun run = StudentPaymentSampleReports.empty();
		assertThat(run.rows()).isEmpty();
		JasperPrint print = renderer.fill(run, grouped(run, GroupBy.NONE));
		assertThat(printedText(print)).contains("No payments match these filters", "Payment method: Manual receipt",
				"Courses: Combined Mathematics");
		write("rpt02-empty.pdf", renderer.render(run, grouped(run, GroupBy.NONE)));
	}

	@Test
	void jrxmlAlsoCompilesAtRuntime() throws Exception {
		try (InputStream in = getClass().getClassLoader()
			.getResourceAsStream(ReportTemplates.STUDENT_PAYMENTS + ".jrxml")) {
			assertThat(JasperCompileManager.compileReport(in)).isNotNull();
		}
	}

	// ------------------------------------------------------------------

	private static String printedPageText(JasperPrint print, int pageIndex) {
		List<String> texts = new java.util.ArrayList<>();
		collect(print.getPages().get(pageIndex).getElements(), texts);
		return String.join("\n", texts);
	}

	private static void collect(List<JRPrintElement> elements, List<String> texts) {
		for (JRPrintElement element : elements) {
			if (element instanceof JRPrintText text && text.getFullText() != null) {
				texts.add(text.getFullText());
			}
			else if (element instanceof JRPrintFrame frame) {
				collect(frame.getElements(), texts);
			}
		}
	}

	private static void preview(String name, JasperPrint print) throws Exception {
		Files.createDirectories(SAMPLES);
		java.awt.image.BufferedImage image = (java.awt.image.BufferedImage) JasperPrintManager
			.getInstance(DefaultJasperReportsContext.getInstance())
			.printToImage(print, 0, 1.5f);
		javax.imageio.ImageIO.write(image, "png", SAMPLES.resolve(name).toFile());
	}

	private static void write(String name, byte[] pdf) throws IOException {
		Files.createDirectories(SAMPLES);
		Files.write(SAMPLES.resolve(name), pdf);
	}

}

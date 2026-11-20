package com.lms.reportinganalytics.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lms.common.error.NotFoundException;
import com.lms.reportinganalytics.service.ReportValidationException;
import com.lms.reportinganalytics.service.TeacherIncomeReport;
import com.lms.reportinganalytics.service.TeacherIncomeReport.CourseRow;
import com.lms.reportinganalytics.service.TeacherIncomeReport.Section;
import com.lms.reportinganalytics.service.TeacherIncomeReport.TeacherBlock;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintPage;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperPrintManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * RPT-01 PDF rendering (no database). All datasets come from {@link
 * SampleReports}, i.e. from {@code TeacherIncomeCalculator}, so the rendered
 * KPIs, teacher totals and rows must reconcile; the samples (and PNG previews)
 * are written to {@code target/report-samples/} for visual QA.
 */
class TeacherIncomePdfRendererTest {

	private static final Path SAMPLES = Path.of("target", "report-samples");

	private static TeacherIncomePdfRenderer renderer;

	@BeforeAll
	static void loadTemplates() {
		renderer = new TeacherIncomePdfRenderer(new ReportTemplates());
	}

	@Test
	void datasetReconcilesAndThePdfShowsExactlyItsTotals() throws Exception {
		TeacherIncomeReport report = SampleReports.mixed();
		for (Section section : report.sections()) {
			BigDecimal teacherRevenue = BigDecimal.ZERO;
			BigDecimal teacherShare = BigDecimal.ZERO;
			BigDecimal teacherCommission = BigDecimal.ZERO;
			for (TeacherBlock teacher : section.teachers()) {
				BigDecimal rowRevenue = BigDecimal.ZERO;
				BigDecimal rowShare = BigDecimal.ZERO;
				BigDecimal rowCommission = BigDecimal.ZERO;
				for (CourseRow row : teacher.courses()) {
					rowRevenue = rowRevenue.add(new BigDecimal(row.revenue()));
					rowShare = rowShare.add(dec(row.teacherIncome()));
					rowCommission = rowCommission.add(dec(row.commission()));
				}
				assertThat(rowRevenue).isEqualByComparingTo(teacher.totals().revenue());
				assertThat(rowShare).isEqualByComparingTo(teacher.totals().teacherIncome());
				assertThat(rowCommission).isEqualByComparingTo(teacher.totals().commission());
				assertThat(new BigDecimal(teacher.totals().revenue())).isEqualByComparingTo(
						dec(teacher.totals().teacherIncome()).add(dec(teacher.totals().commission()))
							.add(dec(teacher.totals().unratedRevenue())));
				assertThat(new BigDecimal(teacher.totals().balance())).isEqualByComparingTo(
						dec(teacher.totals().teacherIncome()).subtract(dec(teacher.totals().payouts())));
				teacherRevenue = teacherRevenue.add(new BigDecimal(teacher.totals().revenue()));
				teacherShare = teacherShare.add(new BigDecimal(teacher.totals().teacherIncome()));
				teacherCommission = teacherCommission.add(new BigDecimal(teacher.totals().commission()));
			}
			assertThat(teacherRevenue).isEqualByComparingTo(section.totals().revenue());
			assertThat(teacherShare).isEqualByComparingTo(section.totals().teacherIncome());
			assertThat(teacherCommission).isEqualByComparingTo(section.totals().commission());
		}

		String printed = printedText(renderer.fillSummary(report));
		byte[] pdf = renderer.renderSummary(report);
		String extracted = pdfText(pdf);
		for (Section section : report.sections()) {
			String c = section.currency();
			assertThat(printed).contains("Currency: " + c);
			List<String> amounts = new ArrayList<>(List.of(money(section.totals().revenue(), c),
					money(section.totals().teacherIncome(), c), money(section.totals().commission(), c),
					money(section.totals().serviceFees(), c)));
			for (TeacherBlock teacher : section.teachers()) {
				amounts.add(money(teacher.totals().teacherIncome(), c));
				amounts.add(money(teacher.totals().payouts(), c));
				amounts.add(money(teacher.totals().balance(), c));
				for (CourseRow row : teacher.courses()) {
					amounts.add(money(row.revenue(), c));
				}
			}
			assertThat(printed).contains(amounts);
			assertThat(extracted).contains(money(section.totals().revenue(), c),
					money(section.totals().teacherIncome(), c), money(section.totals().commission(), c));
		}
		assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
	}

	@Test
	void unratedRefundOnlyPayoutsAndSecondCurrencyRenderAsSpecified() {
		TeacherIncomeReport report = SampleReports.mixed();
		Section lkr = section(report, "LKR");
		TeacherBlock t1 = teacher(lkr, SampleReports.T1);
		TeacherBlock t2 = teacher(lkr, SampleReports.T2);
		TeacherBlock t3 = teacher(lkr, SampleReports.T3);
		CourseRow refundOnly = t1.courses().stream().filter(r -> r.courseName().startsWith("Accounting")).findFirst()
			.orElseThrow();
		assertThat(refundOnly.revenue()).isEqualTo("-500.00");
		assertThat(refundOnly.paymentCount()).isZero();
		CourseRow partial = t2.courses().get(0);
		assertThat(partial.rateMissing()).isTrue();
		assertThat(partial.teacherIncome()).isEqualTo("1200.00");
		CourseRow unrated = t3.courses().get(0);
		assertThat(unrated.teacherIncome()).isNull();
		assertThat(unrated.commission()).isNull();
		assertThat(t1.totals().payouts()).isEqualTo("5010.50");
		assertThat(report.sections()).extracting(Section::currency).containsExactly("LKR", "USD");
		assertThat(report.reconciliation().notes()).anyMatch(n -> n.startsWith("USD: ") && n.contains("payouts"));

		String printed = printedText(renderer.fillSummary(report));
		assertThat(printed).contains("Biology *", "இயற்பியல் - Physics *", "Rs. -500.00", "Rs. 5,010.50",
				"USD 100.00", "Not tracked", "Generated: 22-10-2026 15:30:00 (Asia/Colombo)", "Page 1 of");
		assertThat(printed.lines().filter("—"::equals).count()).isEqualTo(2);
		assertThat(printed).doesNotContain("No share rate");
	}

	@Test
	void payslipTotalIsTheTeachersShareAndLinesSumToIt() {
		TeacherIncomeReport report = SampleReports.mixed();
		TeacherBlock t1 = teacher(section(report, "LKR"), SampleReports.T1);
		BigDecimal rows = t1.courses().stream().map(r -> dec(r.teacherIncome())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
		assertThat(rows).isEqualByComparingTo(t1.totals().teacherIncome());
		String text = printedText(renderer.fillPayslip(report, SampleReports.T1));
		assertThat(text).contains("PAYSLIP", "කමලා පෙරේරා (Kamala Perera)", "Amounts in LKR", "Amounts in USD",
				"Total income", money(t1.totals().teacherIncome(), "LKR"), "2 payments", "0 payments", "Received by",
				"22-10-2026 15:30:00");
		String unrated = printedText(renderer.fillPayslip(report, SampleReports.T3));
		assertThat(unrated).contains("Biology *", "—", "* Part of this course has no teacher share rate");
	}

	@Test
	void payslipForATeacherOutsideTheRunIs404() {
		assertThatThrownBy(() -> renderer.fillPayslip(SampleReports.mixed(), UUID.randomUUID()))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void summaryLargerThanTheRowBoundIsRejected() {
		TeacherIncomeReport sample = SampleReports.mixed();
		Section section = sample.sections().get(0);
		TeacherBlock teacher = section.teachers().get(0);
		List<CourseRow> rows = new ArrayList<>();
		for (int i = 0; i <= TeacherIncomePdfRenderer.MAX_PDF_ROWS; i++) {
			rows.add(teacher.courses().get(0));
		}
		TeacherIncomeReport big = new TeacherIncomeReport(sample.runId(), sample.expiresAt(), sample.generatedAt(),
				sample.timezone(), sample.institute(), sample.filters(),
				List.of(new Section(section.currency(), section.totals(),
						List.of(new TeacherBlock(teacher.teacherId(), teacher.teacherName(), teacher.totals(), rows)))),
				sample.reconciliation());
		assertThatThrownBy(() -> renderer.fillSummary(big)).isInstanceOf(ReportValidationException.class);
	}

	@Test
	void jrxmlTemplatesAlsoCompileAtRuntimeForTheExplodedClasspathFallback() throws Exception {
		for (String base : List.of(ReportTemplates.SUMMARY, ReportTemplates.PAYSLIP)) {
			try (InputStream in = getClass().getClassLoader().getResourceAsStream(base + ".jrxml")) {
				assertThat(JasperCompileManager.compileReport(in)).isNotNull();
			}
		}
	}

	@Test
	void writesReconciledSamplesWithEmbeddedNotoFonts() throws Exception {
		TeacherIncomeReport mixed = SampleReports.mixed();
		byte[] summary = renderer.renderSummary(mixed);
		String raw = new String(summary, StandardCharsets.ISO_8859_1);
		assertThat(raw).contains("NotoSans").contains("NotoSansSinhala").contains("NotoSansTamil");
		write("summary-mixed-sinhala-tamil-usd.pdf", summary);
		write("payslip-sinhala.pdf", renderer.renderPayslip(mixed, SampleReports.T1));
		write("payslip-tamil.pdf", renderer.renderPayslip(mixed, SampleReports.T2));
		write("payslip-unrated.pdf", renderer.renderPayslip(mixed, SampleReports.T3));
		preview("summary-mixed-sinhala-tamil-usd.png", renderer.fillSummary(mixed));
		preview("payslip-sinhala.png", renderer.fillPayslip(mixed, SampleReports.T1));

		TeacherIncomeReport many = SampleReports.manyPages();
		JasperPrint print = renderer.fillSummary(many);
		assertThat(print.getPages().size()).isGreaterThan(3);
		assertThat(pdfText(renderer.renderSummary(many))).contains(money(many.sections().get(0).totals().revenue(),
				"LKR"));
		write("summary-many-pages.pdf", renderer.renderSummary(many));
	}

	// ------------------------------------------------------------------

	private static BigDecimal dec(String amount) {
		return amount == null ? BigDecimal.ZERO : new BigDecimal(amount);
	}

	/** The template's format: {@code #,##0.00}, "Rs." for LKR else the ISO code. */
	static String money(String amount, String currency) {
		DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
		String prefix = "LKR".equals(currency) ? "Rs." : currency;
		return prefix + " " + format.format(new BigDecimal(Objects.requireNonNull(amount)));
	}

	private static Section section(TeacherIncomeReport report, String currency) {
		return report.sections().stream().filter(s -> s.currency().equals(currency)).findFirst().orElseThrow();
	}

	private static TeacherBlock teacher(Section section, UUID id) {
		return section.teachers().stream().filter(t -> t.teacherId().equals(id)).findFirst().orElseThrow();
	}

	static String printedText(JasperPrint print) {
		List<String> texts = new ArrayList<>();
		for (JRPrintPage page : print.getPages()) {
			collect(page.getElements(), texts);
		}
		return texts.stream().collect(Collectors.joining("\n"));
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

	static String pdfText(byte[] pdf) throws IOException {
		PdfReader reader = new PdfReader(pdf);
		try {
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			StringBuilder text = new StringBuilder();
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page)).append('\n');
			}
			return text.toString();
		}
		finally {
			reader.close();
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

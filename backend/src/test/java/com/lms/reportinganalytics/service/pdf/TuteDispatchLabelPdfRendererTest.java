package com.lms.reportinganalytics.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchLabel;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperPrint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** TUTE-01 label sheet: A4 2 x 3 (7 labels = 2 pages), never split, Noto fonts, selectable text. */
class TuteDispatchLabelPdfRendererTest {

	private final TuteDispatchLabelPdfRenderer renderer = new TuteDispatchLabelPdfRenderer(new ReportTemplates());

	private static List<DispatchLabel> sevenLabels() {
		List<DispatchLabel> labels = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			labels.add(new DispatchLabel("Synthetic Student " + i, List.of(i + " Test Road", "Test City"), false,
					"070000000" + i, List.of("Course " + i + " – Tute " + i), 1));
		}
		labels.add(new DispatchLabel("Missing Address Student", List.of(), true, null,
				List.of("Course X – Tute A", "Course X – Tute B"), 2));
		labels.add(new DispatchLabel("සිංහල ශිෂ්‍යයා with a very long synthetic name that must wrap or shrink safely",
				List.of("No. 1, A very long synthetic address line that keeps going and going", "தமிழ் வீதி",
						"Test City 00100"),
				false, "0700000099",
				List.of("Course A – Tute 1", "Course A – Tute 2", "Course B – Tute 3",
						"Course B – Tute 4", "Course C – Tute 5", "Course C – Tute 6"),
				6));
		return labels;
	}

	@Test
	void sevenLabelsFillTwoA4PagesWithEveryLabelOnOnePage() throws Exception {
		JasperPrint print = renderer.fill("Synthetic Institute", sevenLabels());
		assertThat(print.getPages()).hasSize(2);
		assertThat(print.getPageWidth()).isEqualTo(595);
		assertThat(print.getPageHeight()).isEqualTo(842);
		String first = pageText(print, 0);
		String second = pageText(print, 1);
		assertThat(first).contains("Synthetic Student 1", "Synthetic Student 5", "Missing Address Student",
				"No address provided", "Tel: 0700000001", "From: Synthetic Institute", "1 tute", "2 tutes");
		assertThat(second).contains("සිංහල ශිෂ්‍යයා", "தமிழ் வீதி", "6 tutes", "Course C – Tute 6");

		byte[] pdf = renderer.render("Synthetic Institute", sevenLabels());
		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isEqualTo(2);
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("Synthetic Student 1", "No address provided", "From: Synthetic Institute",
					"Tel: 0700000003");
		}
		String raw = new String(pdf, StandardCharsets.ISO_8859_1);
		assertThat(raw).contains("NotoSans").contains("NotoSansSinhala").contains("NotoSansTamil");
	}

	@Test
	void jrxmlAlsoCompilesAtRuntime() throws Exception {
		try (InputStream in = getClass().getClassLoader()
			.getResourceAsStream(ReportTemplates.TUTE_DISPATCH_LABELS + ".jrxml")) {
			assertThat(JasperCompileManager.compileReport(in)).isNotNull();
		}
	}

	private static String pageText(JasperPrint print, int pageIndex) {
		List<String> texts = new ArrayList<>();
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

}

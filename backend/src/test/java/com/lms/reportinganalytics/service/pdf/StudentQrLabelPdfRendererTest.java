package com.lms.reportinganalytics.service.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.lms.usermanagement.api.StudentQrLabelApi.StudentQrLabel;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/**
 * STU-MGMT-02: the printed QR label sheet is decoded with a real QR decoder after
 * rasterising the exported PDF (150 dpi, roughly a cheap office scan). Every
 * selected payload must decode exactly once - none clipped or split across pages.
 */
class StudentQrLabelPdfRendererTest {

	private final StudentQrLabelPdfRenderer renderer = new StudentQrLabelPdfRenderer(new ReportTemplates());

	@Test
	void everyLabelOnEveryPageDecodesToItsOpaquePayload() throws Exception {
		List<StudentQrLabel> labels = new ArrayList<>();
		Set<String> expected = new HashSet<>();
		for (int i = 1; i <= 25; i++) { // > one page of labels
			String payload = "LMS-STU:1:" + (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "")
				.substring(0, 48);
			expected.add(payload);
			labels.add(new StudentQrLabel(UUID.randomUUID(), String.format("%04d", i),
					i == 7 ? "සිංහල Student with a very long name that should wrap safely" : "Student " + i, payload));
		}

		byte[] pdf = renderer.render("Synthetic Institute", labels);

		List<String> decoded = new ArrayList<>();
		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(2);
			PDFRenderer pages = new PDFRenderer(document);
			for (int page = 0; page < document.getNumberOfPages(); page++) {
				BufferedImage image = pages.renderImageWithDPI(page, 150, ImageType.GRAY);
				Result[] results = new QRCodeMultiReader().decodeMultiple(
						new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))),
						Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE));
				for (Result result : results) {
					decoded.add(result.getText());
				}
			}
		}
		assertThat(decoded).hasSize(expected.size());
		assertThat(new HashSet<>(decoded)).isEqualTo(expected);
		assertThat(decoded).allMatch(text -> text.matches("LMS-STU:1:[0-9a-f]{48}"));
	}

}

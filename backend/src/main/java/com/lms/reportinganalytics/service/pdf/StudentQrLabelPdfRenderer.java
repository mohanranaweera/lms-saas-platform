package com.lms.reportinganalytics.service.pdf;

import com.lms.common.qr.QrCodes;
import com.lms.usermanagement.api.StudentQrLabelApi.StudentQrLabel;
import java.awt.Image;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

/**
 * STU-MGMT-02 student QR label sheet (spec section 7) on the shared JasperReports stack:
 * A4 portrait, 3 x 7 labels of 60 mm, each with a 32 mm QR (error correction M, 4-module
 * quiet zone) plus tenant name, full name and index number. Labels never split.
 */
@Component
public class StudentQrLabelPdfRenderer {

	/** 8 px per module keeps the code sharp at 32 mm (about 230 dpi for a version 5 code). */
	private static final int PIXELS_PER_MODULE = 8;

	private final ReportTemplates templates;

	public StudentQrLabelPdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	/** One printed label (Jasper reads the getters). */
	public static final class LabelRow {

		private final String tenantName;

		private final String fullName;

		private final String indexNumber;

		private final Image qrImage;

		LabelRow(String tenantName, String fullName, String indexNumber, Image qrImage) {
			this.tenantName = tenantName;
			this.fullName = fullName;
			this.indexNumber = indexNumber;
			this.qrImage = qrImage;
		}

		public String getTenantName() {
			return tenantName;
		}

		public String getFullName() {
			return fullName;
		}

		public String getIndexNumber() {
			return indexNumber;
		}

		public Image getQrImage() {
			return qrImage;
		}

	}

	public byte[] render(String tenantName, List<StudentQrLabel> labels) {
		return JasperPdfSupport.export(fill(tenantName, labels), "Student QR Labels");
	}

	JasperPrint fill(String tenantName, List<StudentQrLabel> labels) {
		String institute = tenantName == null ? "" : tenantName;
		List<LabelRow> rows = labels.stream()
			.map(label -> new LabelRow(institute, label.fullName(), label.indexNumber(),
					QrCodes.image(label.qrPayload(), PIXELS_PER_MODULE)))
			.toList();
		Map<String, Object> params = new HashMap<>();
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.studentQrLabels(), params, rows);
	}

}

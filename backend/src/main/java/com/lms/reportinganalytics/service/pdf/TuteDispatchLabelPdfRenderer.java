package com.lms.reportinganalytics.service.pdf;

import com.lms.tutemanagement.api.TuteDispatchLabelApi.DispatchLabel;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperPrint;
import org.springframework.stereotype.Component;

/**
 * TUTE-01 Tute Dispatch label sheet on the shared JasperReports stack: A4 portrait, 2 x 3
 * labels with dashed cut guides; never splits a label across pages. Display text only - the
 * labels come from {@code TuteDispatchLabelApi}, which already authorized and revalidated them.
 */
@Component
public class TuteDispatchLabelPdfRenderer {

	private final ReportTemplates templates;

	public TuteDispatchLabelPdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	/** One printed label (Jasper reads the getters). */
	public static final class LabelRow {

		private final String recipientName;

		private final String addressText;

		private final Boolean addressMissing;

		private final String phoneText;

		private final String tutesText;

		private final String countText;

		LabelRow(DispatchLabel label) {
			this.recipientName = label.recipientName() == null ? "" : label.recipientName();
			this.addressText = String.join("\n", label.addressLines());
			this.addressMissing = label.addressMissing();
			this.phoneText = label.phone() == null || label.phone().isBlank() ? "" : "Tel: " + label.phone();
			this.tutesText = String.join("\n", label.tuteLines());
			this.countText = label.tuteCount() == 1 ? "1 tute" : label.tuteCount() + " tutes";
		}

		public String getRecipientName() {
			return recipientName;
		}

		public String getAddressText() {
			return addressText;
		}

		public Boolean getAddressMissing() {
			return addressMissing;
		}

		public String getPhoneText() {
			return phoneText;
		}

		public String getTutesText() {
			return tutesText;
		}

		public String getCountText() {
			return countText;
		}

	}

	public byte[] render(String instituteName, List<DispatchLabel> labels) {
		return JasperPdfSupport.export(fill(instituteName, labels), "Tute Dispatch Labels");
	}

	JasperPrint fill(String instituteName, List<DispatchLabel> labels) {
		Map<String, Object> params = new HashMap<>();
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		params.put("INSTITUTE", instituteName == null ? "" : instituteName);
		return JasperPdfSupport.fill(templates.tuteDispatchLabels(), params, labels.stream().map(LabelRow::new).toList());
	}

}

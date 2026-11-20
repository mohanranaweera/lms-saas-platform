package com.lms.reportinganalytics.service.pdf;

import com.lms.reportinganalytics.service.StudentPaymentGrouping.GroupBy;
import com.lms.reportinganalytics.service.StudentPaymentGrouping.Grouped;
import com.lms.reportinganalytics.service.StudentPaymentReport;
import com.lms.reportinganalytics.service.StudentPaymentReport.CourseRef;
import com.lms.reportinganalytics.service.StudentPaymentReport.CurrencyReconciliation;
import com.lms.reportinganalytics.service.StudentPaymentReport.MethodTotal;
import com.lms.reportinganalytics.service.StudentPaymentReport.Section;
import com.lms.reportinganalytics.service.StudentPaymentReport.Summary;
import com.lms.reportinganalytics.service.StudentPaymentRow;
import com.lms.reportinganalytics.service.StudentPaymentRun;
import com.lms.reportinganalytics.service.StudentPaymentGrouping;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * RPT-02 PDF rendering (landscape A4). Input is ONLY a stored {@link
 * StudentPaymentRun} already put in grouped order by {@link
 * StudentPaymentGrouping}: this class flattens it into display-string beans
 * (KPIs, summary lines, group headers and subtotals, detail cells) and fills
 * the shared precompiled template. It computes no financial figure - every
 * amount is the stored decimal string, only formatted - and never touches the
 * database. The row-count bound is enforced by the caller.
 */
@Component
public class StudentPaymentPdfRenderer {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a",
			Locale.ENGLISH);

	private final ReportTemplates templates;

	public StudentPaymentPdfRenderer(ReportTemplates templates) {
		this.templates = templates;
	}

	public byte[] render(StudentPaymentRun run, Grouped grouped) {
		return JasperPdfSupport.export(fill(run, grouped), "Student Payment Report");
	}

	public JasperPrint fill(StudentPaymentRun run, Grouped grouped) {
		StudentPaymentReport report = run.report();
		ZoneId zone = ZoneId.of(report.timezone());
		Map<String, StudentPaymentPdfRow.Section> sections = new HashMap<>();
		for (Section section : report.sections()) {
			sections.put(section.currency(), section(section, reconciliation(report, section.currency())));
		}
		boolean hasPostage = run.rows().stream().anyMatch(row -> new BigDecimal(row.postage()).signum() != 0);
		List<StudentPaymentPdfRow> beans = new ArrayList<>(grouped.rows().size());
		int rowNo = 0;
		for (StudentPaymentRow row : grouped.rows()) {
			rowNo++;
			StudentPaymentPdfRow.Section section = sections.computeIfAbsent(row.currency(),
					currency -> new StudentPaymentPdfRow.Section(currency, "Currency: " + currency, "", "", "", "",
							"AMOUNT (" + currency + ")"));
			List<StudentPaymentGrouping.Group> groups = grouped.groupsOf(row);
			StudentPaymentPdfRow.Group g1 = groups.isEmpty() ? hidden(section.key())
					: group(groups.get(0), grouped.groupBy(), 0);
			StudentPaymentPdfRow.Group g2 = groups.size() < 2 ? hidden(g1.key())
					: group(groups.get(1), grouped.groupBy(), 1);
			beans.add(new StudentPaymentPdfRow(section, g1, g2, cells(rowNo, row, zone)));
		}

		Map<String, Object> params = new HashMap<>();
		String institute = report.institute() == null || report.institute().name() == null ? ""
				: report.institute().name();
		String generated = JasperPdfSupport.TIMESTAMP.format(report.generatedAt().atZone(zone));
		params.put("INSTITUTE", institute);
		params.put("FILTER_COURSES", coursesLine(report));
		params.put("FILTER_LINE", filterLine(report, grouped.groupBy()));
		params.put("GENERATED_LINE", "Generated: " + generated + " (" + report.timezone() + ")");
		params.put("FOOTER_TEXT", "Student Payment Report generated on " + generated + " | " + institute);
		params.put("NOTES", String.join("\n", report.reconciliation().notes()));
		params.put("EMPTY", beans.isEmpty());
		params.put("HAS_POSTAGE", hasPostage);
		params.put(JRParameter.REPORT_LOCALE, Locale.US);
		return JasperPdfSupport.fill(templates.studentPayments(), params, beans);
	}

	// ------------------------------------------------------------------

	private static CurrencyReconciliation reconciliation(StudentPaymentReport report, String currency) {
		return report.reconciliation()
			.byCurrency()
			.stream()
			.filter(r -> r.currency().equals(currency))
			.findFirst()
			.orElse(null);
	}

	private static StudentPaymentPdfRow.Section section(Section section, CurrencyReconciliation recon) {
		String currency = section.currency();
		Summary s = section.summary();
		List<String> lines = new ArrayList<>();
		lines.add("Service fees collected on top of these amounts: " + money(currency, s.serviceFeeTotal()));
		if (new BigDecimal(s.postageTotal()).signum() != 0) {
			lines.add("Postage collected on top of these amounts: " + money(currency, s.postageTotal()));
		}
		if (s.refundCount() > 0 && recon != null) {
			lines.add("Refunds included (net): " + money(currency, new BigDecimal(recon.refunds()).negate().toPlainString())
					+ " (" + count(s.refundCount(), "refund", "refunds") + ")");
		}
		if (recon != null && recon.staffAdjustments().count() > 0) {
			lines.add("Staff adjustments on manual receipts (not received): "
					+ money(currency, recon.staffAdjustments().amount()) + " ("
					+ count(recon.staffAdjustments().count(), "receipt", "receipts") + ")");
		}
		lines.add("Received (net) total incl. service fees and postage: " + money(currency, s.receivedTotal()));
		List<String> methods = new ArrayList<>();
		for (MethodTotal method : s.byMethod()) {
			methods.add(methodLabel(method.method(), method.manual()) + " " + money(currency, method.total()) + " ("
					+ count(method.receiptCount(), "payment", "payments") + ")");
		}
		if (!methods.isEmpty()) {
			lines.add("By payment method: " + String.join("  ·  ", methods));
		}
		return new StudentPaymentPdfRow.Section(currency, "Currency: " + currency,
				money(currency, s.coursePaymentTotal()), number(s.studentsPaid()), number(s.receiptCount()),
				String.join("\n", lines), "AMOUNT (" + currency + ")");
	}

	private static StudentPaymentPdfRow.Group hidden(String key) {
		return new StudentPaymentPdfRow.Group(key, false, null, null, null, null, null);
	}

	private static StudentPaymentPdfRow.Group group(StudentPaymentGrouping.Group group, GroupBy groupBy, int depth) {
		boolean student = StudentPaymentGrouping.STUDENT.equals(group.level());
		String title = student
				? group.label() + (group.indexNumber() == null || group.indexNumber().isEmpty() ? ""
						: "   ·   Index " + group.indexNumber())
				: "Course: " + group.label();
		String prefix = groupBy == GroupBy.STUDENT_COURSE ? (depth == 0 ? "Student subtotal" : "Course subtotal")
				: "Subtotal";
		String label = prefix + " – " + group.label() + " (" + count(group.receiptCount(), "payment", "payments")
				+ ")";
		String key = group.currency() + "|" + group.level() + "|" + group.parentKey() + "|" + group.key();
		return new StudentPaymentPdfRow.Group(key, true, title, label, amount(group.coursePayment()),
				amount(group.serviceFee()), amount(group.postage()));
	}

	private static StudentPaymentPdfRow.Cells cells(int rowNo, StudentPaymentRow row, ZoneId zone) {
		String date = row.dateOnly() ? DATE.format(LocalDate.parse(row.paymentDate()))
				: DATE_TIME.format(Instant.parse(row.paymentDate()).atZone(zone)).replace("AM", "am").replace("PM",
						"pm");
		String reason = row.refund() ? "Refund – " + row.reason() : row.reason();
		return new StudentPaymentPdfRow.Cells(Integer.toString(rowNo), row.indexNumber(), row.studentName(),
				row.courseName(), reason, methodLabel(row.method(), row.manual()), date, amount(row.amount()),
				amount(row.serviceFee()), amount(row.postage()));
	}

	static String coursesLine(StudentPaymentReport report) {
		List<CourseRef> courses = report.filters().courses();
		if (courses == null || courses.isEmpty()) {
			return "Courses: All courses";
		}
		return "Courses: " + String.join(", ", courses.stream().map(CourseRef::name).toList());
	}

	static String filterLine(StudentPaymentReport report, GroupBy groupBy) {
		var filters = report.filters();
		String period = ("MONTHS".equals(filters.periodMode()) ? "Months: " : "Dates: ") + filters.periodLabel();
		return period + "   |   Payment method: " + filterMethodLabel(filters.paymentMethod()) + "   |   Grouping: "
				+ groupingLabel(groupBy);
	}

	static String filterMethodLabel(String method) {
		if (method == null) {
			return "All";
		}
		return switch (method) {
			case "GATEWAY" -> "Online payment";
			case "MANUAL_SLIP" -> "Bank slip";
			case "MANUAL_RECEIPT" -> "Manual receipt";
			default -> method;
		};
	}

	static String groupingLabel(GroupBy groupBy) {
		return switch (groupBy) {
			case NONE -> "None";
			case STUDENT -> "By student";
			case COURSE -> "By course";
			case STUDENT_COURSE -> "By student, then course";
		};
	}

	/** Display label of a row / method total (spec 3-R.5). */
	static String methodLabel(String method, boolean manual) {
		if (manual) {
			return switch (method) {
				case "CASH" -> "Cash (manual)";
				case "BANK_TRANSFER" -> "Bank transfer (manual)";
				case "CARD" -> "Card (manual)";
				default -> "Manual (unknown)";
			};
		}
		return switch (method) {
			case "ONLINE" -> "Online (gateway)";
			case "BANK_TRANSFER" -> "Bank transfer (slip)";
			default -> "Unknown";
		};
	}

	private static String count(long n, String one, String many) {
		return number(n) + " " + (n == 1 ? one : many);
	}

	private static String number(long n) {
		return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).format(n);
	}

	/** Table cell amount: {@code #,##0.00} (the currency is in the column header). */
	static String amount(String value) {
		return new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.US))
			.format(new BigDecimal(value));
	}

	/** Amount with the currency prefix ("Rs." for LKR, else the ISO code), as RPT-01. */
	static String money(String currency, String value) {
		String prefix = "LKR".equals(currency) ? "Rs." : currency;
		return prefix + " " + amount(value);
	}

}

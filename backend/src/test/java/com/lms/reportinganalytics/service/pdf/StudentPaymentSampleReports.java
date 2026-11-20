package com.lms.reportinganalytics.service.pdf;

import static com.lms.reportinganalytics.service.StudentPaymentFixtures.dates;
import static com.lms.reportinganalytics.service.StudentPaymentFixtures.months;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.reportinganalytics.service.StudentPaymentCalculator;
import com.lms.reportinganalytics.service.StudentPaymentCriteria;
import com.lms.reportinganalytics.service.StudentPaymentFixtures;
import com.lms.reportinganalytics.service.StudentPaymentFixtures.Book;
import com.lms.reportinganalytics.service.StudentPaymentFixtures.Order;
import com.lms.reportinganalytics.service.StudentPaymentRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RPT-02 sample runs for the PDF tests and visual QA. Every run is PRODUCED by
 * {@code StudentPaymentCalculator} from synthetic ledger inputs (never
 * hand-built report DTOs), so KPIs, subtotals and rows reconcile exactly as in
 * production.
 */
final class StudentPaymentSampleReports {

	private StudentPaymentSampleReports() {
	}

	static StudentPaymentRun run(Book book, StudentPaymentCriteria criteria) {
		StudentPaymentCalculator.Result result = book.calculate(criteria);
		return new StudentPaymentRun(result.report()
			.withRun(UUID.randomUUID(), StudentPaymentFixtures.GENERATED.plusSeconds(900)), result.rows());
	}

	/** Spec section 1 reference: 5 students x 4,000 (+100 fee), 3 slips + 2 gateway, 08 Oct 2026. */
	static StudentPaymentRun reference() {
		Book book = new Book().institute("Kandy Science Academy");
		UUID course = book.course("2026 A/L Combined Mathematics - Theory");
		String[] names = { "Kasun Perera", "Dilini Jayasinghe", "Mohamed Rizwan", "Tharushi Fernando",
				"Nuwan Bandara" };
		String[] indexes = { "0084", "0223", "0031", "0410", "0007" };
		String[] times = { "2026-10-08T03:05:00Z", "2026-10-08T04:12:00Z", "2026-10-08T06:40:00Z",
				"2026-10-08T09:30:00Z", "2026-10-08T12:24:00Z" };
		for (int i = 0; i < 5; i++) {
			UUID student = book.student(names[i], indexes[i]);
			book.order(student).monthly(course, "2026-10", "4000.00", "100.00")
				.confirm(i < 3 ? PaymentMethod.MANUAL_SLIP : PaymentMethod.GATEWAY, times[i]);
		}
		return run(book, dates("2026-10-08", "2026-10-08", List.of(course), null));
	}

	/**
	 * Several students and courses, multi-course and multi-month receipts, a
	 * manual cash receipt with a staff adjustment, postage and a refund - for
	 * the grouped layouts.
	 */
	static Book mixedBook() {
		Book book = new Book().institute("Colombo Learning Centre (Pvt) Ltd");
		UUID maths = book.course("Combined Mathematics");
		UUID physics = book.course("Physics - Theory and Revision");
		UUID chem = book.course("Chemistry");
		UUID kasun = book.student("Kasun Perera", "0084");
		UUID dilini = book.student("Dilini Jayasinghe", "0223");
		UUID nimal = book.student("Nimal Silva", "0031");
		book.order(kasun).monthly(maths, "2026-10", "4000.00", "100.00").monthly(maths, "2026-11", "4000.00", "100.00")
			.monthly(physics, "2026-10", "3500.00", "100.00").confirm(PaymentMethod.GATEWAY, "2026-10-03T04:00:00Z");
		book.order(dilini).monthly(maths, "2026-10", "4000.00", "100.00", "400.00").confirm(PaymentMethod.MANUAL_SLIP,
				"2026-10-05T05:00:00Z");
		book.order(dilini).monthly(chem, "2026-10", "3000.00", "100.00").receipt("CASH", "2026-10-06",
				"2026-10-07T05:00:00Z", "310.00");
		Order post = book.order(nimal).monthly(physics, "2026-10", "3500.00", "100.00").postage(physics, "250.00");
		ReportLedgerEntry paid = post.confirm(PaymentMethod.GATEWAY, "2026-10-09T08:00:00Z");
		post.refund(paid, "-385.00", "2026-10-12T08:00:00Z");
		book.order(nimal).monthly(chem, "2026-10", "3000.00", "100.00").receipt("BANK_TRANSFER", "2026-10-10",
				"2026-10-10T09:00:00Z", "0.00");
		return book;
	}

	static StudentPaymentRun mixed() {
		return run(mixedBook(), dates("2026-10-01", "2026-10-31", List.of(), null));
	}

	/** Refund + staff adjustment + postage, MONTHS mode. */
	static StudentPaymentRun refundAdjustmentPostage() {
		return run(mixedBook(), months(List.of(), null, "2026-10"));
	}

	/** Sinhala and Tamil student / course / institute names. */
	static StudentPaymentRun sinhalaTamil() {
		Book book = new Book().institute("ශ්‍රී විද්‍යා Institute of Learning (Pvt) Ltd");
		UUID sinhala = book.course("සංයුක්ත ගණිතය 2026 - A/L Combined Mathematics (Theory)");
		UUID tamil = book.course("இயற்பியல் - Physics");
		UUID a = book.student("කමලා පෙරේරා (Kamala Perera)", "0001");
		UUID b = book.student("செல்வி ராஜேஸ்வரி", "0002");
		UUID c = book.student("Nimal Fernando", "10000");
		book.order(a).monthly(sinhala, "2026-10", "4000.00", "100.00").confirm(PaymentMethod.GATEWAY,
				"2026-10-08T04:00:00Z");
		book.order(b).monthly(tamil, "2026-10", "3000.00", "100.00").confirm(PaymentMethod.MANUAL_SLIP,
				"2026-10-08T05:00:00Z");
		book.order(c).monthly(tamil, "2026-10", "3000.00", "100.00").monthly(sinhala, "2026-10", "4000.00", "100.00")
			.receipt("CARD", "2026-10-08", "2026-10-08T06:00:00Z", "0.00");
		return run(book, dates("2026-10-08", "2026-10-08", List.of(), null));
	}

	/** 40 students x 3 courses x 2 months - many pages, long names. */
	static StudentPaymentRun manyRows() {
		Book book = new Book().institute("Advanced Level Mathematics and Science Education Institute");
		UUID[] courses = new UUID[3];
		for (int c = 0; c < 3; c++) {
			courses[c] = book.course("Course " + (c + 1) + " - Advanced Level Combined Mathematics Theory and "
					+ "Revision Class with a very long title that wraps");
		}
		for (int s = 0; s < 40; s++) {
			UUID student = book.student(String.format("Student %02d with a deliberately long display name to wrap", s),
					String.format("%04d", s + 1));
			for (int c = 0; c < 3; c++) {
				book.order(student).monthly(courses[c], "2026-09", "1234.50", "100.00")
					.monthly(courses[c], "2026-10", "1234.50", "100.00")
					.confirm(s % 3 == 0 ? PaymentMethod.MANUAL_SLIP : PaymentMethod.GATEWAY,
							Instant.parse("2026-10-01T04:00:00Z").plusSeconds(3600L * (s * 3 + c)).toString());
			}
		}
		return run(book, dates("2026-10-01", "2026-10-31", List.of(), null));
	}

	static StudentPaymentRun empty() {
		Book book = new Book();
		UUID course = book.course("Combined Mathematics");
		return run(book, dates("2026-10-08", "2026-10-08", List.of(course), PaymentMethod.MANUAL_RECEIPT));
	}

}

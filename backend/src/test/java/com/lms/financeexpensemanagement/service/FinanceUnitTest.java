package com.lms.financeexpensemanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.financeexpensemanagement.service.FinanceReportService.IncomeSummary;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueEntry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Wave 7 - pure unit coverage for the receipt sniffer and ledger income aggregation. */
class FinanceUnitTest {

	@Test
	void snifferAcceptsOnlyPdfPngJpegByMagicBytes() {
		assertThat(ExpenseReceiptSniffer.sniff("%PDF-1.7 body".getBytes(StandardCharsets.US_ASCII)))
			.isEqualTo("application/pdf");
		assertThat(ExpenseReceiptSniffer.sniff(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0 }))
			.isEqualTo("image/png");
		assertThat(ExpenseReceiptSniffer.sniff(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00 }))
			.isEqualTo("image/jpeg");
		assertThat(ExpenseReceiptSniffer.sniff("GIF89a....".getBytes(StandardCharsets.US_ASCII))).isNull();
		assertThat(ExpenseReceiptSniffer.sniff(new byte[] { 0x4D, 0x5A, (byte) 0x90, 0x00 })).isNull();
		assertThat(ExpenseReceiptSniffer.sniff("<html>".getBytes(StandardCharsets.US_ASCII))).isNull();
		assertThat(ExpenseReceiptSniffer.sniff(new byte[0])).isNull();
	}

	@Test
	void incomeSeparatesConfirmedAndRefundsAndCountsZeroAmountConfirmations() {
		List<LedgerRevenueEntry> entries = List.of(entry(false, "100.00"), entry(false, "0.00"),
				entry(false, "49.99"), entry(true, "-20.50"));

		IncomeSummary income = FinanceReportService.incomeOf(entries);

		assertThat(income.gross()).isEqualByComparingTo("149.99");
		assertThat(income.refunds()).isEqualByComparingTo("20.50");
		assertThat(income.net()).isEqualByComparingTo("129.49");
		assertThat(income.paymentCount()).isEqualTo(3);
		assertThat(income.refundCount()).isEqualTo(1);
		assertThat(FinanceReportService.incomeOf(List.of()).net()).isEqualByComparingTo("0.00");
	}

	private static LedgerRevenueEntry entry(boolean refund, String amount) {
		return new LedgerRevenueEntry(UUID.randomUUID(), UUID.randomUUID(), refund, new BigDecimal(amount),
				Instant.parse("2025-03-01T00:00:00Z"), UUID.randomUUID());
	}

}

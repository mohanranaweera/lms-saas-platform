package com.lms.financeexpensemanagement.service;

import com.lms.financeexpensemanagement.domain.FinanceEnums.SourceType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionStatus;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import com.lms.financeexpensemanagement.domain.FinanceTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * FIN-01: one account-book row as the API returns it. {@code reversibleHere}
 * tells the UI whether a Finance "reverse" is possible or the correction belongs
 * to the owning workflow ({@code correctionOwner}: Payments refund, wallet).
 */
public record FinanceTransactionView(UUID id, TransactionType type, TransactionStatus status, BigDecimal amount,
		String currency, UUID fromAccountId, String fromAccountName, UUID toAccountId, String toAccountName,
		UUID categoryId, String categoryName, LocalDate transactionDate, Instant postedAt, String note,
		SourceType sourceType, UUID sourceId, UUID reversesTransactionId, UUID reversedByTransactionId,
		BigDecimal balanceBefore, BigDecimal balanceAfter, String reason, UUID createdBy, String createdByEmail,
		Instant createdAt, Instant reversedAt, boolean reversibleHere, String correctionOwner) {

	static FinanceTransactionView of(FinanceTransaction t, Map<UUID, String> accountNames,
			Map<UUID, String> categoryNames, Map<UUID, String> emails, UUID reversedBy) {
		boolean reversible = t.getStatus() == TransactionStatus.POSTED && reversedBy == null
				&& (t.getSourceType() == SourceType.MANUAL || t.getSourceType() == SourceType.SCHEDULE
						|| t.getSourceType() == SourceType.EXPENSE)
				&& t.getType() != TransactionType.OPENING && t.getType() != TransactionType.REVERSAL;
		String owner = switch (t.getSourceType()) {
			case LEDGER_ENTRY -> "PAYMENTS";
			case WALLET_ENTRY -> "TEACHER_WALLET";
			case EXPENSE -> "EXPENSES";
			default -> null;
		};
		return new FinanceTransactionView(t.getId(), t.getType(), t.getStatus(), t.getAmount(), t.getCurrency(),
				t.getFromAccountId(), lookup(accountNames, t.getFromAccountId()), t.getToAccountId(),
				lookup(accountNames, t.getToAccountId()), t.getCategoryId(), lookup(categoryNames, t.getCategoryId()),
				t.getTransactionDate(), t.getPostedAt(), t.getNote(), t.getSourceType(), t.getSourceId(),
				t.getReversesTransactionId(), reversedBy, t.getBalanceBefore(), t.getBalanceAfter(), t.getReason(),
				t.getCreatedBy(), lookup(emails, t.getCreatedBy()), t.getCreatedAt(), t.getReversedAt(), reversible,
				owner);
	}

	/** Null-key safe (immutable maps throw on {@code get(null)}). */
	private static String lookup(Map<UUID, String> map, UUID key) {
		return key == null || map == null ? null : map.get(key);
	}

}

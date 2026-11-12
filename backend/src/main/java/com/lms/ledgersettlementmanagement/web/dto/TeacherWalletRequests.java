package com.lms.ledgersettlementmanagement.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * TEACHER-WALLET-01 request bodies. Amount, precision, currency, account and
 * reason rules are enforced in {@code TeacherWalletService} so every caller
 * gets the same field-level 400s. No body carries a tenant id.
 */
public final class TeacherWalletRequests {

	private TeacherWalletRequests() {
	}

	/** {@code POST /finance/teachers/{teacherId}/wallet} - zero is an explicit, valid opening balance. */
	public record Create(BigDecimal openingBalance, String currency) {

	}

	/**
	 * {@code POST .../wallet/pay-ins|pay-outs} - positive {@code amount}; {@code expectedBalance}
	 * is the balance the user saw (stale = 409). {@code accountId}/{@code categoryId} are
	 * rejected until Finance Accounts exist.
	 */
	public record Posting(BigDecimal amount, String description, String currency, UUID accountId, UUID categoryId,
			BigDecimal expectedBalance) {

	}

	/** {@code POST .../wallet/adjustments} - the balance pencil. */
	public record Adjustment(BigDecimal targetBalance, BigDecimal expectedBalance, String reason) {

	}

}

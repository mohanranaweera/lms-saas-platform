package com.lms.financeexpensemanagement.domain;

/**
 * FIN-01 account-book enums (one file: they are small and change together;
 * every value is also enforced by a CHECK constraint in V125).
 */
public final class FinanceEnums {

	private FinanceEnums() {
	}

	/** Direction is implied by the type - see V125 header. */
	public enum TransactionType {

		OPENING, INCOME, EXPENSE, TRANSFER, ADJUSTMENT, REFUND, REVERSAL;

		/** Counted as period income on the Budget dashboard (REFUND negative). */
		public boolean isIncomeLike() {
			return this == INCOME || this == REFUND;
		}

	}

	public enum TransactionStatus {

		POSTED, REVERSED, UNPOSTED

	}

	public enum SourceType {

		MANUAL, LEDGER_ENTRY, EXPENSE, WALLET_ENTRY, SCHEDULE

	}

	/**
	 * Real payment-method codes a confirmed student payment can carry. The UI
	 * groups them into the four Klass slots: Cash = CASH; Card = CARD; Bank/Online
	 * = BANK_TRANSFER + GATEWAY + MANUAL_SLIP; Other = OTHER (fallback).
	 */
	public enum RoutingMethod {

		CASH, CARD, BANK_TRANSFER, GATEWAY, MANUAL_SLIP, OTHER

	}

	public enum ScheduleType {

		ONE_TIME, MONTHLY

	}

	public enum ScheduleStatus {

		ACTIVE, COMPLETED, CANCELLED

	}

	public enum OccurrenceStatus {

		DUE, POSTED, FAILED, SKIPPED

	}

}

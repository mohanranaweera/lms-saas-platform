package com.lms.financeexpensemanagement.domain;

/**
 * How an expense was paid. Mirrors {@code expense}'s {@code ck_expense_method}
 * CHECK constraint (V54) exactly.
 */
public enum ExpenseMethod {

	CASH, BANK_TRANSFER, CARD, CHEQUE, ONLINE, OTHER

}

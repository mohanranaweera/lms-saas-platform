package com.lms.financeexpensemanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleType;
import com.lms.financeexpensemanagement.domain.FinanceEnums.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * FIN-01 request bodies. No {@code tenantId} anywhere - tenant identity is the
 * authenticated context. Validation happens in the services (shared rules,
 * field-level errors).
 */
public final class FinanceRequests {

	private FinanceRequests() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateAccount(String name, String description, BigDecimal openingBalance) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record UpdateAccount(String name, String description) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AdjustBalance(BigDecimal targetBalance, BigDecimal expectedBalance, String reason) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Routing(UUID cashAccountId, UUID cardAccountId, UUID bankOnlineAccountId, UUID otherAccountId) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record RecordTransaction(TransactionType type, BigDecimal amount, LocalDate transactionDate, String note,
			UUID categoryId, UUID accountId, UUID fromAccountId, UUID toAccountId, String currency) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PatchTransaction(String note, UUID categoryId) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Reason(String reason) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PostUnposted(UUID accountId) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Reconcile(LocalDate from) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Schedule(String name, TransactionType type, BigDecimal amount, String currency, UUID accountId,
			UUID fromAccountId, UUID toAccountId, UUID categoryId, String note, ScheduleType scheduleType,
			LocalDate scheduledDate, Integer dayOfMonth) {

	}

}

package com.lms.ledgersettlementmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FIN-01 / ADR-036 §6: a teacher wallet Pay Out that the user linked to a
 * Finance account. Published synchronously by {@code TeacherWalletService}
 * inside the wallet transaction. Exactly one listener (the Finance account
 * book) must post one outflow and call {@link #markPosted(UUID)}; the
 * publisher fails the wallet entry when nobody did, so a linked payout can
 * never be saved without its Finance posting (and vice versa).
 *
 * <p>Wallet Pay In carries no such event: an account-linked Pay In still needs
 * a product decision on what it means for an account (ADR-036 §6).
 */
public final class WalletPayoutLinkedEvent {

	private final UUID tenantId;

	private final UUID settlementId;

	private final UUID teacherId;

	private final BigDecimal amount;

	private final String currency;

	private final UUID accountId;

	private final UUID categoryId;

	private final String description;

	private final UUID actorId;

	private final Instant recordedAt;

	private final AtomicReference<UUID> postedTransactionId = new AtomicReference<>();

	public WalletPayoutLinkedEvent(UUID tenantId, UUID settlementId, UUID teacherId, BigDecimal amount,
			String currency, UUID accountId, UUID categoryId, String description, UUID actorId, Instant recordedAt) {
		this.tenantId = tenantId;
		this.settlementId = settlementId;
		this.teacherId = teacherId;
		this.amount = amount;
		this.currency = currency;
		this.accountId = accountId;
		this.categoryId = categoryId;
		this.description = description;
		this.actorId = actorId;
		this.recordedAt = recordedAt;
	}

	public UUID tenantId() {
		return tenantId;
	}

	public UUID settlementId() {
		return settlementId;
	}

	public UUID teacherId() {
		return teacherId;
	}

	/** Positive payout amount (the wallet row stores it negative). */
	public BigDecimal amount() {
		return amount;
	}

	public String currency() {
		return currency;
	}

	public UUID accountId() {
		return accountId;
	}

	public UUID categoryId() {
		return categoryId;
	}

	public String description() {
		return description;
	}

	public UUID actorId() {
		return actorId;
	}

	public Instant recordedAt() {
		return recordedAt;
	}

	public void markPosted(UUID transactionId) {
		postedTransactionId.set(transactionId);
	}

	public UUID postedTransactionId() {
		return postedTransactionId.get();
	}

	public boolean isPosted() {
		return postedTransactionId.get() != null;
	}

}

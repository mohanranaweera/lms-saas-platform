package com.lms.enrollmentmanagement.domain;

import com.lms.common.persistence.TenantOwned;
import com.lms.common.persistence.UuidV7;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code free_card} (V94, ADR-030 F1-F8): a dated, reasoned, revocable
 * complimentary pass for one MONTHLY course of one student. It covers the
 * {@code MONTHLY:<courseId>:<yyyy-mm>} key of every calendar month touched by
 * [{@code validFrom}, {@code validTo}] while {@code effectiveFrom <= now <
 * effectiveUntil} and not revoked. It is never a payment: it creates no
 * order, payment, ledger, settlement or postage row; {@code
 * nominalMonthlyAmount}/{@code nominalValue} are informational only (F7).
 *
 * <p>Every column except the three revoke columns is {@code updatable =
 * false}; the revoke columns change exactly once, through {@link #revoke}
 * (F8). Rows are never deleted (the repository refuses deletes).
 */
@Entity
@Table(name = "free_card")
public class FreeCard implements TenantOwned {

	/** F3: a card may span at most this many covered calendar months. */
	public static final int MAX_COVERED_MONTHS = 24;

	/** Derived lifecycle state at an instant (never persisted). */
	public enum Status {

		SCHEDULED, ACTIVE, EXPIRED, REVOKED

	}

	@Id
	@UuidV7
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "card_type", nullable = false, updatable = false, length = 10)
	private FreeCardType cardType;

	@Column(name = "valid_from", nullable = false, updatable = false)
	private LocalDate validFrom;

	@Column(name = "valid_to", nullable = false, updatable = false)
	private LocalDate validTo;

	@Column(name = "timezone", nullable = false, updatable = false, length = 64)
	private String timezone;

	@Column(name = "effective_from", nullable = false, updatable = false)
	private Instant effectiveFrom;

	@Column(name = "effective_until", nullable = false, updatable = false)
	private Instant effectiveUntil;

	@Column(name = "reason", nullable = false, updatable = false)
	private String reason;

	@Column(name = "currency", nullable = false, updatable = false, length = 3)
	private String currency;

	@Column(name = "nominal_monthly_amount", updatable = false, precision = 12, scale = 2)
	private BigDecimal nominalMonthlyAmount;

	@Column(name = "nominal_value", updatable = false, precision = 12, scale = 2)
	private BigDecimal nominalValue;

	@Column(name = "issued_by", nullable = false, updatable = false)
	private UUID issuedBy;

	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private UUID idempotencyKey;

	@Column(name = "enrollment_created", nullable = false, updatable = false)
	private boolean enrollmentCreated;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private UUID revokedBy;

	@Column(name = "revoked_reason")
	private String revokedReason;

	protected FreeCard() {
	}

	/**
	 * A new card. {@code effectiveFrom} = start of {@code validFrom} and
	 * {@code effectiveUntil} = start of the day after {@code validTo}, both in
	 * {@code zone} (snapshotted on the row, F3). Shape rules are re-checked
	 * here (fail closed); business validation (today, span) is the service's.
	 */
	public static FreeCard issue(UUID tenantId, UUID studentId, UUID courseId, FreeCardType cardType,
			LocalDate validFrom, LocalDate validTo, ZoneId zone, String reason, String currency,
			BigDecimal nominalMonthlyAmount, UUID issuedBy, Instant issuedAt, UUID idempotencyKey,
			boolean enrollmentCreated) {
		Objects.requireNonNull(validFrom);
		Objects.requireNonNull(validTo);
		if (validTo.isBefore(validFrom)) {
			throw new IllegalArgumentException("validTo must not be before validFrom");
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A reason is required");
		}
		FreeCard card = new FreeCard();
		card.tenantId = tenantId;
		card.studentId = Objects.requireNonNull(studentId);
		card.courseId = Objects.requireNonNull(courseId);
		card.cardType = Objects.requireNonNull(cardType);
		card.validFrom = validFrom;
		card.validTo = validTo;
		card.timezone = zone.getId();
		card.effectiveFrom = validFrom.atStartOfDay(zone).toInstant();
		card.effectiveUntil = validTo.plusDays(1).atStartOfDay(zone).toInstant();
		card.reason = reason.strip();
		card.currency = Objects.requireNonNull(currency);
		card.nominalMonthlyAmount = nominalMonthlyAmount == null ? null
				: nominalMonthlyAmount.setScale(2, RoundingMode.HALF_UP);
		card.nominalValue = card.nominalMonthlyAmount == null ? null
				: card.nominalMonthlyAmount.multiply(BigDecimal.valueOf(coveredMonthCount(validFrom, validTo)));
		card.issuedBy = Objects.requireNonNull(issuedBy);
		card.issuedAt = Objects.requireNonNull(issuedAt);
		card.idempotencyKey = Objects.requireNonNull(idempotencyKey);
		card.enrollmentCreated = enrollmentCreated;
		return card;
	}

	/** Number of calendar months touched by [from, to] (inclusive). */
	public static int coveredMonthCount(LocalDate from, LocalDate to) {
		YearMonth first = YearMonth.from(from);
		YearMonth last = YearMonth.from(to);
		return (int) (first.until(last, java.time.temporal.ChronoUnit.MONTHS) + 1);
	}

	/** Every calendar month touched by [validFrom, validTo], in order. */
	public List<YearMonth> coveredMonths() {
		List<YearMonth> months = new ArrayList<>();
		YearMonth last = YearMonth.from(validTo);
		for (YearMonth month = YearMonth.from(validFrom); !month.isAfter(last); month = month.plusMonths(1)) {
			months.add(month);
		}
		return months;
	}

	/** The MONTHLY coverage keys of {@link #coveredMonths()} - always this card's own course (F4). */
	public List<String> coveredMonthKeys() {
		return coveredMonths().stream()
			.map(month -> CoverageKeys.monthly(courseId, month.getYear(), month.getMonthValue()))
			.toList();
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	/** Usable now: not revoked and {@code effectiveFrom <= now < effectiveUntil} (F4). */
	public boolean isEffectiveAt(Instant now) {
		return statusAt(now) == Status.ACTIVE;
	}

	public Status statusAt(Instant now) {
		if (revokedAt != null) {
			return Status.REVOKED;
		}
		if (now.isBefore(effectiveFrom)) {
			return Status.SCHEDULED;
		}
		if (!now.isBefore(effectiveUntil)) {
			return Status.EXPIRED;
		}
		return Status.ACTIVE;
	}

	/** True when [validFrom, validTo] intersects [from, to] (F5 overlap). */
	public boolean overlaps(LocalDate from, LocalDate to) {
		return !validFrom.isAfter(to) && !from.isAfter(validTo);
	}

	/**
	 * F8: sets the revoke columns exactly once.
	 * @throws IllegalStateException when already revoked.
	 */
	public void revoke(UUID actor, Instant at, String why) {
		if (revokedAt != null) {
			throw new IllegalStateException("Free card already revoked");
		}
		if (why == null || why.isBlank()) {
			throw new IllegalArgumentException("A revocation reason is required");
		}
		this.revokedAt = Objects.requireNonNull(at);
		this.revokedBy = Objects.requireNonNull(actor);
		this.revokedReason = why.strip();
	}

	public UUID getId() {
		return id;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public FreeCardType getCardType() {
		return cardType;
	}

	public LocalDate getValidFrom() {
		return validFrom;
	}

	public LocalDate getValidTo() {
		return validTo;
	}

	public String getTimezone() {
		return timezone;
	}

	public Instant getEffectiveFrom() {
		return effectiveFrom;
	}

	public Instant getEffectiveUntil() {
		return effectiveUntil;
	}

	public String getReason() {
		return reason;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getNominalMonthlyAmount() {
		return nominalMonthlyAmount;
	}

	public BigDecimal getNominalValue() {
		return nominalValue;
	}

	public UUID getIssuedBy() {
		return issuedBy;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public UUID getIdempotencyKey() {
		return idempotencyKey;
	}

	public boolean isEnrollmentCreated() {
		return enrollmentCreated;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public UUID getRevokedBy() {
		return revokedBy;
	}

	public String getRevokedReason() {
		return revokedReason;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof FreeCard other && id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}

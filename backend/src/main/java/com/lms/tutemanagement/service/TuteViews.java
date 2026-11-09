package com.lms.tutemanagement.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TUTE-01 response shapes (wrapped in {@code ApiResponse}). Only display data; queue rows carry
 * names, index numbers and cities - never full addresses or phones (those only reach the label
 * PDF, behind {@code TUTE_DISPATCH_PRINT_LABELS}).
 */
public final class TuteViews {

	private TuteViews() {
	}

	/** A course tute; {@code available = quantity - reserved - dispatched}. */
	public record TuteView(UUID id, UUID courseId, String name, String description, int quantity, long reserved,
			long dispatched, long available, String paymentType, BigDecimal price, String currency,
			UUID paidLessonId, String paidLessonTitle, String status, Instant archivedAt, long version,
			Instant createdAt, Instant updatedAt) {
	}

	/** A PAID session of the course a tute may be sold through. */
	public record PaidSessionView(UUID lessonId, String title, String lessonLabel, String moduleTitle,
			BigDecimal price, String currency, Instant startsAt) {
	}

	/** {@code result}: {@code DELETED} (no history) or {@code ARCHIVED}. */
	public record DeleteResultView(String result) {
	}

	public record SyncResultView(int added, int alreadyAssigned, int skippedOutOfStock, int held) {
	}

	public record AssignResultView(int added, int alreadyAssigned, long available) {
	}

	/** One student's copy of the tute (tute view: assigned students). */
	public record FulfillmentView(UUID id, UUID tuteId, UUID courseId, UUID studentId, UUID studentProfileId,
			String indexNumber, String studentName, String city, boolean addressMissing, String status,
			String method, String source, String tuteName, String paymentType, BigDecimal price, String currency,
			boolean held, Instant paymentReversedAt, Instant holdReleasedAt, Instant createdAt,
			Instant dispatchedAt, Instant cancelledAt, String cancelReason) {
	}

	public record EligibleStudentView(UUID studentId, UUID studentProfileId, String indexNumber, String name,
			boolean alreadyAssigned) {
	}

	/** One chip "COURSE - Tute" of a queue row. */
	public record DispatchItemView(UUID fulfillmentId, UUID tuteId, String tuteName, UUID courseId,
			String courseName, String label) {
	}

	/** One recipient group (student + normalized address). */
	public record DispatchRowView(String groupKey, UUID studentId, UUID studentProfileId, String indexNumber,
			String studentName, String city, boolean addressMissing, int tuteCount, List<DispatchItemView> items) {
	}

	/**
	 * A queue page. {@code totalElements} = filtered groups (labels), {@code missingAddressCount}
	 * = filtered groups without an address, {@code itemCount} = filtered tutes.
	 */
	public record DispatchQueueView(List<DispatchRowView> content, int page, int size, long totalElements,
			int totalPages, long missingAddressCount, long itemCount) {
	}

	public record OptionView(UUID id, String name) {
	}

	public record TuteOptionView(UUID id, String name, UUID courseId) {
	}

	public record FilterOptionsView(List<OptionView> courses, List<TuteOptionView> tutes) {
	}

	public record ConfirmDispatchView(int dispatched, int alreadyDispatched, List<UUID> dispatchedIds,
			List<UUID> alreadyDispatchedIds) {
	}

}

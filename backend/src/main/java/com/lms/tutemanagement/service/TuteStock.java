package com.lms.tutemanagement.service;

import com.lms.tutemanagement.domain.Tute;
import com.lms.tutemanagement.repository.TuteFulfillmentRepository;
import com.lms.tutemanagement.repository.TuteFulfillmentWrites.NewFulfillment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * TUTE-01 section 4 stock: every reservation happens inside a transaction holding the tute row
 * lock ({@code SELECT ... FOR UPDATE}), so concurrent assignments of one tute are serialized and
 * {@code reserved + dispatched} can never exceed {@code quantity}. Callers must already have
 * loaded {@code tute} through the tenant-scoped repository.
 */
@Component
class TuteStock {

	private final TuteFulfillmentRepository fulfillmentRepository;

	@PersistenceContext
	private EntityManager entityManager;

	TuteStock(TuteFulfillmentRepository fulfillmentRepository) {
		this.fulfillmentRepository = fulfillmentRepository;
	}

	/** Locks the tute row (re-reading it) for the rest of the caller's transaction. */
	void lock(Tute tute) {
		entityManager.refresh(tute, LockModeType.PESSIMISTIC_WRITE);
	}

	/**
	 * Locks one fulfillment row and re-reads it, so a decision is never taken on a stale copy
	 * (callers lock several rows in id order).
	 */
	void lock(com.lms.tutemanagement.domain.TuteFulfillment row) {
		entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
	}

	/** Copies still available; call only while holding {@link #lock}. */
	long available(Tute tute) {
		return Math.max(0, tute.getQuantity() - fulfillmentRepository.countAllocated(tute.getId()));
	}

	/**
	 * Inserts the row when the student holds no non-cancelled copy yet and stock allows; call
	 * only while holding {@link #lock}.
	 */
	Outcome reserve(Tute tute, NewFulfillment row) {
		if (fulfillmentRepository.findActiveStudentIds(tute.getId()).contains(row.studentId())) {
			return Outcome.ALREADY_ASSIGNED;
		}
		if (available(tute) < 1) {
			return Outcome.OUT_OF_STOCK;
		}
		return fulfillmentRepository.insertIfAbsent(row) == 1 ? Outcome.ADDED : Outcome.ALREADY_ASSIGNED;
	}

	enum Outcome {

		ADDED, ALREADY_ASSIGNED, OUT_OF_STOCK

	}

}

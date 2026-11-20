package com.lms.paymentmanagement.slip.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.paymentmanagement.slip.domain.PaymentSlipNote;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * PAY-HUB-01. Tenant-scoped per ADR-006 and append-only like {@link
 * PaymentSlipFlagRepository}: every delete-shaped method fails loudly (V106's
 * trigger rejects UPDATE/DELETE as well), and no update method exists - the
 * service only ever {@code save}s a brand new note.
 */
public interface PaymentSlipNoteRepository extends TenantAwareRepository<PaymentSlipNote, UUID> {

	String APPEND_ONLY = "payment_slip_note is append-only - no row may ever be deleted";

	/** A slip's notes, oldest first, at most {@code limit} - tenant-scoped. */
	default List<PaymentSlipNote> findAllBySlipIdOldestFirst(UUID slipId, int limit) {
		return findAll((root, query, cb) -> cb.equal(root.get("slipId"), slipId),
				PageRequest.of(0, limit, Sort.by("createdAt", "id")))
			.getContent();
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void delete(PaymentSlipNote entity) {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void deleteAll(Iterable<? extends PaymentSlipNote> entities) {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void deleteAllInBatch(Iterable<PaymentSlipNote> entities) {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException(APPEND_ONLY);
	}

}

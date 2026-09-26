package com.lms.common.persistence;

import java.io.Serializable;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * A {@link TenantAwareRepository} for append-only financial/audit tables
 * (Wave 7 settlement tables): every delete-shaped method inherited from
 * {@code JpaRepository} fails loudly, mirroring the explicit per-repository
 * overrides {@code LedgerEntryRepository} established - "no repository method
 * exposes delete/deleteById" (spec 24 §8).
 */
@NoRepositoryBean
public interface AppendOnlyTenantAwareRepository<T extends TenantOwned, ID extends Serializable>
		extends TenantAwareRepository<T, ID> {

	String DELETE_FORBIDDEN = "append-only financial history - rows are never deleted";

	@Override
	default void deleteById(ID id) {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void delete(T entity) {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void deleteAllById(Iterable<? extends ID> ids) {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void deleteAll(Iterable<? extends T> entities) {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void deleteAllInBatch(Iterable<T> entities) {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<ID> ids) {
		throw new UnsupportedOperationException(DELETE_FORBIDDEN);
	}

}

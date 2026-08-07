package com.lms.common.persistence;

import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

/**
 * Default implementation backing {@link TenantAwareRepository}. Every
 * inherited finder is composed with a predicate scoping to
 * {@link TenantContext#getTenantId()} - reading the context before it has
 * been populated fails loudly (see
 * {@link com.lms.common.tenant.TenantContextHolder}) rather than silently
 * returning unfiltered, cross-tenant rows.
 *
 * {@code save}/{@code saveAll} additionally guard against persisting an
 * entity whose own {@code tenantId} field disagrees with the current
 * context - a defense-in-depth check against constructing an entity for the
 * wrong tenant, independent of read-path filtering.
 *
 * {@link org.springframework.data.jpa.domain.Specification}-based finders
 * ({@code findOne}/{@code findAll}/{@code count}/{@code exists}) are also
 * AND-combined with {@link #scopedToTenant()} here, so a caller-supplied
 * {@code Specification} (e.g. a derived lookup like "find by email") can
 * never accidentally search across tenants even though it does not itself
 * reference {@code tenant_id} - added for identity-access-service's
 * tenant-scoped lookups (AUTH-1) and applies platform-wide to every
 * tenant-owned repository built on this base.
 */
public class TenantAwareRepositoryImpl<T extends TenantOwned, ID extends Serializable>
		extends SimpleJpaRepository<T, ID> implements TenantAwareRepository<T, ID> {

	private final JpaEntityInformation<T, ?> entityInformation;

	private final TenantContext tenantContext;

	public TenantAwareRepositoryImpl(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager,
			TenantContext tenantContext) {
		super(entityInformation, entityManager);
		this.entityInformation = entityInformation;
		this.tenantContext = tenantContext;
	}

	private Specification<T> scopedToTenant() {
		UUID tenantId = tenantContext.getTenantId();
		return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
	}

	private Specification<T> scopedToId(ID id) {
		return (root, query, cb) -> cb.equal(root.get(entityInformation.getIdAttribute()), id);
	}

	@Override
	public Optional<T> findById(ID id) {
		return findOne(scopedToTenant().and(scopedToId(id)));
	}

	@Override
	public boolean existsById(ID id) {
		return count(scopedToTenant().and(scopedToId(id))) > 0;
	}

	@Override
	public List<T> findAll() {
		return findAll(scopedToTenant());
	}

	@Override
	public List<T> findAll(Sort sort) {
		return findAll(scopedToTenant(), sort);
	}

	@Override
	public Page<T> findAll(Pageable pageable) {
		return findAll(scopedToTenant(), pageable);
	}

	@Override
	public long count() {
		return count(scopedToTenant());
	}

	@Override
	public List<T> findAllById(Iterable<ID> ids) {
		List<ID> idList = toList(ids);
		return findAll(scopedToTenant()
			.and((root, query, cb) -> root.get(entityInformation.getIdAttribute()).in(idList)));
	}

	@Override
	public Optional<T> findOne(Specification<T> spec) {
		return super.findOne(scopedToTenant().and(spec));
	}

	@Override
	public List<T> findAll(Specification<T> spec) {
		return super.findAll(scopedToTenant().and(spec));
	}

	@Override
	public List<T> findAll(Specification<T> spec, Sort sort) {
		return super.findAll(scopedToTenant().and(spec), sort);
	}

	@Override
	public Page<T> findAll(Specification<T> spec, Pageable pageable) {
		return super.findAll(scopedToTenant().and(spec), pageable);
	}

	@Override
	public long count(Specification<T> spec) {
		return super.count(scopedToTenant().and(spec));
	}

	@Override
	public boolean exists(Specification<T> spec) {
		return super.exists(scopedToTenant().and(spec));
	}

	@Override
	public <S extends T> S save(S entity) {
		assertOwnedByCurrentTenant(entity);
		return super.save(entity);
	}

	@Override
	public <S extends T> List<S> saveAll(Iterable<S> entities) {
		entities.forEach(this::assertOwnedByCurrentTenant);
		return super.saveAll(entities);
	}

	@Override
	public T getReferenceById(ID id) {
		throw new UnsupportedOperationException(
				"getReferenceById is not tenant-safe on TenantAwareRepositoryImpl - it returns a lazy proxy "
						+ "with no tenant check. Use findById instead.");
	}

	@Override
	public void deleteAllInBatch() {
		super.deleteAllInBatch(findAll());
	}

	@Override
	public void deleteAllInBatch(Iterable<T> entities) {
		entities.forEach(this::assertOwnedByCurrentTenant);
		super.deleteAllInBatch(entities);
	}

	@Override
	public void deleteAllByIdInBatch(Iterable<ID> ids) {
		List<ID> ownedIds = findAllById(ids).stream()
			.map(entity -> (ID) entityInformation.getId(entity))
			.collect(Collectors.toList());
		super.deleteAllByIdInBatch(ownedIds);
	}

	private void assertOwnedByCurrentTenant(T entity) {
		UUID tenantId = tenantContext.getTenantId();
		if (entity.getTenantId() == null) {
			entity.setTenantId(tenantId);
			return;
		}
		if (!tenantId.equals(entity.getTenantId())) {
			throw new CrossTenantPersistenceException(
					"Attempted to persist an entity belonging to a different tenant than the current context");
		}
	}

	private List<ID> toList(Iterable<ID> ids) {
		List<ID> result = new ArrayList<>();
		ids.forEach(result::add);
		return result;
	}

}

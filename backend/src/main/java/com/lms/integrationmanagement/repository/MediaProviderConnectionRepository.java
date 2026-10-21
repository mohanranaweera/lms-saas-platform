package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionScope;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006: every default method below is built on the
 * inherited {@code Specification}-backed finders, which {@code
 * TenantAwareRepositoryImpl} scopes to the current {@code TenantContext}.
 *
 * <p>{@link #findLiveAcrossTenantsByProviderAndExternalAccountId} is the one
 * deliberate cross-tenant method (ADR-018 §5): Zoom's {@code
 * app_deauthorized} webhook only identifies the Zoom user, so the matching
 * connections must be found without a tenant. Its only permitted use is to
 * DISCONNECT the rows it returns.
 */
public interface MediaProviderConnectionRepository extends TenantAwareRepository<MediaProviderConnection, UUID> {

	/** The teacher's current (non-DISCONNECTED) connection for a provider, if any. */
	default Optional<MediaProviderConnection> findCurrentForTeacher(UUID ownerUserId, MediaProvider provider) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("ownerUserId"), ownerUserId),
				cb.equal(root.get("provider"), provider),
				cb.notEqual(root.get("status"), MediaConnectionStatus.DISCONNECTED)));
	}

	/** Every connection (any status) a teacher has had for a provider, newest first. */
	default List<MediaProviderConnection> findAllForTeacher(UUID ownerUserId, MediaProvider provider) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("ownerUserId"), ownerUserId),
				cb.equal(root.get("provider"), provider)), Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	/**
	 * The tenant's current (non-DISCONNECTED) DynTube/Bunny connection, if any.
	 * Not for YouTube since Phase 5d (several per tenant - use the integration finders).
	 */
	default Optional<MediaProviderConnection> findCurrentForTenant(MediaProvider provider) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("scope"), ConnectionScope.TENANT),
				cb.equal(root.get("provider"), provider),
				cb.notEqual(root.get("status"), MediaConnectionStatus.DISCONNECTED)));
	}

	/** Every tenant-level connection (any status) for a provider, newest first. */
	default List<MediaProviderConnection> findAllForTenant(MediaProvider provider) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("scope"), ConnectionScope.TENANT),
				cb.equal(root.get("provider"), provider)), Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	/** VIDEO Phase 5d: the integration's current (non-DISCONNECTED) connection - at most one (V76). */
	default Optional<MediaProviderConnection> findCurrentForIntegration(UUID integrationId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("integrationId"), integrationId),
				cb.notEqual(root.get("status"), MediaConnectionStatus.DISCONNECTED)));
	}

	/** VIDEO Phase 5d: every connection (any status) of the integration, newest first. */
	default List<MediaProviderConnection> findAllForIntegration(UUID integrationId) {
		return findAll((root, query, cb) -> cb.equal(root.get("integrationId"), integrationId),
				Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	/** VIDEO Phase 5d: every current (non-DISCONNECTED) institute YouTube connection. */
	default List<MediaProviderConnection> findCurrentTenantYouTube() {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("scope"), ConnectionScope.TENANT),
				cb.equal(root.get("provider"), MediaProvider.YOUTUBE),
				cb.notEqual(root.get("status"), MediaConnectionStatus.DISCONNECTED)),
				Sort.by(Sort.Direction.ASC, "createdAt"));
	}

	/** Every current (non-DISCONNECTED) teacher connection in the tenant - Tenant Admin overview. */
	default List<MediaProviderConnection> findCurrentTeacherConnections() {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("scope"), ConnectionScope.TEACHER),
				cb.notEqual(root.get("status"), MediaConnectionStatus.DISCONNECTED)),
				Sort.by(Sort.Direction.ASC, "provider"));
	}

	default List<MediaProviderConnection> findByIds(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("id").in(ids));
	}

	/**
	 * ADR-018 §5 cross-tenant lookup - Zoom deauthorization webhook ONLY.
	 * Deliberately a plain JPQL query (not tenant-scoped) and deliberately
	 * named so the bypass is visible in review.
	 */
	@Query("SELECT c FROM MediaProviderConnection c WHERE c.provider = :provider "
			+ "AND c.externalAccountId = :externalAccountId AND c.status <> :disconnected")
	List<MediaProviderConnection> findLiveAcrossTenantsByProviderAndExternalAccountId(
			@Param("provider") MediaProvider provider, @Param("externalAccountId") String externalAccountId,
			@Param("disconnected") MediaConnectionStatus disconnected);

}

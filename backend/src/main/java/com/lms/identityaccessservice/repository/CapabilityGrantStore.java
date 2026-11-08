package com.lms.identityaccessservice.repository;

import com.lms.common.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * STAFF-MGMT-02 (V118): tenant role-capability grants and the matrix version counter.
 * Every statement is scoped to the tenant resolved in the trusted {@link TenantContext}
 * (throws when none is resolved); no method accepts a caller-supplied tenant id. Plain JDBC
 * because the writes are an optimistic {@code UPDATE ... WHERE version = ?} and an
 * {@code INSERT ... ON CONFLICT} upsert.
 */
@Repository
public class CapabilityGrantStore {

	private final JdbcTemplate jdbc;

	private final TenantContext tenantContext;

	public CapabilityGrantStore(JdbcTemplate jdbc, TenantContext tenantContext) {
		this.jdbc = jdbc;
		this.tenantContext = tenantContext;
	}

	/** capability_key -> granted, for one role of the current tenant (explicit rows only). */
	public Map<String, Boolean> findOverridesForRole(String roleCode) {
		Map<String, Boolean> result = new HashMap<>();
		jdbc.query("SELECT capability_key, granted FROM tenant_role_capability_grant "
				+ "WHERE tenant_id = ? AND role_code = ?",
				rs -> {
					result.put(rs.getString(1), rs.getBoolean(2));
				}, tenantContext.getTenantId(), roleCode);
		return result;
	}

	/** role_code -> (capability_key -> granted) for the current tenant (explicit rows only). */
	public Map<String, Map<String, Boolean>> findAllOverrides() {
		Map<String, Map<String, Boolean>> result = new HashMap<>();
		jdbc.query("SELECT role_code, capability_key, granted FROM tenant_role_capability_grant WHERE tenant_id = ?",
				rs -> {
					result.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getBoolean(3));
				}, tenantContext.getTenantId());
		return result;
	}

	/** Current matrix version; 0 when the tenant never saved its matrix. */
	public long currentVersion() {
		List<Long> versions = jdbc.queryForList(
				"SELECT version FROM tenant_role_permission_version WHERE tenant_id = ?", Long.class,
				tenantContext.getTenantId());
		return versions.isEmpty() ? 0L : versions.get(0);
	}

	/**
	 * Optimistic advance: creates the version row (version 0) if missing, then increments it
	 * only when it still equals {@code expectedVersion}. {@code false} = stale (0 rows updated).
	 */
	public boolean advanceVersion(long expectedVersion, UUID actorId) {
		UUID tenantId = tenantContext.getTenantId();
		jdbc.update("INSERT INTO tenant_role_permission_version (tenant_id, version, updated_at, updated_by) "
				+ "VALUES (?, 0, now(), NULL) ON CONFLICT (tenant_id) DO NOTHING", tenantId);
		int updated = jdbc.update("UPDATE tenant_role_permission_version SET version = version + 1, "
				+ "updated_at = now(), updated_by = ? WHERE tenant_id = ? AND version = ?", actorId, tenantId,
				expectedVersion);
		return updated == 1;
	}

	public void upsert(String roleCode, String capabilityKey, boolean granted, UUID actorId) {
		jdbc.update("INSERT INTO tenant_role_capability_grant "
				+ "(id, tenant_id, role_code, capability_key, granted, updated_at, updated_by) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, role_code, capability_key) "
				+ "DO UPDATE SET granted = EXCLUDED.granted, updated_at = EXCLUDED.updated_at, "
				+ "updated_by = EXCLUDED.updated_by", UUID.randomUUID(), tenantContext.getTenantId(), roleCode,
				capabilityKey, granted, Timestamp.from(Instant.now()), actorId);
	}

}

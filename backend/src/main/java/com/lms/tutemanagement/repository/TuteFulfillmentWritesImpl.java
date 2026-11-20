package com.lms.tutemanagement.repository;

import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Package-private implementation of {@link TuteFulfillmentWrites}. The tenant id comes only
 * from {@link TenantContext} (fails loudly when unresolved). Optional values are bound only
 * when present (SQL {@code NULL} literals otherwise), so no untyped null parameter is bound.
 */
class TuteFulfillmentWritesImpl implements TuteFulfillmentWrites {

	private static final int CHUNK = 1000;

	private final TenantContext tenantContext;

	@PersistenceContext
	private EntityManager entityManager;

	TuteFulfillmentWritesImpl(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	public int insertIfAbsent(NewFulfillment row) {
		Map<String, Object> optional = new HashMap<>();
		optional.put("paymentId", row.paymentId());
		optional.put("orderId", row.orderId());
		optional.put("price", row.price());
		optional.put("deliveryName", row.orderDeliveryName());
		optional.put("deliveryAddress", row.orderDeliveryAddressLine());
		optional.put("deliveryCity", row.orderDeliveryCity());
		optional.put("deliveryPhone", row.orderDeliveryPhone());
		optional.put("actorId", row.actorId());
		String sql = "INSERT INTO tute_fulfillment (id, tenant_id, tute_id, course_id, student_id, status, method, "
				+ "source, source_payment_id, source_order_id, tute_name, payment_type, price, currency, "
				+ "order_delivery_name, order_delivery_address_line, order_delivery_city, order_delivery_phone, "
				+ "created_at, updated_at, created_by, updated_by) VALUES (:id, :tenantId, :tuteId, :courseId, "
				+ ":studentId, 'PENDING', :method, :source, " + param(optional, "paymentId") + ", "
				+ param(optional, "orderId") + ", :tuteName, :paymentType, " + param(optional, "price")
				+ ", :currency, " + param(optional, "deliveryName") + ", " + param(optional, "deliveryAddress") + ", "
				+ param(optional, "deliveryCity") + ", " + param(optional, "deliveryPhone") + ", :now, :now, "
				+ param(optional, "actorId") + ", " + param(optional, "actorId")
				+ ") ON CONFLICT (tenant_id, tute_id, student_id) WHERE status <> 'CANCELLED' DO NOTHING";
		Query query = entityManager.createNativeQuery(sql)
			.setParameter("id", UuidV7Generator.generate())
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("tuteId", row.tuteId())
			.setParameter("courseId", row.courseId())
			.setParameter("studentId", row.studentId())
			.setParameter("method", row.method().name())
			.setParameter("source", row.source().name())
			.setParameter("tuteName", row.tuteName())
			.setParameter("paymentType", row.paymentType().name())
			.setParameter("currency", row.currency())
			.setParameter("now", row.now());
		optional.forEach((name, value) -> {
			if (value != null) {
				query.setParameter(name, value);
			}
		});
		return query.executeUpdate();
	}

	private static String param(Map<String, Object> optional, String name) {
		return optional.get(name) == null ? "NULL" : ":" + name;
	}

	@Override
	public List<UUID> lockFulfillments(Collection<UUID> ids) {
		List<UUID> locked = new ArrayList<>();
		if (ids == null || ids.isEmpty()) {
			return locked;
		}
		List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids));
		for (int from = 0; from < distinct.size(); from += CHUNK) {
			List<UUID> chunk = distinct.subList(from, Math.min(distinct.size(), from + CHUNK));
			@SuppressWarnings("unchecked")
			List<Object> rows = entityManager
				.createNativeQuery("SELECT id FROM tute_fulfillment WHERE tenant_id = :tenantId AND id IN (:ids) "
						+ "ORDER BY id FOR UPDATE")
				.setParameter("tenantId", tenantContext.getTenantId())
				.setParameter("ids", chunk)
				.getResultList();
			rows.forEach(value -> locked.add((UUID) value));
		}
		return locked;
	}

	@Override
	public Map<UUID, long[]> countAllocatedByTute(Collection<UUID> tuteIds) {
		Map<UUID, long[]> result = new HashMap<>();
		if (tuteIds == null || tuteIds.isEmpty()) {
			return result;
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager
			.createNativeQuery("SELECT tute_id, status, count(*) FROM tute_fulfillment WHERE tenant_id = :tenantId "
					+ "AND tute_id IN (:ids) AND status <> 'CANCELLED' GROUP BY tute_id, status")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("ids", List.copyOf(new LinkedHashSet<>(tuteIds)))
			.getResultList();
		for (Object[] row : rows) {
			long[] counts = result.computeIfAbsent((UUID) row[0], id -> new long[2]);
			int slot = "PENDING".equals(row[1]) ? 0 : 1;
			counts[slot] = ((Number) row[2]).longValue();
		}
		return result;
	}

}

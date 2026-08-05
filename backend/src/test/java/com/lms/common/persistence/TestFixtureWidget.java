package com.lms.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Test-only entity backing {@code test_fixture_widget} (see
 * V9001__test_fixture_entity.sql), used exclusively to exercise
 * {@link TenantAwareRepository} and {@link Auditable} against a real table.
 * Never referenced from src/main.
 */
@Entity
@Table(name = "test_fixture_widget")
public class TestFixtureWidget extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false)
	private String name;

	protected TestFixtureWidget() {
	}

	public TestFixtureWidget(UUID tenantId, String name) {
		this.tenantId = tenantId;
		this.name = name;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

}

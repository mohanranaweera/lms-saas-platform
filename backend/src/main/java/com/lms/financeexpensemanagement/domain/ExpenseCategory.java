package com.lms.financeexpensemanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped onto {@code expense_category} (V54) - Wave 7, PAR-23-01. A category
 * is never deleted (expenses reference it and financial history is never
 * deleted); retiring one is the {@code archived} flag, which only hides it
 * from new-expense pickers - existing expenses keep their category.
 */
@Entity
@Table(name = "expense_category")
public class ExpenseCategory extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "archived", nullable = false)
	private boolean archived;

	protected ExpenseCategory() {
	}

	public ExpenseCategory(UUID tenantId, String name, String description) {
		this.tenantId = tenantId;
		this.name = name;
		this.description = description;
		this.archived = false;
	}

	public void rename(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public void archive() {
		this.archived = true;
	}

	public void unarchive() {
		this.archived = false;
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

	public String getDescription() {
		return description;
	}

	public boolean isArchived() {
		return archived;
	}

}

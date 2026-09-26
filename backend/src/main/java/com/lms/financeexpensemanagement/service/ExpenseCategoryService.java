package com.lms.financeexpensemanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave 7 (PAR-23-01) - expense category management. Every method re-checks
 * {@link DomainArea#FINANCE_EXPENSES} server-side ({@code VIEW} for reads,
 * {@code CREATE_EDIT} for writes); every lookup goes through the
 * tenant-scoped repository, so another tenant's category id is a 404.
 */
@Service
@Transactional
public class ExpenseCategoryService {

	private final ExpenseCategoryRepository categoryRepository;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	public ExpenseCategoryService(ExpenseCategoryRepository categoryRepository,
			PermissionCheckService permissionCheckService, TenantContext tenantContext) {
		this.categoryRepository = categoryRepository;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public List<CategoryView> list(boolean includeArchived) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW);
		return categoryRepository.findAllOrdered(includeArchived).stream().map(CategoryView::from).toList();
	}

	public CategoryView create(String name, String description) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		String trimmedName = name.trim();
		requireUniqueName(trimmedName, null);
		ExpenseCategory category = new ExpenseCategory(tenantContext.getTenantId(), trimmedName,
				normalize(description));
		return CategoryView.from(categoryRepository.save(category));
	}

	public CategoryView update(UUID categoryId, String name, String description) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		ExpenseCategory category = load(categoryId);
		String trimmedName = name.trim();
		requireUniqueName(trimmedName, categoryId);
		category.rename(trimmedName, normalize(description));
		return CategoryView.from(categoryRepository.save(category));
	}

	public CategoryView setArchived(UUID categoryId, boolean archived) {
		permissionCheckService.requirePermission(DomainArea.FINANCE_EXPENSES, PermissionAction.CREATE_EDIT);
		ExpenseCategory category = load(categoryId);
		if (archived) {
			category.archive();
		}
		else {
			category.unarchive();
		}
		return CategoryView.from(categoryRepository.save(category));
	}

	private ExpenseCategory load(UUID categoryId) {
		return categoryRepository.findById(categoryId)
			.orElseThrow(() -> new NotFoundException("Expense category not found"));
	}

	private void requireUniqueName(String name, UUID excludeId) {
		if (categoryRepository.existsByNameIgnoreCaseExcluding(name, excludeId)) {
			throw new ConflictException("An expense category with this name already exists");
		}
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	public record CategoryView(UUID id, String name, String description, boolean archived, Instant createdAt,
			Instant updatedAt) {

		static CategoryView from(ExpenseCategory category) {
			return new CategoryView(category.getId(), category.getName(), category.getDescription(),
					category.isArchived(), category.getCreatedAt(), category.getUpdatedAt());
		}

	}

}

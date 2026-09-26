package com.lms.financeexpensemanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.financeexpensemanagement.domain.Expense;
import com.lms.financeexpensemanagement.repository.ExpenseRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional write step of {@link ExpenseService#create}. It is a
 * separate bean so the outbound object-storage call in {@code create} never
 * sits inside a DB transaction (mirrors {@code SlipUploadService}), while the
 * expense row and its audit entry still commit atomically together
 * ({@code .claude/rules/security.md}: audit writes share the privileged
 * action's transaction).
 */
@Service
public class ExpenseWriteService {

	static final String TARGET_ENTITY = "expense";

	private final ExpenseRepository expenseRepository;

	private final AuditLogApi auditLogApi;

	public ExpenseWriteService(ExpenseRepository expenseRepository, AuditLogApi auditLogApi) {
		this.expenseRepository = expenseRepository;
		this.auditLogApi = auditLogApi;
	}

	@Transactional
	public Expense persistNew(Expense expense) {
		Expense saved = expenseRepository.save(expense);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("amount", saved.getAmount().toPlainString());
		metadata.put("currency", saved.getCurrency());
		metadata.put("expenseDate", saved.getExpenseDate().toString());
		metadata.put("categoryId", saved.getCategoryId().toString());
		metadata.put("method", saved.getMethod().name());
		metadata.put("hasAttachment", saved.hasAttachment());
		auditLogApi.record(new AuditLogEntry(saved.getCreatedBy(), "expense.created", TARGET_ENTITY, saved.getId(),
				null, metadata));
		return saved;
	}

}

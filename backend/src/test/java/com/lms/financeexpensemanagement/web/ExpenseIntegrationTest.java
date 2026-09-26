package com.lms.financeexpensemanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.api.PageResponse;
import com.lms.financeexpensemanagement.FinanceTestSupport;
import com.lms.financeexpensemanagement.repository.ExpenseCategoryRepository;
import com.lms.financeexpensemanagement.repository.ExpenseRepository;
import com.lms.financeexpensemanagement.service.ExpenseCategoryService.CategoryView;
import com.lms.financeexpensemanagement.service.ExpenseService.ExpenseView;
import com.lms.financeexpensemanagement.web.dto.AttachmentUrlResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Wave 7 Phase D - expenses/categories: permissions, amount validation,
 * append-only financial history (void), receipt upload gate and protected
 * attachment access, and cross-tenant isolation.
 */
@Tag("cross-tenant")
class ExpenseIntegrationTest extends FinanceTestSupport {

	private static final LocalDate DATE = LocalDate.of(2025, 3, 10);

	@Autowired
	private ExpenseRepository expenseRepository;

	@Autowired
	private ExpenseCategoryRepository categoryRepository;

	@Autowired
	private InMemoryObjectStorageApi objectStorage;

	@Test
	void financeStaffRecordsExpenseWithReceiptAndCanFetchSignedUrl() {
		FinanceTenant t = seedFinanceTenant("exp-happy");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Rent");

		HttpResult<ExpenseView> created = createExpense(t.host(), t.financeToken(), category.id(), DATE, "1500.50",
				"BANK_TRANSFER", receipt(validPdfBytes()));

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		ExpenseView expense = created.getBody().data();
		assertThat(expense.amount()).isEqualByComparingTo("1500.50");
		assertThat(expense.categoryName()).isEqualTo("Rent");
		assertThat(expense.expenseDate()).isEqualTo(DATE);
		assertThat(expense.reference()).isEqualTo("REF-1");
		assertThat(expense.createdByEmail()).isEqualTo("finance@example.test");
		assertThat(expense.hasAttachment()).isTrue();
		assertThat(expense.attachmentMimeType()).isEqualTo("application/pdf");
		assertThat(expense.voided()).isFalse();

		HttpResult<AttachmentUrlResponse> url = attachmentUrl(t.host(), t.financeToken(), expense.id());
		assertThat(url.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(url.getBody().data().url()).startsWith("https://test-object-storage.invalid/");

		String raw = rawContent(perform(authenticated(
				MockMvcRequestBuilders.get("/api/v1/finance/expenses/{id}", expense.id()), t.host(),
				t.financeToken())));
		assertThat(raw).doesNotContain("objectKey");
	}

	@Test
	void spoofedReceiptIsRejectedWithNoPartialWrite() {
		FinanceTenant t = seedFinanceTenant("exp-spoof");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Supplies");
		Set<String> keysBefore = objectStorage.keySet();

		HttpResult<ExpenseView> result = createExpense(t.host(), t.financeToken(), category.id(), DATE, "10.00",
				"CASH", receipt(executableDisguisedAsPdfBytes()));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(countExpenses(t)).isZero();
		assertThat(objectStorage.keySet()).isEqualTo(keysBefore);
	}

	@Test
	void oversizedReceiptIsRejectedWithNoPartialWrite() throws Exception {
		FinanceTenant t = seedFinanceTenant("exp-big");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Supplies");
		Set<String> keysBefore = objectStorage.keySet();
		MockMultipartFile attachment = new MockMultipartFile("attachment", "big.pdf", "application/pdf",
				oversizedPdfFile("big.pdf", 10_485_761).getBytes());

		HttpResult<ExpenseView> result = createExpense(t.host(), t.financeToken(), category.id(), DATE, "10.00",
				"CASH", attachment);

		assertThat(result.getStatusCode().value()).isEqualTo(413);
		assertThat(countExpenses(t)).isZero();
		assertThat(objectStorage.keySet()).isEqualTo(keysBefore);
	}

	@Test
	void expenseWithoutAttachmentHasNoAttachmentUrl() {
		FinanceTenant t = seedFinanceTenant("exp-noatt");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Misc");
		ExpenseView expense = createExpenseOrFail(t.host(), t.financeToken(), category.id(), DATE, "5.00");

		assertThat(expense.hasAttachment()).isFalse();
		assertThat(attachmentUrl(t.host(), t.financeToken(), expense.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void invalidAmountsAreRejected() {
		FinanceTenant t = seedFinanceTenant("exp-amount");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Utilities");

		for (String bad : List.of("0", "0.00", "-5.00", "1.234", "abc", "100000000000.00")) {
			HttpResult<ExpenseView> result = createExpense(t.host(), t.financeToken(), category.id(), DATE, bad,
					"CASH", null);
			assertThat(result.getStatusCode()).as("amount %s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
		}
		assertThat(createExpense(t.host(), t.financeToken(), category.id(), DATE, null, "CASH", null)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countExpenses(t)).isZero();
	}

	@Test
	void futureDateMissingFieldsAndArchivedCategoryAreRejected() {
		FinanceTenant t = seedFinanceTenant("exp-fields");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Travel");

		assertThat(createExpense(t.host(), t.financeToken(), category.id(), LocalDate.now().plusDays(3), "10.00",
				"CASH", null)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(createExpense(t.host(), t.financeToken(), category.id(), DATE, "10.00", null, null)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(createExpense(t.host(), t.financeToken(), category.id(), DATE, "10.00", "BITCOIN", null)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(createExpense(t.host(), t.financeToken(), null, DATE, "10.00", "CASH", null).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);

		archiveCategory(t.host(), t.financeToken(), category.id());
		assertThat(createExpense(t.host(), t.financeToken(), category.id(), DATE, "10.00", "CASH", null)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countExpenses(t)).isZero();
	}

	@Test
	void categoryNamesAreUniquePerTenantCaseInsensitiveButNotAcrossTenants() {
		FinanceTenant a = seedFinanceTenant("cat-uniq-a");
		FinanceTenant b = seedFinanceTenant("cat-uniq-b");
		createCategoryOrFail(a.host(), a.financeToken(), "Marketing");

		assertThat(createCategory(a.host(), a.financeToken(), "marketing").getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		assertThat(createCategory(b.host(), b.financeToken(), "Marketing").getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void voidIsOneWayReasonRequiredAuditedAndKeepsTheRow() {
		FinanceTenant t = seedFinanceTenant("exp-void");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Rent");
		ExpenseView expense = createExpenseOrFail(t.host(), t.financeToken(), category.id(), DATE, "300.00");

		assertThat(voidExpense(t.host(), t.financeToken(), expense.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(voidExpense(t.host(), t.financeToken(), expense.id(), "  ").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);

		HttpResult<ExpenseView> voided = voidExpense(t.host(), t.financeToken(), expense.id(), "Duplicate entry");
		assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(voided.getBody().data().voided()).isTrue();
		assertThat(voided.getBody().data().voidReason()).isEqualTo("Duplicate entry");
		assertThat(voided.getBody().data().voidedByEmail()).isEqualTo("finance@example.test");
		assertThat(voided.getBody().data().amount()).isEqualByComparingTo("300.00");

		assertThat(voidExpense(t.host(), t.financeToken(), expense.id(), "Again").getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);

		assertThat(listExpenses(t.host(), t.financeToken(), null).getBody().data().content())
			.extracting(ExpenseView::id)
			.doesNotContain(expense.id());
		assertThat(listExpenses(t.host(), t.financeToken(), "includeVoided=true").getBody().data().content())
			.extracting(ExpenseView::id)
			.contains(expense.id());

		assertThat(auditCount(expense.id(), "expense.voided")).isEqualTo(1);
		assertThat(auditCount(expense.id(), "expense.created")).isEqualTo(1);
	}

	@Test
	void concurrentVoidsSerializeExactlyOneWinner() throws Exception {
		FinanceTenant t = seedFinanceTenant("exp-race");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Rent");
		ExpenseView expense = createExpenseOrFail(t.host(), t.financeToken(), category.id(), DATE, "300.00");

		java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var results = pool.invokeAll(List.<java.util.concurrent.Callable<HttpStatus>>of(() -> {
				barrier.await();
				return voidExpense(t.host(), t.financeToken(), expense.id(), "Reason A").getStatusCode();
			}, () -> {
				barrier.await();
				return voidExpense(t.host(), t.adminToken(), expense.id(), "Reason B").getStatusCode();
			}));
			List<HttpStatus> statuses = new java.util.ArrayList<>();
			for (var result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(auditCount(expense.id(), "expense.voided")).isEqualTo(1);
	}

	@Test
	void repositoriesExposeNoDelete() {
		assertThatThrownBy(() -> expenseRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> expenseRepository.deleteAll()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> categoryRepository.deleteById(UUID.randomUUID()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void archivingCategoryKeepsItsExpenses() {
		FinanceTenant t = seedFinanceTenant("cat-archive");
		CategoryView category = createCategoryOrFail(t.host(), t.financeToken(), "Old");
		ExpenseView expense = createExpenseOrFail(t.host(), t.financeToken(), category.id(), DATE, "20.00");

		assertThat(archiveCategory(t.host(), t.financeToken(), category.id()).getBody().data().archived()).isTrue();

		assertThat(getExpense(t.host(), t.financeToken(), expense.id()).getBody().data().categoryName())
			.isEqualTo("Old");
		assertThat(listCategories(t.host(), t.financeToken(), false).getBody().data()).extracting(CategoryView::id)
			.doesNotContain(category.id());
		assertThat(listCategories(t.host(), t.financeToken(), true).getBody().data()).extracting(CategoryView::id)
			.contains(category.id());
	}

	@Test
	void listFiltersByDateCategoryAndMethod() {
		FinanceTenant t = seedFinanceTenant("exp-filter");
		CategoryView rent = createCategoryOrFail(t.host(), t.financeToken(), "Rent");
		CategoryView food = createCategoryOrFail(t.host(), t.financeToken(), "Food");
		ExpenseView march = createExpenseOrFail(t.host(), t.financeToken(), rent.id(), LocalDate.of(2025, 3, 5),
				"100.00");
		ExpenseView april = createExpenseOrFail(t.host(), t.financeToken(), food.id(), LocalDate.of(2025, 4, 5),
				"50.00");
		ExpenseView card = createExpense(t.host(), t.financeToken(), food.id(), LocalDate.of(2025, 4, 6), "7.00",
				"CARD", null)
			.getBody()
			.data();

		assertThat(listExpenses(t.host(), t.financeToken(), "from=2025-04-01&to=2025-04-30").getBody()
			.data()
			.content()).extracting(ExpenseView::id).containsExactlyInAnyOrder(april.id(), card.id());
		assertThat(listExpenses(t.host(), t.financeToken(), "categoryId=" + rent.id()).getBody().data().content())
			.extracting(ExpenseView::id)
			.containsExactly(march.id());
		assertThat(listExpenses(t.host(), t.financeToken(), "method=CARD").getBody().data().content())
			.extracting(ExpenseView::id)
			.containsExactly(card.id());
		assertThat(listExpenses(t.host(), t.financeToken(), "from=2025-05-01&to=2025-04-01").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void auditorIsReadOnlyAndNonFinanceRolesAreForbidden() {
		FinanceTenant t = seedFinanceTenant("exp-perm");
		CategoryView category = createCategoryOrFail(t.host(), t.adminToken(), "Rent");
		HttpResult<ExpenseView> withReceipt = createExpense(t.host(), t.adminToken(), category.id(), DATE, "10.00",
				"CASH", receipt(validPngBytes()));
		assertThat(withReceipt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID expenseId = withReceipt.getBody().data().id();

		assertThat(listExpenses(t.host(), t.auditorToken(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getExpense(t.host(), t.auditorToken(), expenseId).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(attachmentUrl(t.host(), t.auditorToken(), expenseId).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listCategories(t.host(), t.auditorToken(), false).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(createCategory(t.host(), t.auditorToken(), "Nope").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(createExpense(t.host(), t.auditorToken(), category.id(), DATE, "10.00", "CASH", null)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(voidExpense(t.host(), t.auditorToken(), expenseId, "x").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);

		for (String token : List.of(t.studentToken(), t.teacherToken(), t.contentToken())) {
			assertThat(listExpenses(t.host(), token, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(getExpense(t.host(), token, expenseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(attachmentUrl(t.host(), token, expenseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(listCategories(t.host(), token, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(createExpense(t.host(), token, category.id(), DATE, "10.00", "CASH", null).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		}
		assertThat(listExpenses(t.host(), null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void crossTenantAccessToExpensesCategoriesAndReceiptsIs404() {
		FinanceTenant a = seedFinanceTenant("exp-xt-a");
		FinanceTenant b = seedFinanceTenant("exp-xt-b");
		CategoryView categoryA = createCategoryOrFail(a.host(), a.financeToken(), "Rent");
		ExpenseView expenseA = createExpense(a.host(), a.financeToken(), categoryA.id(), DATE, "99.00", "CASH",
				receipt(validPdfBytes()))
			.getBody()
			.data();

		assertThat(getExpense(b.host(), b.adminToken(), expenseA.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(attachmentUrl(b.host(), b.adminToken(), expenseA.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(voidExpense(b.host(), b.adminToken(), expenseA.id(), "hostile").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(archiveCategory(b.host(), b.adminToken(), categoryA.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(createExpense(b.host(), b.adminToken(), categoryA.id(), DATE, "1.00", "CASH", null)
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<PageResponse<ExpenseView>> listB = listExpenses(b.host(), b.adminToken(), "includeVoided=true");
		assertThat(listB.getBody().data().content()).extracting(ExpenseView::id).doesNotContain(expenseA.id());
		assertThat(listCategories(b.host(), b.adminToken(), true).getBody().data()).extracting(CategoryView::id)
			.doesNotContain(categoryA.id());

		assertThat(getExpense(a.host(), a.financeToken(), expenseA.id()).getBody().data().voided()).isFalse();
	}

	@Test
	void clientSuppliedTenantIdIsIgnored() {
		FinanceTenant a = seedFinanceTenant("exp-tid-a");
		FinanceTenant b = seedFinanceTenant("exp-tid-b");
		CategoryView category = createCategoryOrFail(a.host(), a.financeToken(), "Rent");
		var builder = MockMvcRequestBuilders.multipart("/api/v1/finance/expenses")
			.param("categoryId", category.id().toString())
			.param("expenseDate", DATE.toString())
			.param("amount", "12.00")
			.param("method", "CASH")
			.param("description", "x")
			.param("tenantId", b.tenant().getId().toString());
		HttpResult<ExpenseView> result = parseSingle(
				performMultipart(authenticatedMultipart(builder, a.host(), a.financeToken())), ExpenseView.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID storedTenant = jdbcTemplate.queryForObject("SELECT tenant_id FROM expense WHERE id = ?", UUID.class,
				result.getBody().data().id());
		assertThat(storedTenant).isEqualTo(a.tenant().getId());
		assertThat(result.getBody().data().amount()).isEqualByComparingTo(new BigDecimal("12.00"));
	}

	private long countExpenses(FinanceTenant t) {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM expense WHERE tenant_id = ?", Long.class,
				t.tenant().getId());
		return count == null ? 0 : count;
	}

	private int auditCount(UUID targetId, String action) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_id = ? AND action = ?", Integer.class, targetId, action);
		return count == null ? 0 : count;
	}

}

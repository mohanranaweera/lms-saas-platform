package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.web.dto.AdminOrderRowResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * PAY-HUB-01 order-side contract (plan §6.1/§6.2): the "All Orders" list with
 * order-number / order-date / status / method filters, and the staff order
 * detail's access rules - with explicit cross-tenant negative tests on both.
 * (The detail's product/fee/postage content is asserted against a real
 * multi-course postage order in {@code MultiCourseCheckoutIntegrationTest}.)
 */
@Tag("cross-tenant")
class PaymentsHubOrderIntegrationTest extends SlipTestSupport {

	@Test
	void listsOneRowPerOrderNewestFirstWithStudentNameAndItemSummary() {
		SlipFixture f = seedTenantWithOrder("hub-orders-list");
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Kamala Perera', now(), now())", UUID.randomUUID(), f.tenant().getId(),
				f.student().getId());
		OrderResponse second = createAnotherOrder(f, "hub-orders-list-2");

		List<AdminOrderRowResponse> rows = rows(listOrders(f.host(), f.adminToken(), Map.of()));

		assertThat(rows).extracting(AdminOrderRowResponse::orderId).containsExactly(second.id(), f.order().id());
		AdminOrderRowResponse first = rows.get(1);
		assertThat(first.displayOrderNumber()).isEqualTo(f.order().displayOrderNumber());
		assertThat(first.studentName()).isEqualTo("Kamala Perera");
		assertThat(first.studentId()).isNotNull();
		assertThat(first.itemCount()).isEqualTo(1);
		assertThat(first.firstItemTitle()).isNotBlank();
		assertThat(first.amount()).isEqualByComparingTo(f.order().amount());
		assertThat(first.operationalState()).isEqualTo(PaymentOperationalState.UNPAID);
		assertThat(first.method()).isNull();
	}

	@Test
	void orderNumberSearchAcceptsDisplayPaddedAndPlainFormsAndRejectsGarbage() {
		SlipFixture f = seedTenantWithOrder("hub-orders-number");
		createAnotherOrder(f, "hub-orders-number-2");
		long number = f.order().orderNumber();

		for (String term : List.of(f.order().displayOrderNumber(), f.order().displayOrderNumber().toLowerCase(),
				String.format("%06d", number), String.valueOf(number))) {
			assertThat(rows(listOrders(f.host(), f.adminToken(), Map.of("orderNumber", term))))
				.as(term)
				.extracting(AdminOrderRowResponse::orderId)
				.containsExactly(f.order().id());
		}
		assertThat(listOrders(f.host(), f.adminToken(), Map.of("orderNumber", "ORD-12a")).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void orderDateRangeIsInclusiveAndAnInvertedRangeIs400() {
		SlipFixture f = seedTenantWithOrder("hub-orders-date");
		// The tenant has no default_timezone config, so days are UTC days.
		LocalDate day = f.order().createdAt().atZone(ZoneOffset.UTC).toLocalDate();

		assertThat(rows(listOrders(f.host(), f.adminToken(), Map.of("from", day.toString(), "to", day.toString()))))
			.extracting(AdminOrderRowResponse::orderId)
			.containsExactly(f.order().id());
		assertThat(rows(listOrders(f.host(), f.adminToken(), Map.of("from", day.plusDays(1).toString())))).isEmpty();
		assertThat(rows(listOrders(f.host(), f.adminToken(), Map.of("to", day.minusDays(1).toString())))).isEmpty();
		assertThat(listOrders(f.host(), f.adminToken(),
				Map.of("from", day.plusDays(1).toString(), "to", day.toString())).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(listOrders(f.host(), f.adminToken(), Map.of("from", "29-10-2026")).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void statusAndMethodFiltersFollowTheSlipLifecycleAndPaginateTheFilteredList() {
		SlipFixture f = seedTenantWithOrder("hub-orders-status");
		OrderResponse unpaid = createAnotherOrder(f, "hub-orders-status-2");
		uploadSlipOrFail(f.host(), f.studentToken(), f.order().id(), "REF-HUB-ORDER", pdfFile("slip.pdf"));

		List<AdminOrderRowResponse> underReview = rows(
				listOrders(f.host(), f.adminToken(), Map.of("status", "UNDER_REVIEW")));
		assertThat(underReview).extracting(AdminOrderRowResponse::orderId).containsExactly(f.order().id());
		assertThat(underReview.get(0).method()).isEqualTo(PaymentMethod.MANUAL_SLIP);
		assertThat(rows(listOrders(f.host(), f.adminToken(), Map.of("method", "MANUAL_SLIP"))))
			.extracting(AdminOrderRowResponse::orderId)
			.containsExactly(f.order().id());
		assertThat(rows(listOrders(f.host(), f.adminToken(), Map.of("status", "UNPAID"))))
			.extracting(AdminOrderRowResponse::orderId)
			.containsExactly(unpaid.id());
		HttpResult<PageResponse<AdminOrderRowResponse>> secondPage = listOrders(f.host(), f.adminToken(),
				Map.of("status", "UNPAID", "page", "1", "size", "1"));
		assertThat(secondPage.getBody().data().content()).isEmpty();
		assertThat(secondPage.getBody().data().totalElements()).isEqualTo(1L);
		assertThat(listOrders(f.host(), f.adminToken(), Map.of("status", "SETTLED")).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void orderListIsStaffOnlyAndReadOnlyAuditorsMayViewIt() {
		SlipFixture f = seedTenantWithOrder("hub-orders-roles");
		seedTenantUser(f.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditorToken = loginAndGetToken(f.host(), "auditor@example.test");
		String teacherToken = loginAndGetToken(f.host(), "teacher@example.test");

		assertThat(listOrders(f.host(), auditorToken, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listOrders(f.host(), f.studentToken(), Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listOrders(f.host(), teacherToken, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void orderListNeverShowsAnotherTenantsOrdersEvenForTheSameOrderNumber() {
		SlipFixture tenantA = seedTenantWithOrder("hub-orders-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("hub-orders-xt-b");

		List<UUID> bAll = rows(listOrders(tenantB.host(), tenantB.adminToken(), Map.of())).stream()
			.map(AdminOrderRowResponse::orderId)
			.toList();
		List<UUID> bByAsNumber = rows(listOrders(tenantB.host(), tenantB.adminToken(),
				Map.of("orderNumber", tenantA.order().displayOrderNumber()))).stream()
			.map(AdminOrderRowResponse::orderId)
			.toList();

		assertThat(bAll).containsExactly(tenantB.order().id());
		assertThat(bByAsNumber).doesNotContain(tenantA.order().id());
	}

	@Test
	void adminOrderDetailIsStaffOnlyAndAnotherTenantsOrderIs404() {
		SlipFixture tenantA = seedTenantWithOrder("hub-detail-xt-a");
		SlipFixture tenantB = seedTenantWithOrder("hub-detail-xt-b");

		assertThat(adminDetailStatus(tenantA.host(), tenantA.adminToken(), tenantA.order().id()))
			.isEqualTo(HttpStatus.OK);
		assertThat(adminDetailStatus(tenantB.host(), tenantB.adminToken(), tenantA.order().id()))
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(adminDetailStatus(tenantA.host(), tenantA.adminToken(), UUID.randomUUID()))
			.isEqualTo(HttpStatus.NOT_FOUND);
		// The order's own student uses GET /orders/{id}; the staff view is never theirs.
		assertThat(adminDetailStatus(tenantA.host(), tenantA.studentToken(), tenantA.order().id()))
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------

	private HttpResult<PageResponse<AdminOrderRowResponse>> listOrders(String host, String token,
			Map<String, String> params) {
		MockHttpServletRequestBuilder builder = get("/api/v1/ledger/orders");
		params.forEach(builder::param);
		return parsePage(perform(authenticated(builder, host, token)), AdminOrderRowResponse.class);
	}

	private static List<AdminOrderRowResponse> rows(HttpResult<PageResponse<AdminOrderRowResponse>> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		return result.getBody().data().content();
	}

	private HttpStatus adminDetailStatus(String host, String token, UUID orderId) {
		return HttpStatus.valueOf(perform(authenticated(get("/api/v1/orders/{id}/admin-detail", orderId), host, token))
			.getResponse()
			.getStatus());
	}

}

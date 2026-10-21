package com.lms.paymentmanagement.manualpayment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.web.GlobalExceptionHandler;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.enrollmentmanagement.api.EntitlementGrantApi;
import com.lms.enrollmentmanagement.repository.CourseParticipationRepository;
import com.lms.enrollmentmanagement.repository.FreeCardRepository;
import com.lms.enrollmentmanagement.service.FreeCardService;
import com.lms.enrollmentmanagement.service.LessonAccessService;
import com.lms.enrollmentmanagement.web.FreeCardController;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.identityaccessservice.service.PermissionCheckServiceImpl;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.checkout.service.CheckoutComposerService;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService;
import com.lms.paymentmanagement.manualpayment.repository.ManualPaymentReceiptRepository;
import com.lms.paymentmanagement.manualpayment.web.ManualPaymentController;
import com.lms.paymentmanagement.order.repository.StudentOrderCourseChargeRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import com.lms.paymentmanagement.refund.repository.PaymentRefundRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentLookupApi;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * ADR-030 (M2, F1, acceptance L): HTTP-level permission behaviour of the
 * manual payment and Free Card endpoints through real MVC dispatch, the real
 * {@code GlobalExceptionHandler}, the REAL permission matrix and the real
 * services (collaborators mocked). The services' own permission checks are
 * what deny here (standalone MockMvc has no method security); the
 * controller-level {@code @PreAuthorize} expressions are pinned separately.
 */
@ExtendWith(MockitoExtension.class)
class ManualPaymentAndFreeCardPermissionWebTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID PROFILE = UUID.randomUUID();

	private static final UUID STUDENT = UUID.randomUUID();

	private static final UUID COURSE = UUID.randomUUID();

	@Mock
	private ManualPaymentSupport support;

	@Mock
	private ManualPaymentReceiptRepository receiptRepository;

	@Mock
	private CheckoutComposerService composerService;

	@Mock
	private PaymentRepository paymentRepository;

	@Mock
	private LedgerEntryApi ledgerEntryApi;

	@Mock
	private EntitlementGrantApi entitlementGrantApi;

	@Mock
	private DiscountRedemptionService discountRedemptionService;

	@Mock
	private StudentOrderLineRepository lineRepository;

	@Mock
	private StudentOrderRepository orderRepository;

	@Mock
	private StudentOrderCourseChargeRepository chargeRepository;

	@Mock
	private PaymentSlipRepository slipRepository;

	@Mock
	private PaymentRefundRepository refundRepository;

	@Mock
	private AuditLogApi auditLogApi;

	@Mock
	private StudentLookupApi studentLookupApi;

	@Mock
	private ContentCoverageApi contentCoverageApi;

	@Mock
	private CourseLookupApi courseLookupApi;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private FreeCardRepository freeCardRepository;

	@Mock
	private CourseParticipationRepository participationRepository;

	@Mock
	private LessonAccessService lessonAccessService;

	@Mock
	private TenantConfigApi tenantConfigApi;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		PermissionCheckServiceImpl permissions = new PermissionCheckServiceImpl();
		ManualPaymentService manualPaymentService = new ManualPaymentService(support, receiptRepository,
				composerService, paymentRepository, ledgerEntryApi, entitlementGrantApi, discountRedemptionService,
				lineRepository, permissions, auditLogApi, studentLookupApi, tenantContext, chargeRepository);
		ManualPaymentQueryService queryService = new ManualPaymentQueryService(support, permissions, studentLookupApi,
				contentCoverageApi, courseLookupApi, composerService, orderRepository, lineRepository,
				paymentRepository, slipRepository, refundRepository, receiptRepository, userProvisioningApi);
		FreeCardService freeCardService = new FreeCardService(freeCardRepository, participationRepository,
				lessonAccessService, courseLookupApi, studentLookupApi, userProvisioningApi, permissions, auditLogApi,
				tenantConfigApi, tenantContext);
		mockMvc = MockMvcBuilders
			.standaloneSetup(new ManualPaymentController(manualPaymentService, queryService),
					new FreeCardController(freeCardService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
		lenient().when(tenantContext.getTenantId()).thenReturn(TENANT);
		lenient().when(support.requireStudentUserId(PROFILE)).thenReturn(STUDENT);
		lenient().when(orderRepository.findAllByStudentId(STUDENT)).thenReturn(List.of());
		lenient().when(freeCardRepository.findPage(any(), any(), any(), any(), any()))
			.thenReturn(new PageImpl<>(List.of()));
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	private static void as(String role) {
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT, role, UUID.randomUUID()));
	}

	private static final String RECORD_BODY = """
			{"items":[{"courseId":"%s","scope":"MONTHLY","year":2026,"month":8}],"quoteToken":"t",
			 "expectedReceivedAmount":"3000.00","paymentMethod":"CASH","receivedOn":"2026-08-01",
			 "idempotencyKey":"%s"}""";

	private static final String ISSUE_BODY = """
			{"studentId":"%s","courseId":"%s","cardType":"FULL","validFrom":"2026-08-01","validTo":"2026-08-31",
			 "reason":"scholarship","idempotencyKey":"%s"}""";

	private String recordBody() {
		return RECORD_BODY.formatted(COURSE, UUID.randomUUID());
	}

	private String issueBody() {
		return ISSUE_BODY.formatted(PROFILE, COURSE, UUID.randomUUID());
	}

	@Test
	void studentIsForbiddenEverywhere() throws Exception {
		as("STUDENT");
		String base = "/api/v1/staff/students/" + PROFILE;
		mockMvc.perform(get(base + "/payment-context")).andExpect(status().isForbidden());
		mockMvc.perform(get(base + "/payment-history").param("courseId", COURSE.toString()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(base + "/payment-offers").param("courseId", COURSE.toString()).param("scope", "MONTHLY"))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(base + "/manual-payments/quote").contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\":[]}")).andExpect(status().isForbidden());
		mockMvc.perform(post(base + "/manual-payments").contentType(MediaType.APPLICATION_JSON).content(recordBody()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/free-cards")).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/free-cards").contentType(MediaType.APPLICATION_JSON).content(issueBody()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/free-cards/" + UUID.randomUUID() + "/revoke")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
		verifyNoInteractions(support, composerService, paymentRepository, ledgerEntryApi, freeCardRepository,
				participationRepository);
	}

	@Test
	void financeStaffMayRecordButNeverIssueOrRevokeAFreeCard() throws Exception {
		as("FINANCE_STAFF");
		UUID unknown = UUID.randomUUID();
		lenient().when(support.requireStudentUserId(unknown)).thenThrow(new NotFoundException("Student not found"));

		// Past the APPROVE gate: the (unknown) student resolution answers 404, not 403.
		mockMvc.perform(post("/api/v1/staff/students/" + unknown + "/manual-payments")
			.contentType(MediaType.APPLICATION_JSON)
			.content(recordBody())).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/free-cards").contentType(MediaType.APPLICATION_JSON).content(issueBody()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false));
		mockMvc.perform(post("/api/v1/free-cards/" + UUID.randomUUID() + "/revoke")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/free-cards")).andExpect(status().isOk());
		verifyNoInteractions(participationRepository, auditLogApi);
	}

	@Test
	void studentSupportIsViewOnly() throws Exception {
		as("STUDENT_SUPPORT");
		String base = "/api/v1/staff/students/" + PROFILE;
		mockMvc.perform(get(base + "/payment-history").param("courseId", COURSE.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray());
		mockMvc.perform(post(base + "/manual-payments").contentType(MediaType.APPLICATION_JSON).content(recordBody()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(base + "/manual-payments/quote").contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\":[]}")).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/v1/free-cards")).andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/free-cards").contentType(MediaType.APPLICATION_JSON).content(issueBody()))
			.andExpect(status().isForbidden());
		verifyNoInteractions(composerService, paymentRepository, ledgerEntryApi);
	}

	@Test
	void tenantAdminMayIssueAFreeCardPastThePermissionGate() throws Exception {
		as("TENANT_ADMIN");
		UUID unknownProfile = UUID.randomUUID();
		lenient().when(studentLookupApi.resolveUserId(unknownProfile)).thenReturn(Optional.empty());

		mockMvc.perform(post("/api/v1/free-cards").contentType(MediaType.APPLICATION_JSON)
			.content(ISSUE_BODY.formatted(unknownProfile, COURSE, UUID.randomUUID())))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/free-cards").contentType(MediaType.APPLICATION_JSON)
			.content(ISSUE_BODY.formatted(PROFILE, COURSE, UUID.randomUUID()).replace("FULL", "HALF")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.error.code").value("FREE_CARD_TYPE_UNSUPPORTED"));
	}

	@Test
	void controllerPreAuthorizeExpressionsArePinned() {
		assertPreAuthorize(ManualPaymentController.class, "PAYMENTS_SLIPS", "VIEW", "APPROVE", 5);
		assertPreAuthorize(FreeCardController.class, "FREE_CARDS", "VIEW", null, 3);
		assertThat(preAuthorizeOf(FreeCardController.class, "issue"))
			.isEqualTo("@permissionCheckService.hasPermission('FREE_CARDS', 'CREATE_EDIT')");
		assertThat(preAuthorizeOf(FreeCardController.class, "revoke"))
			.isEqualTo("@permissionCheckService.hasPermission('FREE_CARDS', 'DELETE')");
	}

	private static void assertPreAuthorize(Class<?> controller, String area, String readAction, String writeAction,
			int endpoints) {
		int count = 0;
		for (Method method : controller.getDeclaredMethods()) {
			boolean isGet = method.isAnnotationPresent(GetMapping.class);
			boolean isPost = method.isAnnotationPresent(PostMapping.class);
			if (!isGet && !isPost) {
				continue;
			}
			count++;
			PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
			assertThat(preAuthorize).as(method.getName()).isNotNull();
			if (isGet) {
				assertThat(preAuthorize.value()).as(method.getName())
					.isEqualTo("@permissionCheckService.hasPermission('" + area + "', '" + readAction + "')");
			}
			else if (writeAction != null) {
				assertThat(preAuthorize.value()).as(method.getName())
					.isEqualTo("@permissionCheckService.hasPermission('" + area + "', '" + writeAction + "')");
			}
		}
		assertThat(count).isEqualTo(endpoints);
	}

	private static String preAuthorizeOf(Class<?> controller, String name) {
		for (Method method : controller.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return method.getAnnotation(PreAuthorize.class).value();
			}
		}
		throw new AssertionError(name);
	}

}

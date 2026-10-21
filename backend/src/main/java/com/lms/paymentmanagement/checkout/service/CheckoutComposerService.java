package com.lms.paymentmanagement.checkout.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CheckoutAmount;
import com.lms.coursemanagement.api.CourseCatalogInfo;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.ModulePriceInfo;
import com.lms.enrollmentmanagement.api.ContentCoverageApi;
import com.lms.enrollmentmanagement.api.ContentCoverageApi.CallerCoverage;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.integrationmanagement.api.PaymentGatewayAccountApi;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.BankDetails;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCart;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CourseCharge;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.DeliveryDetails;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCourse;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutCourseTag;
import com.lms.coursemanagement.api.CourseTagRef;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOffers;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutOrder;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CheckoutQuote;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.ItemSelection;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.Methods;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.OfferItem;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.QuoteLine;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.AppliedDiscountView;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.CodeResult;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.RewardMonth;
import com.lms.paymentmanagement.checkout.service.CheckoutViews.RewardOffer;
import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.StudentOrderDiscount;
import com.lms.paymentmanagement.discount.service.DiscountCheckoutService;
import com.lms.paymentmanagement.discount.service.DiscountCodeAttemptLimiter;
import com.lms.paymentmanagement.discount.service.DiscountClock;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedReward;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CodeStatus;
import com.lms.paymentmanagement.discount.service.DiscountPricing.DiscountOutcome;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardCourse;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardMonthOffer;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardOfferPlan;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardPlan;
import com.lms.paymentmanagement.discount.service.DiscountPricingService;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService.EvidenceKind;
import com.lms.paymentmanagement.discount.service.RuleSnapshot;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.domain.StudentOrderCourseCharge;
import com.lms.paymentmanagement.order.domain.StudentOrderLine;
import com.lms.paymentmanagement.order.repository.StudentOrderCourseChargeRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderLineRepository;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.service.PaymentStatusApiImpl;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.FacilityFeeSettings;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment composer (KLASS-T4c, plan §4.4, D06/D07/D09/D16/D18/D21,
 * ADR-028): offers, server-side quotes and multi-line orders - since V90
 * spanning up to 20 courses, with per-course facility fees and postage
 * (docs/plans/multi-course-checkout-fees-postage.md).
 * Separate from {@code OrderService#createOrder}, which keeps the legacy
 * single-amount (ONE_TIME "Full Course") path unchanged.
 *
 * <p>Security model:
 * <ul>
 * <li>STUDENT only; tenant from {@link TenantContext}, student from the
 * authenticated principal - neither is ever read from a request.</li>
 * <li>The course must exist in the caller's tenant, not be DRAFT, and the
 * caller must participate in it (direct, legacy enrollment, or via a linked
 * parent) - otherwise 404 (no existence leak).</li>
 * <li>Every requested item is resolved against the server-computed offers;
 * client prices are never read (the request DTO has no price field). Unknown
 * or foreign module/lesson ids, unsupported scopes and malformed items are
 * {@code 422 ITEM_NOT_ELIGIBLE}; covered items {@code ITEM_ALREADY_PAID};
 * items awaiting payment evidence {@code ITEM_PENDING}; duplicates collapse.
 * </li>
 * <li>Order creation re-quotes inside the transaction AFTER taking the shared
 * (tenant, student, coverage) advisory locks, so a concurrent grant or order
 * for the same item is serialized and re-checked.</li>
 * </ul>
 * An order or quote NEVER grants anything; entitlements follow only a verified
 * confirmation or an approved slip ({@code EntitlementGrantApi}).
 */
@Service
public class CheckoutComposerService {

	public static final String FEE_NOTE = "Includes a Facility Fee set by the institute.";

	private static final String STUDENT_ROLE = "STUDENT";

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private static final int MAX_ITEMS = 100;

	private static final int MAX_COURSES = 20;

	/**
	 * DISC-01: upper bound on the (tenant, student, coverage) advisory locks one
	 * order placement takes - the cart's own items (at most {@value #MAX_ITEMS})
	 * plus every month an accepted free-course reward could add. Beyond it the
	 * order is refused (422 {@code ITEM_NOT_ELIGIBLE}), never locked unbounded.
	 */
	static final int MAX_LOCK_KEYS = 240;

	static final String ZERO_TOTAL_MESSAGE = "Your discounts cover the whole amount, and an order with a zero total "
			+ "cannot be placed. Remove a discount or add another item to continue.";

	enum Scope {

		MONTHLY, MODULE, LESSON, COURSE, DAILY, INSTALLMENT

	}

	private final CourseLookupApi courseLookupApi;

	private final ContentCoverageApi contentCoverageApi;

	private final PaymentStatusApiImpl paymentStatusApi;

	private final TenantConfigApi tenantConfigApi;

	private final PaymentGatewayAccountApi paymentGatewayAccountApi;

	private final StudentOrderRepository studentOrderRepository;

	private final StudentOrderLineRepository studentOrderLineRepository;

	private final TenantContext tenantContext;

	private final StudentOrderCourseChargeRepository studentOrderCourseChargeRepository;

	private final StudentLookupApi studentLookupApi;

	private final DiscountCheckoutService discountCheckoutService;

	private final DiscountPricingService discountPricingService;

	private final DiscountRedemptionService discountRedemptionService;

	private final DiscountClock discountClock;

	private final DiscountCodeAttemptLimiter discountCodeAttemptLimiter;

	public CheckoutComposerService(CourseLookupApi courseLookupApi, ContentCoverageApi contentCoverageApi,
			PaymentStatusApiImpl paymentStatusApi, TenantConfigApi tenantConfigApi,
			PaymentGatewayAccountApi paymentGatewayAccountApi, StudentOrderRepository studentOrderRepository,
			StudentOrderLineRepository studentOrderLineRepository, TenantContext tenantContext,
			StudentOrderCourseChargeRepository studentOrderCourseChargeRepository, StudentLookupApi studentLookupApi,
			DiscountCheckoutService discountCheckoutService, DiscountPricingService discountPricingService,
			DiscountRedemptionService discountRedemptionService, DiscountClock discountClock,
			DiscountCodeAttemptLimiter discountCodeAttemptLimiter) {
		this.discountCodeAttemptLimiter = discountCodeAttemptLimiter;
		this.discountCheckoutService = discountCheckoutService;
		this.discountPricingService = discountPricingService;
		this.discountRedemptionService = discountRedemptionService;
		this.discountClock = discountClock;
		this.studentOrderCourseChargeRepository = studentOrderCourseChargeRepository;
		this.studentLookupApi = studentLookupApi;
		this.courseLookupApi = courseLookupApi;
		this.contentCoverageApi = contentCoverageApi;
		this.paymentStatusApi = paymentStatusApi;
		this.tenantConfigApi = tenantConfigApi;
		this.paymentGatewayAccountApi = paymentGatewayAccountApi;
		this.studentOrderRepository = studentOrderRepository;
		this.studentOrderLineRepository = studentOrderLineRepository;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------
	// Offers.
	// ------------------------------------------------------------------

	/** One sellable item with its coverage key (internal; never trusts the client). */
	record Offer(Scope scope, Integer year, Integer month, UUID moduleId, UUID lessonId, String label,
			BigDecimal amount, String currency, UUID billingPeriodId, String coverageKey, String state) {

		OfferItem toItem() {
			return new OfferItem(scope.name(), year, month, moduleId, lessonId, label, amount, state);
		}

	}

	/** Everything needed to price one course for the caller (constant number of reads). */
	private record CourseFacts(CourseCatalogInfo info, List<LessonPolicy> lessons, CallerCoverage coverage,
			Set<String> pendingKeys) {

	}

	@Transactional(readOnly = true)
	public List<CheckoutCourse> listCourses(String scopeParam) {
		requireStudent();
		Scope scope = parseScope(scopeParam);
		Map<UUID, String> offered = new LinkedHashMap<>();
		for (UUID courseId : contentCoverageApi.findCallerCourseIds()) {
			Optional<CourseCatalogInfo> info = courseLookupApi.findCatalogInfo(courseId);
			if (info.isEmpty() || "DRAFT".equals(info.get().status())) {
				continue;
			}
			CourseFacts facts = loadFacts(info.get());
			if (!offersFor(scope, facts).isEmpty()) {
				offered.put(courseId, info.get().name());
			}
		}
		// R2-D6: tags of every offered course in one tenant-scoped read.
		Map<UUID, List<CourseTagRef>> tags = offered.isEmpty() ? Map.of()
				: courseLookupApi.findTagsByCourseIds(offered.keySet());
		List<CheckoutCourse> result = new ArrayList<>();
		offered.forEach((courseId, title) -> result.add(new CheckoutCourse(courseId, title,
				tags.getOrDefault(courseId, List.of())
					.stream()
					.map(tag -> new CheckoutCourseTag(tag.id(), tag.name()))
					.toList())));
		result.sort(Comparator.comparing(CheckoutCourse::title, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(CheckoutCourse::courseId));
		return result;
	}

	@Transactional(readOnly = true)
	public CheckoutOffers offers(UUID courseId, String scopeParam, Integer year) {
		requireStudent();
		Scope scope = parseScope(scopeParam);
		CourseCatalogInfo info = requireParticipatedCourse(courseId);
		if (scope == Scope.DAILY || scope == Scope.INSTALLMENT) {
			return new CheckoutOffers(scope.name(), courseId, info.currency(), false, List.of(), List.of());
		}
		CourseFacts facts = loadFacts(info);
		List<Offer> offers = offersFor(scope, facts);
		TreeSet<Integer> years = new TreeSet<>();
		offers.stream().map(Offer::year).filter(y -> y != null).forEach(years::add);
		List<OfferItem> items = offers.stream()
			.filter(offer -> year == null || year.equals(offer.year()))
			.map(Offer::toItem)
			.toList();
		String currency = offers.isEmpty() ? defaultCurrency(scope, info) : offers.get(0).currency();
		return new CheckoutOffers(scope.name(), courseId, currency, true, List.copyOf(years), items);
	}

	// ------------------------------------------------------------------
	// Quote and order.
	// ------------------------------------------------------------------

	/**
	 * Server-side quote of a (possibly multi-course) cart. Every course must
	 * be a non-DRAFT course of the caller's tenant the caller participates in
	 * (else 404); see {@link #price} for the item, currency, facility-fee and
	 * postage rules. A quote never persists or grants anything.
	 */
	@Transactional(readOnly = true)
	public CheckoutQuote quote(CheckoutCart cart) {
		AuthenticatedPrincipal principal = requireStudent();
		requireCodeAttemptAllowed(cart, principal);
		Priced priced = price(cart, requireCartCourses(cart));
		recordCodeAttempt(priced, principal);
		return toQuote(priced);
	}

	/**
	 * DISC-01 code probing (owner decision 2026-10-21): 429 {@code
	 * DISCOUNT_CODE_RATE_LIMITED} when the caller (tenant + student from the
	 * trusted context) has used up its INVALID-code allowance and enters a code
	 * again. Carts without a code are never limited.
	 */
	private void requireCodeAttemptAllowed(CheckoutCart cart, AuthenticatedPrincipal principal) {
		if (DiscountPricingService.normalizeCode(cart.discountCode()) != null) {
			discountCodeAttemptLimiter.requireNotLimited(tenantContext.getTenantId(), principal.userId());
		}
	}

	/** Counts an attempt whose code resolved (student-facing) to INVALID; 429 beyond the limit. */
	private void recordCodeAttempt(Priced priced, AuthenticatedPrincipal principal) {
		if (priced.codeResult() != null && CodeStatus.INVALID.name().equals(priced.codeResult().status())) {
			discountCodeAttemptLimiter.recordInvalid(tenantContext.getTenantId(), principal.userId());
		}
	}

	/**
	 * Re-quotes server-side and persists one order + its immutable lines +
	 * one immutable {@code student_order_course_charge} per course, in this
	 * one transaction. {@code quoteToken} (required, else 422 {@code
	 * QUOTE_REQUIRED}) must equal the token of the server re-quote taken
	 * AFTER the coverage locks, else 409 {@code QUOTE_CHANGED} carrying the
	 * fresh quote, and nothing is written. {@code idempotencyKey} (optional)
	 * replays the caller's earlier order for the same key unchanged (same
	 * advisory-lock pattern as {@code OrderService#createOrder}), even if
	 * prices/config changed since; a key already used for different courses or
	 * a legacy order is 409.
	 */
	@Transactional
	public CheckoutOrder createOrder(CheckoutCart cart, String quoteToken, UUID idempotencyKey) {
		AuthenticatedPrincipal principal = requireStudent();
		UUID tenantId = tenantContext.getTenantId();
		if (idempotencyKey != null) {
			studentOrderRepository.acquireIdempotencyLock(tenantId + ":" + principal.userId() + ":" + idempotencyKey);
			Optional<StudentOrder> existing = studentOrderRepository.findByStudentIdAndIdempotencyKey(
					principal.userId(), idempotencyKey);
			if (existing.isPresent()) {
				StudentOrder order = existing.get();
				List<StudentOrderLine> lines = order.isLineOrder()
						? studentOrderLineRepository.findAllByOrderId(order.getId()) : List.of();
				List<StudentOrderCourseCharge> charges = order.isLineOrder()
						? studentOrderCourseChargeRepository.findAllByOrderId(order.getId()) : List.of();
				if (!order.isLineOrder() || !sameSelection(lines, charges, cart)) {
					throw new ConflictException("This idempotency key was already used for a different order");
				}
				return toOrderView(order, lines, charges, true);
			}
		}
		requireCodeAttemptAllowed(cart, principal);
		Map<UUID, CourseCatalogInfo> courses = requireCartCourses(cart);

		// Lock every well-formed requested coverage of every course plus every
		// month an accepted free-course reward could add (the lock
		// implementation takes them in sorted order), then (DISC-01, D12) the
		// rules that could apply (capped or not), in id order - then price, which
		// re-reads coverage, pending state, rule capacity and the
		// one-use-per-student cap - see class javadoc. The number of lock keys
		// is bounded (MAX_LOCK_KEYS, fail closed).
		Set<String> lockKeys = new TreeSet<>(requestedKeys(cart));
		lockKeys.addAll(discountCheckoutService.rewardLockKeys(cart.acceptedRewardRuleIds(),
				discountClock.currentMonth(), MAX_LOCK_KEYS));
		if (lockKeys.size() > MAX_LOCK_KEYS) {
			throw new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE,
					"Too many items (including free-course months) in one order");
		}
		contentCoverageApi.lockCallerCoverage(lockKeys);
		discountCheckoutService.lockRulesForCart(courses.keySet(), cart.discountCode());
		Priced priced = price(cart, courses);
		recordCodeAttempt(priced, principal);
		if (quoteToken == null || quoteToken.isBlank()) {
			throw new CheckoutItemException(CheckoutItemException.QUOTE_REQUIRED,
					"Review the quote for this payment before placing the order");
		}
		if (!MessageDigest.isEqual(priced.quoteToken().getBytes(StandardCharsets.UTF_8),
				quoteToken.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
			throw new QuoteChangedException(toQuote(priced));
		}
		if (priced.blockingIssue() != null) {
			// D3: no zero-total order (and so no new activation path).
			throw new CheckoutItemException(CheckoutItemException.ORDER_TOTAL_ZERO, ZERO_TOTAL_MESSAGE);
		}

		DeliveryDetails delivery = priced.deliveryRequired() ? priced.delivery() : null;
		StudentOrder order = studentOrderRepository.save(StudentOrder.forLines(tenantId, principal.userId(),
				priced.courseId(), priced.currency(), priced.subtotal(), priced.facilityFeeTotal(),
				priced.postageTotal(), priced.discountTotal(), priced.feePolicySnapshot(), idempotencyKey,
				delivery == null ? null
						: new StudentOrder.DeliverySnapshot(delivery.recipientName(), delivery.addressLine(),
								delivery.city(), delivery.phone())));
		Instant now = Instant.now();
		// DISC-01 snapshot + RESERVED redemptions (same transaction as the order).
		Map<UUID, UUID> orderDiscountIds = discountCheckoutService.persistOrderDiscounts(tenantId, order.getId(),
				principal.userId(), priced.applied(), now);
		List<StudentOrderLine> lines = new ArrayList<>();
		for (PricedLine pricedLine : priced.pricedLines()) {
			Offer offer = pricedLine.offer();
			QuoteLine quoteLine = pricedLine.line();
			UUID orderDiscountId = pricedLine.discountRule() == null || quoteLine.discountAmount().signum() == 0
					? null : orderDiscountIds.get(pricedLine.discountRule().ruleId());
			lines.add(studentOrderLineRepository.save(new StudentOrderLine(tenantId, order.getId(),
					quoteLine.lineNo(), offer.scope().name(), pricedLine.courseId(), offer.moduleId(),
					offer.lessonId(), offer.year(), offer.month(), offer.billingPeriodId(), offer.label(),
					quoteLine.unitAmount(), quoteLine.facilityFee(), quoteLine.discountAmount(), offer.currency(),
					offer.coverageKey(), now, orderDiscountId, pricedLine.rewardRuleId() != null)));
		}
		requireDiscountInvariants(priced.discountTotal(), priced.applied(), lines, orderDiscountIds);
		List<StudentOrderCourseCharge> charges = new ArrayList<>();
		for (CourseCharge charge : priced.courses()) {
			charges.add(studentOrderCourseChargeRepository.save(new StudentOrderCourseCharge(tenantId, order.getId(),
					charge.courseId(), charge.currency(), priced.feeBasis(), charge.facilityFeeSource(),
					charge.effectiveFacilityFee(), charge.facilityFee(), charge.postageSelected(), charge.postageFee(),
					now)));
		}
		return toOrderView(order, lines, charges, false);
	}

	/**
	 * DISC-01 invariant, checked before the placement transaction commits (fail
	 * closed - a mismatch is a bug, never a silently wrong order): the order's
	 * discount equals the sum of its lines' {@code discount_amount}, and each
	 * {@code student_order_discount.amount} equals the sum of the lines linked
	 * to it.
	 */
	static void requireDiscountInvariants(BigDecimal discountTotal, List<AppliedDiscount> applied,
			List<StudentOrderLine> lines, Map<UUID, UUID> orderDiscountIds) {
		BigDecimal lineTotal = ZERO;
		Map<UUID, BigDecimal> bySnapshot = new HashMap<>();
		for (StudentOrderLine line : lines) {
			BigDecimal discount = line.getDiscountAmount() == null ? ZERO : line.getDiscountAmount();
			lineTotal = lineTotal.add(discount);
			if (line.getOrderDiscountId() != null) {
				bySnapshot.merge(line.getOrderDiscountId(), discount, BigDecimal::add);
			}
		}
		if (lineTotal.compareTo(discountTotal) != 0) {
			throw new IllegalStateException("Order discount does not equal the sum of its line discounts");
		}
		for (AppliedDiscount discount : applied) {
			UUID snapshotId = orderDiscountIds.get(discount.rule().ruleId());
			if (snapshotId == null
					|| bySnapshot.getOrDefault(snapshotId, ZERO).compareTo(discount.amount()) != 0) {
				throw new IllegalStateException(
						"Applied discount does not equal the sum of its lines for rule " + discount.rule().ruleId());
			}
		}
	}

	/**
	 * Validates the cart shape and every course in it: the default course
	 * (when given) and each item's course must be a non-DRAFT course of the
	 * caller's tenant the caller participates in - otherwise 404 (covers
	 * foreign-tenant and unknown ids without leaking existence). At most
	 * {@value #MAX_ITEMS} items and {@value #MAX_COURSES} distinct courses.
	 * Returns the courses in first-appearance order.
	 */
	private Map<UUID, CourseCatalogInfo> requireCartCourses(CheckoutCart cart) {
		if (cart.defaultCourseId() != null) {
			requireParticipatedCourse(cart.defaultCourseId());
		}
		List<ItemSelection> items = cart.items();
		if (items.isEmpty()) {
			throw new CheckoutItemException(CheckoutItemException.EMPTY_SELECTION, "Select at least one item to pay for");
		}
		if (items.size() > MAX_ITEMS) {
			throw new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE, "Too many items in one order");
		}
		Set<UUID> courseIds = new LinkedHashSet<>();
		for (ItemSelection item : items) {
			UUID courseId = courseOf(cart, item);
			if (courseId == null) {
				throw new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE,
						"One of the selected items has no course");
			}
			courseIds.add(courseId);
		}
		if (courseIds.size() > MAX_COURSES) {
			throw new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE,
					"Too many courses in one order (at most " + MAX_COURSES + ")");
		}
		Map<UUID, CourseCatalogInfo> courses = new LinkedHashMap<>();
		for (UUID courseId : courseIds) {
			courses.put(courseId, requireParticipatedCourse(courseId));
		}
		return courses;
	}

	/** An item's own course, else the cart default; {@code null} for a null item or when neither is given. */
	private static UUID courseOf(CheckoutCart cart, ItemSelection item) {
		if (item == null) {
			return cart.defaultCourseId();
		}
		return item.courseId() != null ? item.courseId() : cart.defaultCourseId();
	}

	/**
	 * Idempotent replay guard: the stored order must have been placed for the
	 * same selection - same courses, same sorted coverage keys and same
	 * postage courses. Anything else with the same key is a 409, never a
	 * silent replay of a different purchase.
	 */
	private static boolean sameSelection(List<StudentOrderLine> lines, List<StudentOrderCourseCharge> charges,
			CheckoutCart cart) {
		// DISC-01: reward lines ADDED by an accepted free-course offer were not
		// requested items - they (and a course only they belong to) are ignored.
		Set<String> requested = requestedKeys(cart);
		Set<UUID> orderCourses = new TreeSet<>();
		Set<String> orderKeys = new TreeSet<>();
		lines.forEach(line -> {
			if (line.isReward() && !requested.contains(line.getCoverageKey())) {
				return;
			}
			orderCourses.add(line.getCourseId());
			orderKeys.add(line.getCoverageKey());
		});
		Set<UUID> orderPostage = new TreeSet<>();
		charges.stream()
			.filter(StudentOrderCourseCharge::isPostageSelected)
			.forEach(charge -> orderPostage.add(charge.getCourseId()));
		Set<UUID> requestedPostage = new TreeSet<>();
		cart.postageCourseIds().stream().filter(Objects::nonNull).forEach(requestedPostage::add);
		return orderCourses.equals(requestedCourseIds(cart)) && orderKeys.equals(requested)
				&& orderPostage.equals(requestedPostage);
	}

	private static Set<UUID> requestedCourseIds(CheckoutCart cart) {
		Set<UUID> ids = new TreeSet<>();
		cart.items().forEach(item -> {
			UUID courseId = courseOf(cart, item);
			if (courseId != null) {
				ids.add(courseId);
			}
		});
		return ids;
	}

	/** The distinct courses of a line order's lines (empty for a legacy order). */
	@Transactional(readOnly = true)
	public Set<UUID> lineCourseIds(StudentOrder order) {
		Set<UUID> courseIds = new LinkedHashSet<>();
		studentOrderLineRepository.findAllByOrderId(order.getId()).forEach(line -> courseIds.add(line.getCourseId()));
		return courseIds;
	}

	/**
	 * Guard for paying an EXISTING line order (payment initiation / slip
	 * upload): 409 when any of its items is now covered for the caller or is
	 * awaiting payment evidence in ANOTHER order. A no-op for legacy orders.
	 * Unlocked pre-check only (before any gateway call / storage write); the
	 * authoritative check is {@link #lockAndRequireNewLineOrderEvidenceAllowed}.
	 */
	@Transactional(readOnly = true)
	public void requireLineOrderStillPayable(StudentOrder order) {
		List<StudentOrderLine> lines = studentOrderLineRepository.findAllByOrderId(order.getId());
		if (lines.isEmpty()) {
			return;
		}
		requireLinesStillPayable(order, lines);
	}

	/**
	 * Unlocked pre-check before NEW payment evidence (a new PENDING gateway
	 * payment or a slip) is created for a line order: {@link
	 * #requireLineOrderStillPayable} plus "this order has no PENDING/CONFIRMED
	 * payment and no open/approved slip". A no-op for legacy orders.
	 */
	@Transactional(readOnly = true)
	public void requireNewLineOrderEvidenceAllowed(StudentOrder order) {
		List<StudentOrderLine> lines = studentOrderLineRepository.findAllByOrderId(order.getId());
		if (lines.isEmpty()) {
			return;
		}
		requireNoEvidenceOnOrder(order);
		requireLinesStillPayable(order, lines);
		discountRedemptionService.requireHoldRetakable(order.getId());
	}

	/**
	 * KLASS-T8b (D24, payment review H-1): the authoritative guard, run INSIDE
	 * the transaction that inserts the new PENDING payment / slip of a line
	 * order. Takes the same sorted (tenant, student, coverage) advisory locks as
	 * order creation ({@link #createOrder}) and the entitlement grant, then
	 * re-checks: the order itself has no PENDING/CONFIRMED payment and no
	 * SUBMITTED/UNDER_REVIEW/APPROVED slip (409 {@code
	 * ORDER_PAYMENT_IN_PROGRESS} / {@code ITEM_ALREADY_PAID}), no line is
	 * covered ({@code ITEM_ALREADY_PAID}) and no line awaits evidence in another
	 * order ({@code ITEM_PENDING}). Rejected/failed evidence never blocks. Two
	 * concurrent payment starts for the same item therefore serialize on the
	 * lock and the second one sees the first one's committed PENDING row. A
	 * no-op for legacy orders. An idempotent replay of the same gateway payment
	 * is resolved by the caller BEFORE this runs.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockAndRequireNewLineOrderEvidenceAllowed(StudentOrder order, EvidenceKind evidenceKind) {
		List<StudentOrderLine> lines = studentOrderLineRepository.findAllByOrderId(order.getId());
		if (lines.isEmpty()) {
			return;
		}
		Set<String> keys = new TreeSet<>();
		lines.forEach(line -> keys.add(line.getCoverageKey()));
		contentCoverageApi.lockCallerCoverage(keys);
		requireNoEvidenceOnOrder(order);
		requireLinesStillPayable(order, lines);
		// DISC-01 (D12): re-take a lapsed discount hold after re-validation (409
		// DISCOUNT_NO_LONGER_AVAILABLE); a gateway payment holds for the gateway
		// TTL, a bank slip holds open-ended while it is open.
		discountRedemptionService.holdForEvidence(order.getId(), evidenceKind);
	}

	private void requireNoEvidenceOnOrder(StudentOrder order) {
		PaymentStatusApiImpl.OrderEvidence evidence = paymentStatusApi.findOrderEvidence(order.getId());
		if (evidence.confirmed() || evidence.approvedSlip()) {
			throw new CheckoutItemException(HttpStatus.CONFLICT, CheckoutItemException.ITEM_ALREADY_PAID,
					"This order has already been paid");
		}
		if (evidence.pending() || evidence.openSlip()) {
			throw new CheckoutItemException(HttpStatus.CONFLICT, CheckoutItemException.ORDER_PAYMENT_IN_PROGRESS,
					"A payment for this order is already in progress");
		}
	}

	/** Checked per course of the order (a V90 line order may span several courses). */
	private void requireLinesStillPayable(StudentOrder order, List<StudentOrderLine> lines) {
		Map<UUID, List<StudentOrderLine>> linesByCourse = new LinkedHashMap<>();
		lines.forEach(line -> linesByCourse.computeIfAbsent(line.getCourseId(), key -> new ArrayList<>()).add(line));
		linesByCourse.forEach((courseId, courseLines) -> {
			CallerCoverage coverage = contentCoverageApi.coverageForCaller(courseId);
			Set<String> pending = paymentStatusApi.findPendingCoverageKeysExcludingOrder(order.getStudentId(),
					courseId, order.getId());
			for (StudentOrderLine line : courseLines) {
				if (coverage.activeCoverageKeys().contains(line.getCoverageKey())
						|| (coverage.legacyCovered() && !"LESSON".equals(line.getScope()))) {
					throw new CheckoutItemException(HttpStatus.CONFLICT, CheckoutItemException.ITEM_ALREADY_PAID,
							"'" + line.getLabel() + "' is already paid for - this order can no longer be paid");
				}
				if (pending.contains(line.getCoverageKey())) {
					throw new CheckoutItemException(HttpStatus.CONFLICT, CheckoutItemException.ITEM_PENDING,
							"'" + line.getLabel() + "' already has a payment awaiting confirmation in another order");
				}
			}
		});
	}

	// ------------------------------------------------------------------
	// Pricing.
	// ------------------------------------------------------------------

	/**
	 * One selected (or reward) offer with the course it belongs to and its
	 * priced line. {@code rewardRuleId} = the free-course rule the line is a
	 * reward of; {@code discountRule} = the rule whose discount the line
	 * carries ({@code null} when undiscounted).
	 */
	private record PricedLine(UUID courseId, Offer offer, QuoteLine line, UUID rewardRuleId,
			RuleSnapshot discountRule) {

	}

	/**
	 * A fully priced cart. {@code courseId} is the single course of the cart
	 * ({@code null} when it spans several); {@code delivery} is the on-record
	 * address ({@code null} when nothing is on record). DISC-01: {@code
	 * applied} = the engine's applied discounts (allocation per line), plus
	 * their student views, the code result, reward offers, notices and the
	 * zero-total blocking issue.
	 */
	private record Priced(UUID courseId, String currency, List<PricedLine> pricedLines, List<QuoteLine> lines,
			List<CourseCharge> courses, BigDecimal subtotal, BigDecimal facilityFeeTotal, BigDecimal postageTotal,
			BigDecimal discountTotal, BigDecimal total, String feeBasis, String feeNote, String feePolicySnapshot,
			boolean deliveryRequired, DeliveryDetails delivery, String quoteToken, List<AppliedDiscount> applied,
			List<AppliedDiscountView> discounts, CodeResult codeResult, List<RewardOffer> rewardOffers,
			List<String> notices, String blockingIssue) {

	}

	/** A line before its discount is known: course, offer, number, facility fee. */
	private record FeeLine(UUID courseId, String courseTitle, Offer offer, int lineNo, BigDecimal facilityFee) {

	}

	/**
	 * Prices a cart (plan §2; DISC-01 spec §2):
	 * <ul>
	 * <li>Items resolve against the server-computed offers of their own course
	 * (an item's {@code courseId}, else the cart default); duplicates collapse
	 * by coverage key; covered = {@code ITEM_ALREADY_PAID}, awaiting evidence =
	 * {@code ITEM_PENDING}, anything else unknown = {@code ITEM_NOT_ELIGIBLE}.
	 * Lines are grouped by course (first-appearance order).</li>
	 * <li>One currency per order, else {@code 422 CURRENCY_MISMATCH} naming the
	 * courses and currencies. Collection routing is one account per tenant (the
	 * tenant's ACTIVE gateway account, or bank slip), so currency is the only
	 * possible incompatibility between two courses of the same tenant.</li>
	 * <li>DISC-01 phase 1: accepted free-course rewards add MONTHLY reward lines
	 * of the reward course (appended after that course's lines, or as a new
	 * course group) BEFORE fees; selected lines matching a reward month are
	 * converted in place.</li>
	 * <li>Facility fee per tenant mode: NONE 0; PER_COURSE the course's
	 * effective fee once, on its first line; PER_LINE the course's effective
	 * fee on every line; PER_ORDER the tenant default once, on line 1
	 * (overrides not applied). Effective fee = course override when NOT NULL,
	 * else tenant default.</li>
	 * <li>Postage: once per selected course ({@code POSTAGE_COURSE_NOT_IN_CART}
	 * for a course with no item); a 0 postage fee is selected but charges 0.
	 * Any postage requires the on-record billing address ({@code
	 * DELIVERY_DETAILS_REQUIRED}). Postage never creates a line or an
	 * entitlement.</li>
	 * <li>DISC-01 phase 3: discounts on unit amounts only (never fees or
	 * postage), one per line; {@code total = subtotal + fees + postage -
	 * discountTotal}; a 0 total is flagged {@code ORDER_TOTAL_ZERO} (D3).</li>
	 * </ul>
	 */
	private Priced price(CheckoutCart cart, Map<UUID, CourseCatalogInfo> courses) {
		FacilityFeeSettings fee = FacilityFeeSettings.from(paymentConfig());
		Map<UUID, Map<String, Offer>> offersByCourse = new HashMap<>();
		for (CourseCatalogInfo info : courses.values()) {
			CourseFacts facts = loadFacts(info);
			Map<String, Offer> offersByKey = new LinkedHashMap<>();
			for (Scope scope : List.of(Scope.MONTHLY, Scope.MODULE, Scope.LESSON)) {
				offersFor(scope, facts).forEach(offer -> offersByKey.put(offer.coverageKey(), offer));
			}
			offersByCourse.put(info.courseId(), offersByKey);
		}

		Set<String> seenKeys = new HashSet<>();
		Map<UUID, List<Offer>> selectedByCourse = new LinkedHashMap<>();
		for (ItemSelection item : cart.items()) {
			UUID courseId = courseOf(cart, item);
			String key = coverageKeyOf(courseId, item)
				.orElseThrow(() -> new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE,
						"One of the selected items cannot be purchased here"));
			if (!seenKeys.add(key)) {
				continue;
			}
			Offer offer = offersByCourse.getOrDefault(courseId, Map.of()).get(key);
			if (offer == null) {
				throw new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE,
						"One of the selected items cannot be purchased for this course");
			}
			if ("PAID".equals(offer.state())) {
				throw new CheckoutItemException(CheckoutItemException.ITEM_ALREADY_PAID,
						"'" + offer.label() + "' is already paid for");
			}
			if ("PENDING".equals(offer.state())) {
				throw new CheckoutItemException(CheckoutItemException.ITEM_PENDING,
						"'" + offer.label() + "' already has a payment awaiting confirmation");
			}
			selectedByCourse.computeIfAbsent(courseId, id -> new ArrayList<>()).add(offer);
		}
		// Keep the courses' first-appearance order.
		Map<UUID, List<Offer>> byCourse = new LinkedHashMap<>();
		courses.keySet().forEach(courseId -> {
			List<Offer> offers = selectedByCourse.get(courseId);
			if (offers != null && !offers.isEmpty()) {
				byCourse.put(courseId, offers);
			}
		});
		String currency = requireSingleCurrency(byCourse, courses);

		// DISC-01 phase 1 (before fees): candidate rules and free-course rewards.
		LocalDate today = discountClock.today();
		YearMonth currentMonth = YearMonth.from(today);
		DiscountCheckoutService.Candidates candidates = discountCheckoutService.candidates(currency,
				cart.discountCode(), AuthenticatedPrincipalHolder.get().userId(), today, discountClock.now());
		Map<UUID, CourseCatalogInfo> allCourses = new LinkedHashMap<>(courses);
		Map<UUID, RewardCourse> rewardCourses = rewardCourses(candidates.freeRules(), currency, allCourses,
				offersByCourse);
		List<CartLine> baseLines = new ArrayList<>();
		byCourse.forEach((courseId, offers) -> offers.forEach(offer -> baseLines.add(new CartLine(0,
				offer.coverageKey(), courseId, offer.scope().name(), offer.year(), offer.month(), offer.amount()))));
		RewardPlan plan = discountPricingService.planRewards(candidates.freeRules(), baseLines, rewardCourses,
				new LinkedHashSet<>(cart.acceptedRewardRuleIds()), currentMonth);
		Map<String, UUID> rewardKeys = plan.rewardKeys();
		for (AppliedReward reward : plan.applied()) {
			for (RewardMonthOffer month : reward.addedMonths()) {
				Offer offer = offersByCourse.get(month.courseId()).get(month.key());
				byCourse.computeIfAbsent(month.courseId(), id -> new ArrayList<>()).add(offer);
			}
		}

		Set<UUID> postageCourses = new LinkedHashSet<>();
		for (UUID courseId : cart.postageCourseIds()) {
			if (courseId == null || !byCourse.containsKey(courseId)) {
				throw new CheckoutItemException(CheckoutItemException.POSTAGE_COURSE_NOT_IN_CART,
						"Postage can only be added for a course that has items in this payment");
			}
			postageCourses.add(courseId);
		}

		List<FeeLine> feeLines = new ArrayList<>();
		List<CourseCharge> charges = new ArrayList<>();
		BigDecimal subtotal = ZERO;
		BigDecimal feeTotal = ZERO;
		BigDecimal postageTotal = ZERO;
		int lineNo = 1;
		for (Map.Entry<UUID, List<Offer>> entry : byCourse.entrySet()) {
			UUID courseId = entry.getKey();
			CourseCatalogInfo info = allCourses.get(courseId);
			BigDecimal effective = fee.effectiveFor(info.facilityFeeOverride());
			BigDecimal courseSubtotal = ZERO;
			BigDecimal courseFee = ZERO;
			List<Offer> offers = entry.getValue();
			for (int i = 0; i < offers.size(); i++) {
				Offer offer = offers.get(i);
				BigDecimal lineFee = switch (fee.mode()) {
					case FacilityFeeSettings.PER_COURSE -> i == 0 ? effective : ZERO;
					case FacilityFeeSettings.PER_LINE -> effective;
					case FacilityFeeSettings.PER_ORDER -> lineNo == 1 ? fee.amount() : ZERO;
					default -> ZERO;
				};
				feeLines.add(new FeeLine(courseId, info.name(), offer, lineNo++, lineFee));
				courseSubtotal = courseSubtotal.add(offer.amount());
				courseFee = courseFee.add(lineFee);
			}
			BigDecimal postageFee = money(info.postageFee());
			boolean postageSelected = postageCourses.contains(courseId);
			BigDecimal postageCharge = postageSelected ? postageFee : ZERO;
			charges.add(new CourseCharge(courseId, info.name(), currency, courseSubtotal, courseFee, effective,
					fee.sourceFor(info.facilityFeeOverride()), postageFee, postageSelected, postageCharge));
			subtotal = subtotal.add(courseSubtotal);
			feeTotal = feeTotal.add(courseFee);
			postageTotal = postageTotal.add(postageCharge);
		}

		// DISC-01 phase 3: discounts on the final, numbered lines.
		List<CartLine> finalLines = feeLines.stream()
			.map(line -> new CartLine(line.lineNo(), line.offer().coverageKey(), line.courseId(),
					line.offer().scope().name(), line.offer().year(), line.offer().month(), line.offer().amount(),
					rewardKeys.get(line.offer().coverageKey())))
			.toList();
		DiscountOutcome outcome = discountPricingService.applyDiscounts(finalLines, plan, candidates.valueRules(),
				candidates.codeRuleId(), currentMonth);
		Map<Integer, BigDecimal> discountByLine = new HashMap<>();
		Map<Integer, RuleSnapshot> ruleByLine = new HashMap<>();
		for (AppliedDiscount applied : outcome.applied()) {
			applied.allocation().forEach((no, amount) -> {
				discountByLine.put(no, amount);
				ruleByLine.put(no, applied.rule());
			});
		}
		List<PricedLine> pricedLines = new ArrayList<>();
		List<QuoteLine> lines = new ArrayList<>();
		for (FeeLine feeLine : feeLines) {
			Offer offer = feeLine.offer();
			BigDecimal unit = offer.amount();
			BigDecimal discount = discountByLine.getOrDefault(feeLine.lineNo(), ZERO);
			RuleSnapshot rule = ruleByLine.get(feeLine.lineNo());
			UUID rewardRuleId = rewardKeys.get(offer.coverageKey());
			QuoteLine line = new QuoteLine(feeLine.lineNo(), offer.scope().name(), offer.label(), offer.year(),
					offer.month(), offer.moduleId(), offer.lessonId(), unit, feeLine.facilityFee(),
					unit.add(feeLine.facilityFee()).subtract(discount), feeLine.courseId(), feeLine.courseTitle(),
					discount, rewardRuleId != null, rule == null ? null : rule.name());
			lines.add(line);
			pricedLines.add(new PricedLine(feeLine.courseId(), offer, line, rewardRuleId, rule));
		}
		BigDecimal discountTotal = outcome.total();
		BigDecimal total = subtotal.add(feeTotal).add(postageTotal).subtract(discountTotal);

		boolean deliveryRequired = !postageCourses.isEmpty();
		DeliveryDetails delivery = deliveryOnRecord();
		if (deliveryRequired && (delivery == null || delivery.addressLine() == null || delivery.city() == null)) {
			throw new CheckoutItemException(CheckoutItemException.DELIVERY_DETAILS_REQUIRED,
					"Add your delivery address (address and city) to your billing details before choosing postage");
		}

		List<AppliedDiscountView> discountViews = outcome.applied()
			.stream()
			.map(applied -> new AppliedDiscountView(applied.rule().ruleId(), applied.rule().versionId(),
					applied.rule().type().name(), applied.rule().name(),
					applied.rule().type() == DiscountType.DISCOUNT_CODE ? applied.rule().code() : null,
					applied.amount(), applied.lineNos(),
					applied.qualification().rewardCourseIds().isEmpty() ? null
							: applied.qualification().rewardCourseIds().get(0),
					applied.qualification().rewardCourseIds(), discountPricingService.explanation(applied.rule())))
			.toList();
		CodeResult codeResult = codeResult(candidates, outcome);
		List<RewardOffer> rewardOffers = rewardOffers(plan, fee, allCourses, byCourse);
		String blockingIssue = total.signum() == 0 ? CheckoutItemException.ORDER_TOTAL_ZERO : null;

		UUID singleCourse = byCourse.size() == 1 ? byCourse.keySet().iterator().next() : null;
		String token = quoteToken(singleCourse, currency, fee, pricedLines, charges, subtotal, feeTotal, postageTotal,
				discountTotal, total, deliveryRequired ? delivery : null, outcome, candidates.code(), blockingIssue);
		return new Priced(singleCourse, currency, List.copyOf(pricedLines), List.copyOf(lines), List.copyOf(charges),
				subtotal, feeTotal, postageTotal, discountTotal, total, fee.mode(),
				feeTotal.signum() > 0 ? FEE_NOTE : null, fee.snapshot(), deliveryRequired, delivery, token,
				outcome.applied(), discountViews, codeResult, rewardOffers, plan.notices(), blockingIssue);
	}

	/**
	 * DISC-01: the reward courses of the candidate free-course rules as seen by
	 * the caller. A course is eligible only when it is a non-DRAFT course of the
	 * caller's tenant the caller participates in (the composer's normal rule for
	 * any line), MONTHLY-priced; only its AVAILABLE months in the cart currency
	 * are offered. Eligible reward courses are added to {@code allCourses} and
	 * their MONTHLY offers to {@code offersByCourse}.
	 */
	private Map<UUID, RewardCourse> rewardCourses(List<RuleSnapshot> freeRules, String currency,
			Map<UUID, CourseCatalogInfo> allCourses, Map<UUID, Map<String, Offer>> offersByCourse) {
		Map<UUID, RewardCourse> result = new HashMap<>();
		for (RuleSnapshot rule : freeRules) {
			for (UUID courseId : rule.rewardCourseIds()) {
				if (!result.containsKey(courseId)) {
					result.put(courseId, rewardCourse(courseId, currency, allCourses, offersByCourse));
				}
			}
		}
		return result;
	}

	private RewardCourse rewardCourse(UUID courseId, String currency, Map<UUID, CourseCatalogInfo> allCourses,
			Map<UUID, Map<String, Offer>> offersByCourse) {
		CourseCatalogInfo info = allCourses.get(courseId);
		if (info == null) {
			Optional<CourseCatalogInfo> found = courseLookupApi.findCatalogInfo(courseId)
				.filter(course -> !"DRAFT".equals(course.status()));
			if (found.isEmpty()) {
				return new RewardCourse(courseId, null, false, List.of());
			}
			info = found.get();
			if (!contentCoverageApi.isCallerParticipant(courseId)) {
				return new RewardCourse(courseId, info.name(), false,
						info.available() && "MONTHLY".equals(info.pricingModel()), List.of());
			}
		}
		if (!"MONTHLY".equals(info.pricingModel())) {
			return new RewardCourse(courseId, info.name(), false, List.of());
		}
		Map<String, Offer> offers = offersByCourse.get(courseId);
		if (offers == null) {
			offers = new LinkedHashMap<>();
			for (Offer offer : monthlyOffers(loadFacts(info))) {
				offers.put(offer.coverageKey(), offer);
			}
			offersByCourse.put(courseId, offers);
		}
		List<RewardMonthOffer> months = offers.values()
			.stream()
			.filter(offer -> offer.scope() == Scope.MONTHLY && "AVAILABLE".equals(offer.state())
					&& currency.equals(offer.currency()) && offer.year() != null && offer.month() != null)
			.map(offer -> new RewardMonthOffer(courseId, offer.year(), offer.month(), offer.coverageKey(),
					offer.amount(), offer.label()))
			.toList();
		allCourses.putIfAbsent(courseId, info);
		return new RewardCourse(courseId, info.name(), true, months);
	}

	/**
	 * The entered code's status: the gate failure when it could not apply at
	 * all, else the engine's verdict - always the STUDENT-FACING status (owner
	 * decision 2026-10-21): a disabled, expired or not-yet-valid code is
	 * {@code INVALID} with the same message as an unknown code, so a real but
	 * unusable code cannot be told apart from a guess. A code the student
	 * already used is {@code NOT_ELIGIBLE} (one use per student per rule).
	 */
	private static CodeResult codeResult(DiscountCheckoutService.Candidates candidates, DiscountOutcome outcome) {
		if (candidates.code() == null) {
			return null;
		}
		CodeStatus internal = candidates.codeGateStatus() != null ? candidates.codeGateStatus()
				: outcome.codeStatus() != null ? outcome.codeStatus() : CodeStatus.INVALID;
		CodeStatus status = internal.studentFacing();
		String message = internal == CodeStatus.ALREADY_USED ? "You have already used this discount."
				: switch (status) {
					case APPLIED -> "Discount code applied.";
					case NOT_ELIGIBLE -> "Your selection does not meet this code's conditions.";
					case USAGE_LIMIT_REACHED -> "This discount code has reached its usage limit.";
					case NOT_APPLIED_BETTER_DISCOUNT -> "A better discount is already applied to these items.";
					case INVALID, EXPIRED, DISABLED, NOT_YET_VALID, ALREADY_USED -> "This discount code is not valid.";
				};
		return new CodeResult(candidates.code(), status.name(), message);
	}

	/** Not-yet-accepted free months with their value and the facility fee they would add. */
	private static List<RewardOffer> rewardOffers(RewardPlan plan, FacilityFeeSettings fee,
			Map<UUID, CourseCatalogInfo> allCourses, Map<UUID, List<Offer>> byCourse) {
		List<RewardOffer> offers = new ArrayList<>();
		for (RewardOfferPlan offerPlan : plan.offers()) {
			CourseCatalogInfo info = allCourses.get(offerPlan.courseId());
			if (info == null) {
				continue;
			}
			BigDecimal effective = fee.effectiveFor(info.facilityFeeOverride());
			int count = offerPlan.months().size();
			BigDecimal offerFee = switch (fee.mode()) {
				case FacilityFeeSettings.PER_LINE -> effective.multiply(BigDecimal.valueOf(count));
				case FacilityFeeSettings.PER_COURSE -> byCourse.containsKey(offerPlan.courseId()) ? ZERO : effective;
				default -> ZERO;
			};
			BigDecimal value = offerPlan.months()
				.stream()
				.map(RewardMonthOffer::unitAmount)
				.reduce(ZERO, BigDecimal::add);
			List<RewardMonth> months = offerPlan.months()
				.stream()
				.map(month -> new RewardMonth(month.year(), month.month(), month.label()))
				.toList();
			offers.add(new RewardOffer(offerPlan.rule().ruleId(), offerPlan.rule().name(), offerPlan.courseId(),
					offerPlan.courseTitle(), months, value, money(offerFee),
					"Get " + count + (count == 1 ? " month" : " months") + " of \"" + offerPlan.courseTitle()
							+ "\" free with \"" + offerPlan.rule().name() + "\"."));
		}
		return List.copyOf(offers);
	}
	private static String requireSingleCurrency(Map<UUID, List<Offer>> byCourse,
			Map<UUID, CourseCatalogInfo> courses) {
		Map<UUID, Set<String>> currenciesByCourse = new LinkedHashMap<>();
		Set<String> all = new TreeSet<>();
		byCourse.forEach((courseId, offers) -> offers.forEach(offer -> {
			currenciesByCourse.computeIfAbsent(courseId, id -> new TreeSet<>()).add(offer.currency());
			all.add(offer.currency());
		}));
		if (all.size() > 1) {
			StringBuilder detail = new StringBuilder();
			currenciesByCourse.forEach((courseId, currencies) -> {
				if (!detail.isEmpty()) {
					detail.append(", ");
				}
				detail.append(courses.get(courseId).name()).append(" (").append(String.join("/", currencies))
					.append(')');
			});
			throw new CheckoutItemException(CheckoutItemException.CURRENCY_MISMATCH,
					"Items in one payment must share a currency: " + detail + ". Please pay for them separately.");
		}
		return all.iterator().next();
	}

	/**
	 * SHA-256 (hex) of a canonical rendering of the whole breakdown: lines
	 * (course, coverage key, amounts), per-course charges, totals, currency,
	 * fee basis/snapshot and - when postage is selected - the delivery
	 * address. DISC-01 ({@code v2}): also every line's discount, reward flag
	 * and discount rule/version, every applied discount (rule, version, amount,
	 * lines), the entered code and the blocking issue. Any server-side change
	 * between quote and order changes it.
	 */
	private static String quoteToken(UUID courseId, String currency, FacilityFeeSettings fee, List<PricedLine> lines,
			List<CourseCharge> charges, BigDecimal subtotal, BigDecimal feeTotal, BigDecimal postageTotal,
			BigDecimal discountTotal, BigDecimal total, DeliveryDetails delivery, DiscountOutcome outcome, String code,
			String blockingIssue) {
		StringBuilder canonical = new StringBuilder("v2");
		field(canonical, "course", courseId);
		field(canonical, "currency", currency);
		field(canonical, "basis", fee.mode());
		field(canonical, "policy", fee.snapshot());
		for (PricedLine priced : lines) {
			QuoteLine line = priced.line();
			field(canonical, "line", line.lineNo() + "," + line.courseId() + "," + line.scope() + ","
					+ line.unitAmount().toPlainString() + "," + line.facilityFee().toPlainString());
			field(canonical, "key", priced.offer().coverageKey());
			field(canonical, "discount", line.discountAmount().toPlainString() + "," + line.reward() + ","
					+ (priced.discountRule() == null ? ""
							: priced.discountRule().ruleId() + "," + priced.discountRule().versionId()));
		}
		for (CourseCharge charge : charges) {
			field(canonical, "charge", charge.courseId() + "," + charge.subtotal().toPlainString() + ","
					+ charge.facilityFee().toPlainString() + "," + charge.effectiveFacilityFee().toPlainString() + ","
					+ charge.facilityFeeSource() + "," + charge.postageFee().toPlainString() + ","
					+ charge.postageSelected() + "," + charge.postageCharge().toPlainString());
		}
		for (AppliedDiscount applied : outcome.applied()) {
			field(canonical, "applied", applied.rule().ruleId() + "," + applied.rule().versionId() + ","
					+ applied.amount().toPlainString() + "," + applied.lineNos());
		}
		field(canonical, "code", code);
		field(canonical, "blocking", blockingIssue);
		field(canonical, "totals", subtotal.toPlainString() + "," + feeTotal.toPlainString() + ","
				+ postageTotal.toPlainString() + "," + discountTotal.toPlainString() + "," + total.toPlainString());
		if (delivery != null) {
			field(canonical, "name", delivery.recipientName());
			field(canonical, "address", delivery.addressLine());
			field(canonical, "city", delivery.city());
			field(canonical, "phone", delivery.phone());
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
	/** Length-prefixed so no value can be confused with a separator. */
	private static void field(StringBuilder canonical, String name, Object value) {
		String text = value == null ? "" : value.toString();
		canonical.append('|').append(name).append('=').append(text.length()).append(':').append(text);
	}

	/**
	 * The caller's on-record billing address (the existing student billing
	 * address - no separate address book), trimmed and clipped to the order
	 * snapshot column sizes; {@code null} when nothing is on record. {@code
	 * addressLine}/{@code city} are {@code null} when blank.
	 */
	private DeliveryDetails deliveryOnRecord() {
		UUID studentId = AuthenticatedPrincipalHolder.get().userId();
		Optional<StudentContact> contact = studentLookupApi.findContact(studentId);
		if (contact.isEmpty()) {
			return null;
		}
		StudentContact c = contact.get();
		String name = clip(c.name(), 255);
		String address = clip(c.addressLine(), 255);
		String city = clip(c.city(), 100);
		String phone = clip(c.mobile(), 30);
		if (name == null && address == null && city == null && phone == null) {
			return null;
		}
		return new DeliveryDetails(name, address, city, phone);
	}

	private static String clip(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	private Map<String, Object> paymentConfig() {
		return tenantConfigApi.resolveDomain(tenantContext.getTenantId(), ConfigDomain.PAYMENT);
	}

	private CheckoutQuote toQuote(Priced priced) {
		return new CheckoutQuote(priced.courseId(), priced.currency(), priced.lines(), priced.courses(),
				priced.subtotal(), priced.facilityFeeTotal(), priced.postageTotal(), priced.discountTotal(),
				priced.total(), priced.feeBasis(), priced.feeNote(), priced.deliveryRequired(), priced.delivery(),
				priced.quoteToken(), new Methods(paymentGatewayAccountApi.isOnlinePaymentAvailable(), true),
				bankDetails(), priced.discounts(), priced.codeResult(), priced.rewardOffers(), priced.notices(),
				priced.blockingIssue(), priced.blockingIssue() == null ? null : ZERO_TOTAL_MESSAGE);
	}
	private BankDetails bankDetails() {
		Map<String, Object> config = paymentConfig();
		String bank = text(config.get("bank_name"));
		String branch = text(config.get("bank_branch"));
		String holder = text(config.get("bank_account_holder"));
		String number = text(config.get("bank_account_number"));
		if (bank == null && branch == null && holder == null && number == null) {
			return null;
		}
		return new BankDetails(bank, branch, holder, number);
	}

	private static String text(Object value) {
		return value instanceof String s && !s.isBlank() ? s : null;
	}

	// ------------------------------------------------------------------
	// Offer computation (D09).
	// ------------------------------------------------------------------

	private CourseFacts loadFacts(CourseCatalogInfo info) {
		UUID courseId = info.courseId();
		UUID studentId = AuthenticatedPrincipalHolder.get().userId();
		return new CourseFacts(info, courseLookupApi.findLessonPolicies(courseId),
				contentCoverageApi.coverageForCaller(courseId),
				paymentStatusApi.findPendingCoverageKeys(studentId, courseId));
	}

	private List<Offer> offersFor(Scope scope, CourseFacts facts) {
		return switch (scope) {
			case MONTHLY -> monthlyOffers(facts);
			case MODULE -> moduleOffers(facts);
			case LESSON -> lessonOffers(facts);
			case COURSE -> courseOffers(facts);
			case DAILY, INSTALLMENT -> List.of();
		};
	}

	/**
	 * Months = distinct explicit billing (year, month) of the course's
	 * COVERED_BY_CLASS_FEES lessons; amount = the current open MONTHLY billing
	 * period; only for MONTHLY-priced courses with a positive configured amount.
	 */
	private List<Offer> monthlyOffers(CourseFacts facts) {
		CourseCatalogInfo info = facts.info();
		if (!"MONTHLY".equals(info.pricingModel()) || info.monthlyAmount() == null
				|| info.monthlyAmount().signum() <= 0) {
			return List.of();
		}
		TreeSet<Integer> months = new TreeSet<>();
		for (LessonPolicy lesson : facts.lessons()) {
			if (paymentType(lesson) == LessonPaymentType.COVERED_BY_CLASS_FEES && lesson.billingYear() != null
					&& lesson.billingMonth() != null) {
				months.add(lesson.billingYear() * 100 + lesson.billingMonth());
			}
		}
		String currency = info.monthlyCurrency() != null ? info.monthlyCurrency() : info.currency();
		List<Offer> offers = new ArrayList<>();
		for (int packed : months) {
			int year = packed / 100;
			int month = packed % 100;
			String key = CoverageKeys.monthly(info.courseId(), year, month);
			String state = state(facts, key, facts.coverage().legacyCovered());
			offers.add(new Offer(Scope.MONTHLY, year, month, null, null,
					Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + year,
					money(info.monthlyAmount()), currency, info.monthlyBillingPeriodId(), key, state));
		}
		return offers;
	}

	private List<Offer> moduleOffers(CourseFacts facts) {
		CourseCatalogInfo info = facts.info();
		List<Offer> offers = new ArrayList<>();
		for (ModulePriceInfo module : courseLookupApi.findModulePrices(info.courseId())) {
			if (module.price() == null || module.price().signum() <= 0) {
				continue;
			}
			String key = CoverageKeys.module(module.moduleId());
			offers.add(new Offer(Scope.MODULE, null, null, module.moduleId(), null, module.title(),
					money(module.price()), info.currency(), null, key,
					state(facts, key, facts.coverage().legacyCovered())));
		}
		return offers;
	}

	private List<Offer> lessonOffers(CourseFacts facts) {
		CourseCatalogInfo info = facts.info();
		List<Offer> offers = new ArrayList<>();
		for (LessonPolicy lesson : facts.lessons()) {
			if (paymentType(lesson) != LessonPaymentType.PAID || lesson.price() == null
					|| lesson.price().signum() <= 0) {
				continue;
			}
			String key = CoverageKeys.lesson(lesson.lessonId());
			String label = lesson.moduleTitle() == null ? lesson.title() : lesson.moduleTitle() + " - " + lesson.title();
			// Legacy course access never covers a PAID lesson (D05).
			offers.add(new Offer(Scope.LESSON, null, null, lesson.moduleId(), lesson.lessonId(), label,
					money(lesson.price()), info.currency(), null, key, state(facts, key, false)));
		}
		return offers;
	}

	/** The ONE_TIME "Full Course" offer - purchased through the existing {@code POST /api/v1/orders}. */
	private List<Offer> courseOffers(CourseFacts facts) {
		CourseCatalogInfo info = facts.info();
		if (!"ONE_TIME".equals(info.pricingModel())) {
			return List.of();
		}
		Optional<CheckoutAmount> amount = courseLookupApi.getResolvedCheckoutAmount(info.courseId());
		if (amount.isEmpty() || amount.get().amount() == null || amount.get().amount().signum() <= 0) {
			return List.of();
		}
		return List.of(new Offer(Scope.COURSE, null, null, null, null, info.name(), money(amount.get().amount()),
				amount.get().currency(), null, "COURSE:" + info.courseId(),
				facts.coverage().legacyCovered() ? "PAID" : "AVAILABLE"));
	}

	/** Active, non-expired coverage (or legacy) = PAID; awaiting evidence = PENDING; else AVAILABLE (D21). */
	private static String state(CourseFacts facts, String key, boolean coveredByLegacy) {
		if (coveredByLegacy || facts.coverage().activeCoverageKeys().contains(key)) {
			return "PAID";
		}
		if (facts.pendingKeys().contains(key)) {
			return "PENDING";
		}
		return "AVAILABLE";
	}

	private static LessonPaymentType paymentType(LessonPolicy lesson) {
		return lesson.paymentType() == null ? LessonPaymentType.COVERED_BY_CLASS_FEES : lesson.paymentType();
	}

	private static Optional<String> coverageKeyOf(UUID courseId, ItemSelection item) {
		if (item == null || item.scope() == null) {
			return Optional.empty();
		}
		return switch (item.scope()) {
			case "MONTHLY" -> (item.year() != null && item.month() != null && item.year() >= 2000
					&& item.year() <= 2100 && item.month() >= 1 && item.month() <= 12)
							? Optional.of(CoverageKeys.monthly(courseId, item.year(), item.month())) : Optional.empty();
			case "MODULE" -> item.moduleId() != null ? Optional.of(CoverageKeys.module(item.moduleId()))
					: Optional.empty();
			case "LESSON" -> item.lessonId() != null ? Optional.of(CoverageKeys.lesson(item.lessonId()))
					: Optional.empty();
			default -> Optional.empty();
		};
	}

	/** Every well-formed coverage key of the cart, across all its courses, sorted. */
	private static Set<String> requestedKeys(CheckoutCart cart) {
		Set<String> keys = new TreeSet<>();
		if (cart.items().size() <= MAX_ITEMS) {
			cart.items().forEach(item -> {
				UUID courseId = courseOf(cart, item);
				if (courseId != null) {
					coverageKeyOf(courseId, item).ifPresent(keys::add);
				}
			});
		}
		return keys;
	}

	private static String defaultCurrency(Scope scope, CourseCatalogInfo info) {
		if (scope == Scope.MONTHLY && info.monthlyCurrency() != null) {
			return info.monthlyCurrency();
		}
		return info.currency();
	}

	private static BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

	// ------------------------------------------------------------------
	// Guards and mapping.
	// ------------------------------------------------------------------

	private CourseCatalogInfo requireParticipatedCourse(UUID courseId) {
		CourseCatalogInfo info = courseLookupApi.findCatalogInfo(courseId)
			.filter(course -> !"DRAFT".equals(course.status()))
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!contentCoverageApi.isCallerParticipant(courseId)) {
			throw new NotFoundException("Course not found");
		}
		return info;
	}

	private static Scope parseScope(String scope) {
		try {
			return Scope.valueOf(scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new CheckoutItemException(CheckoutItemException.ITEM_NOT_ELIGIBLE, "Unknown payment type");
		}
	}

	private static AuthenticatedPrincipal requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new AccessDeniedException("Only a student may perform this action");
		}
		return principal;
	}

	/**
	 * The persisted order snapshot: lines, per-course charges (pre-V90 line
	 * orders have none - their per-course figures are derived from the lines,
	 * with {@code effectiveFacilityFee}/{@code facilityFeeSource} {@code null}),
	 * postage and delivery. Course titles are read (tenant-scoped) for display.
	 * DISC-01: per-line discount / reward / discount name and the order's
	 * immutable {@code student_order_discount} snapshot - never re-derived from
	 * current rules.
	 */
	private CheckoutOrder toOrderView(StudentOrder order, List<StudentOrderLine> lines,
			List<StudentOrderCourseCharge> charges, boolean replay) {
		Set<UUID> courseIds = new LinkedHashSet<>();
		lines.forEach(line -> courseIds.add(line.getCourseId()));
		Map<UUID, String> titles = new HashMap<>();
		if (!courseIds.isEmpty()) {
			courseLookupApi.getCourseSummaries(courseIds).forEach(summary -> titles.put(summary.id(), summary.name()));
		}
		List<StudentOrderDiscount> snapshots = lines.isEmpty() ? List.of()
				: discountCheckoutService.orderDiscounts(order.getId());
		Map<UUID, StudentOrderDiscount> snapshotById = new HashMap<>();
		snapshots.forEach(snapshot -> snapshotById.put(snapshot.getId(), snapshot));
		List<QuoteLine> quoteLines = lines.stream()
			.map(line -> {
				StudentOrderDiscount snapshot = line.getOrderDiscountId() == null ? null
						: snapshotById.get(line.getOrderDiscountId());
				return new QuoteLine(line.getLineNo(), line.getScope(), line.getLabel(), line.getPeriodYear(),
						line.getPeriodMonth(), line.getModuleId(), line.getLessonId(), line.getUnitAmount(),
						line.getFacilityFeeAmount(), line.getLineTotal(), line.getCourseId(),
						titles.get(line.getCourseId()), line.getDiscountAmount(), line.isReward(),
						snapshot == null ? null : snapshot.getName());
			})
			.toList();
		List<AppliedDiscountView> discounts = new ArrayList<>();
		for (StudentOrderDiscount snapshot : snapshots) {
			List<Integer> lineNos = new ArrayList<>();
			Set<UUID> rewardCourses = new LinkedHashSet<>();
			for (StudentOrderLine line : lines) {
				if (snapshot.getId().equals(line.getOrderDiscountId())) {
					lineNos.add(line.getLineNo());
					if (line.isReward()) {
						rewardCourses.add(line.getCourseId());
					}
				}
			}
			List<UUID> rewardCourseIds = List.copyOf(rewardCourses);
			discounts.add(new AppliedDiscountView(snapshot.getRuleId(), snapshot.getRuleVersionId(),
					snapshot.getDiscountType().name(), snapshot.getName(), snapshot.getCode(), snapshot.getAmount(),
					List.copyOf(lineNos), rewardCourseIds.isEmpty() ? null : rewardCourseIds.get(0), rewardCourseIds,
					DiscountPricingService.explanation(snapshot.getDiscountType(), snapshot.getValueType(),
							snapshot.getValue(), order.getCurrency(), snapshot.getCode(), null)));
		}
		Map<UUID, StudentOrderCourseCharge> chargeByCourse = new HashMap<>();
		charges.forEach(charge -> chargeByCourse.put(charge.getCourseId(), charge));
		List<CourseCharge> courseCharges = new ArrayList<>();
		for (UUID courseId : courseIds) {
			BigDecimal subtotal = ZERO;
			BigDecimal fee = ZERO;
			for (StudentOrderLine line : lines) {
				if (courseId.equals(line.getCourseId())) {
					subtotal = subtotal.add(line.getUnitAmount());
					fee = fee.add(line.getFacilityFeeAmount());
				}
			}
			StudentOrderCourseCharge charge = chargeByCourse.get(courseId);
			courseCharges.add(charge == null
					? new CourseCharge(courseId, titles.get(courseId), order.getCurrency(), subtotal, fee, null, null,
							ZERO, false, ZERO)
					: new CourseCharge(courseId, titles.get(courseId), charge.getCurrency(), subtotal,
							charge.getFacilityFeeAmount(), charge.getEffectiveFacilityFee(),
							charge.getFacilityFeeSource(), charge.getPostageFee(), charge.isPostageSelected(),
							charge.getPostageAmount()));
		}
		StudentOrder.DeliverySnapshot snapshot = order.getDelivery();
		DeliveryDetails delivery = snapshot == null ? null
				: new DeliveryDetails(snapshot.recipientName(), snapshot.addressLine(), snapshot.city(),
						snapshot.phone());
		String policy = order.getFeePolicySnapshot();
		String feeBasis = policy == null ? null : policy.split(":", 2)[0];
		return new CheckoutOrder(order.getId(), order.getStudentId(), order.getCourseId(), order.getAmount(),
				order.getCurrency(), order.getBillingPeriodId(), order.getStatus(), order.getCreatedAt(),
				order.getUpdatedAt(), quoteLines, List.copyOf(courseCharges), order.getSubtotalAmount(),
				order.getFacilityFeeAmount(), order.getPostageAmount() == null ? ZERO : order.getPostageAmount(),
				order.getDiscountAmount(), feeBasis, delivery, replay, List.copyOf(discounts));
	}

}

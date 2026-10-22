package com.lms.ledgersettlementmanagement.service;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.ledgersettlementmanagement.api.InstantRange;
import com.lms.ledgersettlementmanagement.api.LedgerRevenueAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry;
import com.lms.ledgersettlementmanagement.api.ReportScopeTooLargeException;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportCourseAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.ReportLineAllocation;
import com.lms.ledgersettlementmanagement.api.ReportLedgerEntry.SettledAllocation;
import com.lms.ledgersettlementmanagement.api.TeacherIncomeLedgerApi;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlement;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementItem;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherRevenueShareRateRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementItemRepository;
import com.lms.ledgersettlementmanagement.repository.TeacherSettlementRepository;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentFact;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link TeacherIncomeLedgerApi} (RPT-01). Read-only: every read
 * goes through this module's structurally tenant-scoped repositories or
 * another module's {@code api}; nothing is written. Each source is read once
 * per call in id batches of {@link #CHUNK} (never per entry), and the
 * entry / allocation / line / settlement grains are assembled in memory keyed
 * by id, so no SQL join can multiply rows.
 */
@Service
@Transactional(readOnly = true)
public class TeacherIncomeLedgerService implements TeacherIncomeLedgerApi {

	static final int CHUNK = 1000;

	private final LedgerEntryRepository ledgerEntryRepository;

	private final TeacherSettlementItemRepository itemRepository;

	private final TeacherSettlementRepository settlementRepository;

	private final TeacherRevenueShareRateRepository rateRepository;

	private final PaymentStatusApi paymentStatusApi;

	private final CourseLookupApi courseLookupApi;

	public TeacherIncomeLedgerService(LedgerEntryRepository ledgerEntryRepository,
			TeacherSettlementItemRepository itemRepository, TeacherSettlementRepository settlementRepository,
			TeacherRevenueShareRateRepository rateRepository, PaymentStatusApi paymentStatusApi,
			CourseLookupApi courseLookupApi) {
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.itemRepository = itemRepository;
		this.settlementRepository = settlementRepository;
		this.rateRepository = rateRepository;
		this.paymentStatusApi = paymentStatusApi;
		this.courseLookupApi = courseLookupApi;
	}

	@Override
	public List<ReportLedgerEntry> findEntriesCreatedIn(List<InstantRange> windows, int maxEntries) {
		if (windows == null || windows.isEmpty()) {
			return List.of();
		}
		requireWithin(ledgerEntryRepository.countCreatedInAny(windows), maxEntries);
		return enrich(ledgerEntryRepository.findAllCreatedInAny(windows));
	}

	@Override
	public List<ReportLedgerEntry> findEntriesForBillingMonths(Set<YearMonth> months, int maxEntries) {
		if (months == null || months.isEmpty()) {
			return List.of();
		}
		Set<UUID> lessonIds = courseLookupApi.findLessonIdsInBillingMonths(months);
		List<UUID> orderIds = List.copyOf(paymentStatusApi.findOrderIdsWithLinesInMonths(months, lessonIds));
		long inScope = 0;
		for (List<UUID> chunk : chunks(orderIds)) {
			inScope += ledgerEntryRepository.countForOrders(chunk);
			requireWithin(inScope, maxEntries);
		}
		List<LedgerEntry> entries = new ArrayList<>();
		for (List<UUID> chunk : chunks(orderIds)) {
			entries.addAll(ledgerEntryRepository.findAllForOrdersOldestFirst(chunk));
		}
		entries.sort(Comparator.comparing(LedgerEntry::getCreatedAt).thenComparing(LedgerEntry::getId));
		return enrich(entries);
	}

	@Override
	public List<ShareRate> findShareRates(Collection<UUID> teacherIds) {
		if (teacherIds == null || teacherIds.isEmpty()) {
			return List.of();
		}
		return rateRepository.findForTeachers(Set.copyOf(teacherIds))
			.stream()
			.map(rate -> new ShareRate(rate.getTeacherId(), rate.getSharePercent(), rate.getEffectiveFrom()))
			.toList();
	}

	@Override
	public List<PaidSettlement> findPaidSettlements(Collection<UUID> teacherIds, List<InstantRange> paidWindows) {
		if (teacherIds == null || teacherIds.isEmpty() || paidWindows == null || paidWindows.isEmpty()) {
			return List.of();
		}
		return settlementRepository.findPaidInWindows(Set.copyOf(teacherIds), paidWindows)
			.stream()
			.map(s -> new PaidSettlement(s.getId(), s.getTeacherId(), s.getKind().name(), s.getShareAmount(),
					s.getCurrency(), s.getPaidAt()))
			.toList();
	}

	private static void requireWithin(long count, int maxEntries) {
		if (count > maxEntries) {
			throw new ReportScopeTooLargeException(count, maxEntries);
		}
	}

	// ------------------------------------------------------------------
	// Enrichment.
	// ------------------------------------------------------------------

	private List<ReportLedgerEntry> enrich(List<LedgerEntry> entries) {
		if (entries.isEmpty()) {
			return List.of();
		}
		List<UUID> orderIds = entries.stream().map(LedgerEntry::getOrderId).distinct().toList();
		Map<UUID, OrderRevenueBasis> basisByOrder = new HashMap<>();
		Map<UUID, List<OrderLineSnapshot>> linesByOrder = new HashMap<>();
		for (List<UUID> chunk : chunks(orderIds)) {
			paymentStatusApi.findOrderRevenueBases(chunk).forEach(basis -> basisByOrder.put(basis.orderId(), basis));
			paymentStatusApi.findOrderLinesForOrders(chunk)
				.forEach(line -> linesByOrder.computeIfAbsent(line.orderId(), key -> new ArrayList<>()).add(line));
		}
		Set<UUID> lessonIds = new HashSet<>();
		linesByOrder.values()
			.forEach(lines -> lines.stream()
				.filter(line -> "LESSON".equals(line.scope()) && line.lessonId() != null)
				.forEach(line -> lessonIds.add(line.lessonId())));
		Map<UUID, YearMonth> lessonMonths = lessonIds.isEmpty() ? Map.of()
				: courseLookupApi.findLessonBillingMonths(lessonIds);

		List<UUID> paymentIds = entries.stream()
			.map(LedgerEntry::getPaymentId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		Map<UUID, PaymentFact> paymentById = new HashMap<>();
		for (List<UUID> chunk : chunks(paymentIds)) {
			paymentStatusApi.findPaymentFacts(chunk).forEach(fact -> paymentById.put(fact.paymentId(), fact));
		}

		Map<String, SettledAllocation> settledByKey = settledAllocations(
				entries.stream().map(LedgerEntry::getId).toList());

		List<ReportLedgerEntry> result = new ArrayList<>(entries.size());
		for (LedgerEntry entry : entries) {
			OrderRevenueBasis basis = basisByOrder.get(entry.getOrderId());
			PaymentFact payment = entry.getPaymentId() == null ? null : paymentById.get(entry.getPaymentId());
			Map<UUID, List<OrderLineSnapshot>> linesByCourse = new LinkedHashMap<>();
			linesByOrder.getOrDefault(entry.getOrderId(), List.of())
				.forEach(line -> linesByCourse.computeIfAbsent(line.courseId(), key -> new ArrayList<>()).add(line));
			List<ReportCourseAllocation> courses = new ArrayList<>();
			for (LedgerRevenueAllocation allocation : RevenueAllocator.allocate(entry.getAmount(), basis)) {
				List<ReportLineAllocation> lines = splitToLines(allocation,
						linesByCourse.getOrDefault(allocation.courseId(), List.of()), lessonMonths);
				courses.add(new ReportCourseAllocation(allocation.courseId(), allocation.amount(), lines,
						settledByKey.get(TeacherSettlementItemRepository.allocationKey(entry.getId(),
								allocation.courseId()))));
			}
			result.add(new ReportLedgerEntry(entry.getId(), entry.getOrderId(), entry.getPaymentId(),
					entry.getEntryType() == LedgerEntryType.REFUND, entry.getAmount(), entry.getCreatedAt(),
					entry.getReversesEntryId(), payment == null ? null : payment.currency(),
					payment == null ? null : LedgerViewEnrichmentService.deriveMethod(payment.gatewayReference()),
					basis != null && basis.lineOrder(), courses));
		}
		return result;
	}

	private static List<ReportLineAllocation> splitToLines(LedgerRevenueAllocation allocation,
			List<OrderLineSnapshot> courseLines, Map<UUID, YearMonth> lessonMonths) {
		if (courseLines.isEmpty()) {
			return List.of();
		}
		Map<UUID, OrderLineSnapshot> byId = new HashMap<>();
		List<RevenueLineSplitter.LineShare> shares = new ArrayList<>();
		for (OrderLineSnapshot line : courseLines) {
			byId.put(line.lineId(), line);
			shares.add(new RevenueLineSplitter.LineShare(line.lineId(), line.lineNo(), line.courseShare()));
		}
		List<ReportLineAllocation> lines = new ArrayList<>();
		for (RevenueLineSplitter.LinePart part : RevenueLineSplitter.split(allocation.amount(), shares)) {
			OrderLineSnapshot line = byId.get(part.lineId());
			YearMonth month = billingMonthOf(line, lessonMonths);
			lines.add(new ReportLineAllocation(line.lineId(), line.lineNo(), line.scope(),
					month == null ? null : month.getYear(), month == null ? null : month.getMonthValue(),
					part.amount()));
		}
		return lines;
	}

	private static YearMonth billingMonthOf(OrderLineSnapshot line, Map<UUID, YearMonth> lessonMonths) {
		if ("MONTHLY".equals(line.scope()) && line.periodYear() != null && line.periodMonth() != null) {
			return YearMonth.of(line.periodYear(), line.periodMonth());
		}
		if ("LESSON".equals(line.scope()) && line.lessonId() != null) {
			return lessonMonths.get(line.lessonId());
		}
		return null;
	}

	private Map<String, SettledAllocation> settledAllocations(List<UUID> entryIds) {
		List<TeacherSettlementItem> items = new ArrayList<>();
		for (List<UUID> chunk : chunks(entryIds)) {
			items.addAll(itemRepository.findByLedgerEntryIds(chunk));
		}
		if (items.isEmpty()) {
			return Map.of();
		}
		Set<UUID> settlementIds = new LinkedHashSet<>();
		items.forEach(item -> settlementIds.add(item.getSettlementId()));
		Map<UUID, TeacherSettlement> settlements = new HashMap<>();
		for (List<UUID> chunk : chunks(List.copyOf(settlementIds))) {
			settlementRepository.findAllById(chunk).forEach(s -> settlements.put(s.getId(), s));
		}
		Map<String, SettledAllocation> result = new HashMap<>();
		for (TeacherSettlementItem item : items) {
			TeacherSettlement settlement = settlements.get(item.getSettlementId());
			if (settlement == null) {
				continue;
			}
			result.put(TeacherSettlementItemRepository.allocationKey(item.getLedgerEntryId(), item.getCourseId()),
					new SettledAllocation(settlement.getId(), settlement.getTeacherId(), settlement.getSharePercent(),
							item.getAmount(), settlement.getStatus().name()));
		}
		return result;
	}

	private static <T> List<List<T>> chunks(List<T> ids) {
		List<List<T>> result = new ArrayList<>();
		for (int from = 0; from < ids.size(); from += CHUNK) {
			result.add(ids.subList(from, Math.min(ids.size(), from + CHUNK)));
		}
		return result;
	}

}

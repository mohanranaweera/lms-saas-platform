package com.lms.ledgersettlementmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.tenant.TenantContext;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import com.lms.ledgersettlementmanagement.repository.LedgerEntryRepository;
import com.lms.ledgersettlementmanagement.service.LedgerEntryService;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito-only unit coverage for {@link LedgerEntryService}'s sign convention
 * - the plan's own explicitly-named "needs a test" item: {@code REFUND}
 * entries carry a NEGATIVE amount, {@code PAYMENT_CONFIRMED} entries carry a
 * POSITIVE amount. Asserts the actual numeric sign, not merely {@code
 * entry_type}.
 */
@ExtendWith(MockitoExtension.class)
class LedgerEntryServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private LedgerEntryRepository ledgerEntryRepository;

	@Mock
	private TenantContext tenantContext;

	private LedgerEntryService service;

	@BeforeEach
	void setUp() {
		service = new LedgerEntryService(ledgerEntryRepository, tenantContext);
	}

	@Test
	void recordPaymentConfirmedStoresAPositiveAmountWithNoReversalLink() {
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(ledgerEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		UUID orderId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		BigDecimal amount = new BigDecimal("120.00");

		service.recordPaymentConfirmed(orderId, paymentId, amount);

		ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).save(captor.capture());
		LedgerEntry saved = captor.getValue();
		assertThat(saved.getEntryType()).isEqualTo(LedgerEntryType.PAYMENT_CONFIRMED);
		assertThat(saved.getAmount()).isEqualByComparingTo(amount);
		assertThat(saved.getAmount().signum()).isEqualTo(1);
		assertThat(saved.getReversesEntryId()).isNull();
		assertThat(saved.getOrderId()).isEqualTo(orderId);
		assertThat(saved.getPaymentId()).isEqualTo(paymentId);
		assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
	}

	@Test
	void recordRefundStoresANegativeAmountAndLinksBackToTheConfirmedEntryItReverses() {
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(ledgerEntryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		UUID orderId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID confirmedEntryId = UUID.randomUUID();
		LedgerEntry confirmedEntry = new LedgerEntry(TENANT_ID, orderId, paymentId, LedgerEntryType.PAYMENT_CONFIRMED,
				new BigDecimal("120.00"), null);
		setId(confirmedEntry, confirmedEntryId);
		when(ledgerEntryRepository.findConfirmedEntryForPayment(paymentId)).thenReturn(Optional.of(confirmedEntry));
		BigDecimal refundAmount = new BigDecimal("30.00");

		service.recordRefund(orderId, paymentId, refundAmount);

		ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(ledgerEntryRepository).save(captor.capture());
		LedgerEntry saved = captor.getValue();
		assertThat(saved.getEntryType()).isEqualTo(LedgerEntryType.REFUND);
		// The load-bearing assertion: the sign is negated relative to the
		// positive amount the caller passed in.
		assertThat(saved.getAmount()).isEqualByComparingTo(refundAmount.negate());
		assertThat(saved.getAmount().signum()).isEqualTo(-1);
		assertThat(saved.getReversesEntryId()).isEqualTo(confirmedEntryId);
	}

	@Test
	void recordRefundThrowsWhenNoConfirmedEntryExistsForThePayment() {
		UUID paymentId = UUID.randomUUID();
		when(ledgerEntryRepository.findConfirmedEntryForPayment(paymentId)).thenReturn(Optional.empty());

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> service.recordRefund(UUID.randomUUID(), paymentId, new BigDecimal("10.00")))
			.isInstanceOf(IllegalStateException.class);
	}

	private static void setId(BaseEntity entity, UUID id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}

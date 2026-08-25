package com.lms.paymentmanagement.slip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.service.OrderService;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Mockito-only unit coverage for {@link SlipUploadService} (MVP-011/SLIP-1),
 * matching {@code MaterialServiceTest}'s established style. Isolates this
 * service's own validation-before-any-write branching (size, content-sniff,
 * ownership, storage-failure-never-orphans-a-row) without a Spring context;
 * the real HTTP-layer equivalent lives in {@code SlipUploadIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SlipUploadServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final long MAX_FILE_SIZE_BYTES = 1_000_000L;

	@Mock
	private OrderService orderService;

	@Mock
	private PaymentSlipRepository paymentSlipRepository;

	@Mock
	private PaymentSlipFlagRepository paymentSlipFlagRepository;

	@Mock
	private ObjectStorageApi slipStorageApi;

	@Mock
	private SlipDuplicateCheckService slipDuplicateCheckService;

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private TenantContext tenantContext;

	private SlipUploadService slipUploadService;

	@BeforeEach
	void setUp() {
		slipUploadService = new SlipUploadService(orderService, paymentSlipRepository, paymentSlipFlagRepository,
				slipStorageApi, slipDuplicateCheckService, userProvisioningApi, tenantContext, MAX_FILE_SIZE_BYTES);
	}

	@Test
	void uploadSlipWithAValidPdfPersistsAndRunsDuplicateChecks() {
		UUID orderId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		StudentOrder order = new StudentOrder(TENANT_ID, studentId, UUID.randomUUID(), new BigDecimal("99.99"),
				"USD");
		ReflectionTestUtils.setField(order, "id", orderId);
		when(orderService.loadOrderOwnedByCurrentStudent(orderId)).thenReturn(order);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(slipStorageApi.store(any(StoreObjectCommand.class)))
			.thenReturn(new StoredObject("object-key-123", 123L));
		AtomicReference<PaymentSlip> savedSlip = new AtomicReference<>();
		when(paymentSlipRepository.save(any(PaymentSlip.class))).thenAnswer(invocation -> {
			PaymentSlip slip = invocation.getArgument(0);
			ReflectionTestUtils.setField(slip, "id", UUID.randomUUID());
			savedSlip.set(slip);
			return slip;
		});
		when(paymentSlipRepository.findById(any())).thenAnswer(invocation -> Optional.of(savedSlip.get()));
		MockMultipartFile file = new MockMultipartFile("file", "slip.pdf", "application/pdf", validPdfBytes());

		PaymentSlipView view = slipUploadService.uploadSlip(orderId, "REF-123", file);

		assertThat(view.referenceNumber()).isEqualTo("REF-123");
		assertThat(view.orderId()).isEqualTo(orderId);
		assertThat(view.studentId()).isEqualTo(studentId);
		verify(orderService).loadOrderOwnedByCurrentStudent(orderId);
		verify(slipStorageApi, times(1)).store(any(StoreObjectCommand.class));
		verify(paymentSlipRepository, times(1)).save(any(PaymentSlip.class));
		verify(slipDuplicateCheckService, times(1)).runChecksAndAdvance(savedSlip.get().getId());
	}

	/**
	 * Mirrors {@code MaterialServiceTest}'s finding-1 regression test: the
	 * size-limit rejection path must abort as soon as the running byte count
	 * crosses {@code maxFileSizeBytes}, WITHOUT first reading (and therefore
	 * buffering) the rest of an oversized file. {@link BoundedRejectingInputStream}
	 * only ever hands out a small, generous slack margin of bytes past {@code
	 * MAX_FILE_SIZE_BYTES} before throwing; if {@code SlipUploadService} still
	 * tried to read the "whole file" first, it would keep asking this stream
	 * for more bytes past that slack margin, and this test would fail with
	 * the stream's own {@link AssertionError} instead of the expected {@link
	 * PayloadTooLargeException}.
	 */
	@Test
	void uploadSlipWithAFileExceedingTheMaxSizeNeverBuffersTheFullOversizedFileBeforeRejecting() throws Exception {
		UUID orderId = UUID.randomUUID();
		long allowedBeforeExplosion = MAX_FILE_SIZE_BYTES + 65_536L;
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream()).thenReturn(new BoundedRejectingInputStream(allowedBeforeExplosion));

		assertThatThrownBy(() -> slipUploadService.uploadSlip(orderId, "REF-OVERSIZE", file))
			.isInstanceOf(PayloadTooLargeException.class);

		verifyNoInteractions(slipStorageApi);
		verify(paymentSlipRepository, never()).save(any());
		verifyNoInteractions(slipDuplicateCheckService);
	}

	@Test
	void uploadSlipWithBytesFailingContentSnifferThrowsUnsupportedMediaTypeAndNeverCallsStore() {
		UUID orderId = UUID.randomUUID();
		byte[] mzHeader = { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00 };
		MockMultipartFile file = new MockMultipartFile("file", "disguised.pdf", "application/pdf", mzHeader);

		assertThatThrownBy(() -> slipUploadService.uploadSlip(orderId, "REF-DISGUISED", file))
			.isInstanceOf(UnsupportedMediaTypeException.class);

		verifyNoInteractions(slipStorageApi);
		verify(paymentSlipRepository, never()).save(any());
		verifyNoInteractions(slipDuplicateCheckService);
	}

	/**
	 * Review round 4 item 1: the friendly pre-check backing {@code
	 * uq_payment_slip_tenant_order_active} must reject BEFORE any
	 * storage/DB write is attempted, with a specific, actionable message -
	 * never let a second active-slip upload fall through to a generic
	 * {@code DataIntegrityViolationException}-mapped 409.
	 */
	@Test
	void uploadSlipWhenTheOrderAlreadyHasAnActiveSlipRejectsBeforeAnyStorageOrDbWrite() {
		UUID orderId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		StudentOrder order = new StudentOrder(TENANT_ID, studentId, UUID.randomUUID(), new BigDecimal("99.99"),
				"USD");
		ReflectionTestUtils.setField(order, "id", orderId);
		when(orderService.loadOrderOwnedByCurrentStudent(orderId)).thenReturn(order);
		when(paymentSlipRepository.existsActiveSlipForOrder(orderId)).thenReturn(true);
		MockMultipartFile file = new MockMultipartFile("file", "slip.pdf", "application/pdf", validPdfBytes());

		assertThatThrownBy(() -> slipUploadService.uploadSlip(orderId, "REF-ACTIVE-EXISTS", file))
			.isInstanceOf(ConflictException.class)
			.hasMessage("You already have a payment slip under review for this order");

		verifyNoInteractions(slipStorageApi);
		verify(paymentSlipRepository, never()).save(any());
		verifyNoInteractions(slipDuplicateCheckService);
	}

	@Test
	void uploadSlipWhenTheOrderIsNotOwnedByTheCallerDeniesAccessAndCallsNoStorage() {
		UUID orderId = UUID.randomUUID();
		when(orderService.loadOrderOwnedByCurrentStudent(orderId))
			.thenThrow(new AccessDeniedException("You do not have permission to perform this action"));
		MockMultipartFile file = new MockMultipartFile("file", "slip.pdf", "application/pdf", validPdfBytes());

		assertThatThrownBy(() -> slipUploadService.uploadSlip(orderId, "REF-DENIED", file))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(slipStorageApi);
		verify(paymentSlipRepository, never()).save(any());
		verifyNoInteractions(slipDuplicateCheckService);
	}

	@Test
	void uploadSlipWhenObjectStorageStoreThrowsPropagatesAndNeverSavesASlipRow() {
		// A storage failure AFTER validation (size/content-sniff) passes must
		// not orphan a payment_slip row: the row must only ever be saved once
		// the object genuinely landed in storage.
		UUID orderId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(slipStorageApi.store(any(StoreObjectCommand.class)))
			.thenThrow(new RuntimeException("storage unavailable"));
		MockMultipartFile file = new MockMultipartFile("file", "slip.pdf", "application/pdf", validPdfBytes());

		assertThatThrownBy(() -> slipUploadService.uploadSlip(orderId, "REF-STORAGE-FAIL", file))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("storage unavailable");

		verify(paymentSlipRepository, never()).save(any());
		verifyNoInteractions(slipDuplicateCheckService);
	}

	/**
	 * Compensating-delete coverage (Low finding): the object already landed
	 * in storage before {@code paymentSlipRepository.save} was attempted -
	 * if that save fails (e.g. the {@code uq_payment_slip_tenant_order_active}
	 * unique-index violation, mapped to {@link DataIntegrityViolationException}),
	 * the just-stored object must not be orphaned, and the ORIGINAL exception
	 * must still propagate unchanged (never masked by a delete failure).
	 */
	@Test
	void uploadSlipWhenSavingThePaymentSlipRowFailsDeletesTheOrphanedStorageObjectAndPropagatesTheOriginalException() {
		UUID orderId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		StudentOrder order = new StudentOrder(TENANT_ID, studentId, UUID.randomUUID(), new BigDecimal("99.99"),
				"USD");
		ReflectionTestUtils.setField(order, "id", orderId);
		when(orderService.loadOrderOwnedByCurrentStudent(orderId)).thenReturn(order);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(slipStorageApi.store(any(StoreObjectCommand.class)))
			.thenReturn(new StoredObject("object-key-orphan-candidate", 123L));
		DataIntegrityViolationException saveFailure = new DataIntegrityViolationException(
				"duplicate key value violates unique constraint \"uq_payment_slip_tenant_order_active\"");
		when(paymentSlipRepository.save(any(PaymentSlip.class))).thenThrow(saveFailure);
		MockMultipartFile file = new MockMultipartFile("file", "slip.pdf", "application/pdf", validPdfBytes());

		assertThatThrownBy(() -> slipUploadService.uploadSlip(orderId, "REF-ORPHAN", file))
			.isSameAs(saveFailure);

		verify(slipStorageApi).delete("object-key-orphan-candidate");
		verifyNoInteractions(slipDuplicateCheckService);
	}

	private static byte[] validPdfBytes() {
		return "%PDF-1.4\n%Fixture PDF content for MVP-011 unit tests.\n%%EOF".getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * An effectively-infinite (never returns EOF) fake upload stream that
	 * throws {@link AssertionError} once more than {@code
	 * maxBytesBeforeFailure} bytes have been served across all calls -
	 * simulates an oversized upload without allocating a real huge byte
	 * array. Copied locally (rather than reused from {@code
	 * MaterialServiceTest}) since that class's nested helper is {@code
	 * private} and this test lives in a different package.
	 */
	private static final class BoundedRejectingInputStream extends InputStream {

		private final long maxBytesBeforeFailure;

		private long bytesServed;

		private BoundedRejectingInputStream(long maxBytesBeforeFailure) {
			this.maxBytesBeforeFailure = maxBytesBeforeFailure;
		}

		@Override
		public int read() {
			byte[] single = new byte[1];
			int read = read(single, 0, 1);
			return read == -1 ? -1 : (single[0] & 0xFF);
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (bytesServed >= maxBytesBeforeFailure) {
				throw new AssertionError(
						"SlipUploadService read past the declared size-limit slack margin before rejecting the "
								+ "upload - it must abort as soon as the running byte count exceeds the configured "
								+ "max, never buffer the rest of an oversized file first.");
			}
			int toServe = (int) Math.min(len, maxBytesBeforeFailure - bytesServed);
			Arrays.fill(b, off, off + toServe, (byte) 'A');
			bytesServed += toServe;
			return toServe;
		}

	}

}

package com.lms.notificationmanagement.service;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.notificationmanagement.repository.NotificationOutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Plain Mockito unit coverage for {@link NotificationDispatchPoller#pollAndDispatch()}
 * (MVP-018 plan §18): an exception thrown by {@link
 * NotificationDispatchService#dispatchOne(UUID)} while processing one claimed
 * row is caught inside that row's own iteration of the poller's loop and does
 * NOT propagate to abort the rest of the batch or the {@code @Scheduled}
 * invocation itself - every remaining id in the batch still gets its own
 * {@code dispatchOne} call. No retry policy is asserted either way, per the
 * plan's explicit "without asserting any undecided retry policy" scoping.
 *
 * <p>Supersedes the plan's originally-considered {@code
 * AsyncUncaughtExceptionHandler}-based test (that design assumed an {@code
 * @Async}/{@code ThreadPoolTaskExecutor} dispatch path, which §9.2's final
 * single-threaded {@code @Scheduled} poller design replaced) - per-row {@code
 * try/catch} isolation, proven here via plain Mockito call-count/order
 * verification, is the equivalent guarantee for the shipped design.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchPollerPerRowIsolationTest {

	@Mock
	private NotificationOutboxRepository notificationOutboxRepository;

	@Mock
	private NotificationDispatchService notificationDispatchService;

	@Mock
	private NotificationDispatchReconciliationService notificationDispatchReconciliationService;

	private NotificationDispatchPoller poller;

	@BeforeEach
	void setUp() {
		poller = new NotificationDispatchPoller(notificationOutboxRepository, notificationDispatchService,
				notificationDispatchReconciliationService);
	}

	@Test
	void anExceptionFromOneRowsDispatchDoesNotAbortTheRestOfTheBatch() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID third = UUID.randomUUID();
		when(notificationOutboxRepository.findPendingIdsAcrossTenants(Mockito.anyInt()))
			.thenReturn(List.of(first, second, third));
		// Explicit doNothing() stubs for the two unaffected rows (rather than
		// relying on Mockito's unstubbed-void-is-a-no-op default) - this is
		// what actually makes them genuine no-ops for this test's purposes AND
		// avoids Mockito's default strict-stubbing PotentialStubbingProblem
		// that a stub-only-for-`second`-with-different-arguments would
		// otherwise raise against calls for `first`/`third`, which the
		// poller's own outer per-row catch previously swallowed - silently
		// passing this test for the wrong reason (a masked strict-stubbing
		// failure, not genuine per-row isolation).
		doNothing().when(notificationDispatchService).dispatchOne(first);
		doThrow(new RuntimeException("Simulated unexpected dispatch failure")).when(notificationDispatchService)
			.dispatchOne(second);
		doNothing().when(notificationDispatchService).dispatchOne(third);

		poller.pollAndDispatch();

		// Every claimed id was individually attempted, in order, regardless
		// of the middle one throwing - no id was skipped, and the loop never
		// aborted early.
		InOrder inOrder = Mockito.inOrder(notificationDispatchService);
		inOrder.verify(notificationDispatchService).dispatchOne(first);
		inOrder.verify(notificationDispatchService).dispatchOne(second);
		inOrder.verify(notificationDispatchService).dispatchOne(third);
		verify(notificationDispatchService, times(1)).dispatchOne(first);
		verify(notificationDispatchService, times(1)).dispatchOne(second);
		verify(notificationDispatchService, times(1)).dispatchOne(third);
	}

	@Test
	void anEmptyBatchIsANoOpWithNoDispatchCallsAtAll() {
		when(notificationOutboxRepository.findPendingIdsAcrossTenants(Mockito.anyInt())).thenReturn(List.of());

		poller.pollAndDispatch();

		verify(notificationDispatchService, times(0)).dispatchOne(Mockito.any());
	}

}

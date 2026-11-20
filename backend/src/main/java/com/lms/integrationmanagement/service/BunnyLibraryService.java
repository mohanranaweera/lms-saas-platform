package com.lms.integrationmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.BunnyLibraryApi;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.BunnyLibraryStatus;
import com.lms.integrationmanagement.domain.BunnyTeacherLibrary;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.media.BunnyAccountClient;
import com.lms.integrationmanagement.gateway.media.BunnyAccountClient.BunnyLibrary;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.repository.BunnyTeacherLibraryRepository;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.service.MediaConnectionViews.BunnyLibraryView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherBunnyLibraryView;
import com.lms.integrationmanagement.service.MediaConnectionViews.TeacherLibraryRow;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi.TeacherSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * One Bunny Stream library per teacher inside the tenant's Bunny account
 * (MEDIA-INT-01A / ADR-018 Amendment 1).
 *
 * <ul>
 * <li>Creation is idempotent and never holds a DB transaction across a Bunny
 * call: a PROVISIONING row is committed first ({@link BunnyLibraryStore}),
 * Bunny is called, then the row is activated or failed in a new
 * transaction.</li>
 * <li>Before creating, the account is searched for a library carrying this
 * teacher's tag, so a retry after an interrupted attempt adopts it instead
 * of creating a duplicate.</li>
 * <li>Only teachers of the current tenant qualify - anyone else is a 404.</li>
 * <li>Libraries are never deleted; a library the current account key cannot
 * see is reported {@code UNAVAILABLE}, never recreated automatically.</li>
 * </ul>
 */
@Service
public class BunnyLibraryService implements BunnyLibraryApi {

	private static final Logger log = LoggerFactory.getLogger(BunnyLibraryService.class);

	/** A PROVISIONING row older than this was interrupted; a new attempt may take over. */
	static final Duration STALE_PROVISIONING = Duration.ofMinutes(5);

	static final String UNAVAILABLE = "UNAVAILABLE";

	private static final int MAX_DISPLAY_NAME = 200;

	private final BunnyTeacherLibraryRepository libraries;

	private final BunnyLibraryStore store;

	private final MediaProviderConnectionRepository connections;

	private final MediaConnectionStore connectionStore;

	private final MediaCredentialsCodec codec;

	private final BunnyAccountClient bunny;

	private final TeacherLookupApi teacherLookupApi;

	private final TenantLookupApi tenantLookupApi;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	public BunnyLibraryService(BunnyTeacherLibraryRepository libraries, BunnyLibraryStore store,
			MediaProviderConnectionRepository connections, MediaConnectionStore connectionStore,
			MediaCredentialsCodec codec, BunnyAccountClient bunny, TeacherLookupApi teacherLookupApi,
			TenantLookupApi tenantLookupApi, TenantContext tenantContext,
			PermissionCheckService permissionCheckService) {
		this.libraries = libraries;
		this.store = store;
		this.connections = connections;
		this.connectionStore = connectionStore;
		this.codec = codec;
		this.bunny = bunny;
		this.teacherLookupApi = teacherLookupApi;
		this.tenantLookupApi = tenantLookupApi;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
	}

	/** Result of a create request: the library, and whether this call created (or adopted) it. */
	public record Creation(BunnyLibraryView library, boolean created) {

	}

	// ------------------------------------------------------------------
	// Tenant Admin
	// ------------------------------------------------------------------

	/** Every teacher of the tenant with their latest library (or none), by name. */
	public List<TeacherLibraryRow> listForAdmin() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		Map<UUID, BunnyTeacherLibrary> latest = new LinkedHashMap<>();
		for (BunnyTeacherLibrary row : libraries.findAllNewestFirst()) {
			latest.putIfAbsent(row.getTeacherUserId(), row);
		}
		Map<UUID, String> statuses = displayStatuses(latest.values());
		Set<UUID> eligible = teacherLookupApi.listApprovedActiveTeachers()
			.stream()
			.map(TeacherSummary::userId)
			.collect(Collectors.toSet());
		boolean connected = activeConnection().isPresent();
		// Approved, active teachers - plus anyone who already has a library (e.g. suspended since).
		return teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> eligible.contains(teacher.userId()) || latest.containsKey(teacher.userId()))
			.map(teacher -> {
				BunnyTeacherLibrary row = latest.get(teacher.userId());
				// A setup stuck in PROVISIONING past the takeover window can be retried (UI/UX review UX-1).
				boolean canCreate = connected && eligible.contains(teacher.userId()) && (row == null
						|| row.getStatus() == BunnyLibraryStatus.FAILED || isStaleProvisioning(row));
				return new TeacherLibraryRow(teacher.userId(), Objects.requireNonNullElse(teacher.name(), ""),
						(row == null) ? null : toView(row, statuses.get(row.getId())), canCreate);
			})
			.sorted(Comparator.comparing(TeacherLibraryRow::teacherName, String.CASE_INSENSITIVE_ORDER))
			.toList();
	}

	public Creation createForTeacher(UUID teacherUserId) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		return ensure(teacherUserId, IntegrationActors.currentUserId());
	}

	// ------------------------------------------------------------------
	// Teacher
	// ------------------------------------------------------------------

	/** The teacher's own latest library, or {@code null}. */
	public TeacherBunnyLibraryView ownLibrary(UUID teacherUserId) {
		return libraries.findAllForTeacher(teacherUserId)
			.stream()
			.findFirst()
			.map(row -> new TeacherBunnyLibraryView(displayStatuses(List.of(row)).get(row.getId()),
					row.getLibraryName()))
			.orElse(null);
	}

	public boolean isBunnyConnected() {
		return activeConnection().isPresent();
	}

	// ------------------------------------------------------------------
	// BunnyLibraryApi (MEDIA-INT-03)
	// ------------------------------------------------------------------

	@Override
	public TeacherLibrarySummary ensureTeacherLibrary(UUID teacherUserId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		boolean self = principal != null && teacherUserId.equals(principal.userId());
		if (!self) {
			permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		}
		BunnyLibraryView view = ensure(teacherUserId, IntegrationActors.currentUserId()).library();
		return new TeacherLibrarySummary(view.id(), view.status(), view.libraryName());
	}

	// ------------------------------------------------------------------
	// Creation
	// ------------------------------------------------------------------

	private Creation ensure(UUID teacherUserId, UUID actorId) {
		MediaProviderConnection connection = activeConnection()
			.orElseThrow(() -> new ConflictException("Connect a Bunny.net account first."));
		TeacherSummary teacher = teacherLookupApi.listApprovedActiveTeachers()
			.stream()
			.filter(candidate -> candidate.userId().equals(teacherUserId))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Teacher not found"));

		BunnyTeacherLibrary live = libraries.findLiveForTeacher(teacherUserId).orElse(null);
		if (live != null && live.getStatus() == BunnyLibraryStatus.ACTIVE) {
			return new Creation(toView(live, live.getStatus().name()), false);
		}
		if (live != null) {
			if (!isStaleProvisioning(live)) {
				throw new ConflictException("This teacher's video library is being set up. Try again shortly.");
			}
			// Another request may take over the same stale row concurrently: only one marks it FAILED,
			// and the live-row unique index turns the loser's insert below into a 409 (review L-1).
			store.failIfProvisioning(live.getId(), "The previous setup attempt was interrupted.", actorId);
		}

		String tag = tagFor(teacherUserId);
		BunnyTeacherLibrary row;
		try {
			row = store.insertProvisioning(tenantContext.getTenantId(), teacherUserId, connection.getId(),
					libraryName(teacher, tag));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("This teacher's video library is being set up. Try again shortly.");
		}

		String accountKey = codec.open(connection).apiKey();
		BunnyLibrary library;
		boolean adopted;
		try {
			Optional<BunnyLibrary> existing = bunny.findLibraryByTag(accountKey, tag);
			adopted = existing.isPresent();
			library = existing.orElseGet(() -> bunny.createLibrary(accountKey, row.getLibraryName()));
		}
		catch (MediaProviderException ex) {
			store.fail(row.getId(), ex.getMessage(), actorId);
			if (ex.getKind() == MediaProviderException.Kind.AUTH_REJECTED) {
				connectionStore.markNeedsReconnect(connection.getId(), ex.getMessage());
				throw new ConflictException("Bunny.net rejected the stored API key. Reconnect Bunny.net and try again.");
			}
			throw new ServiceUnavailableException("Bunny.net could not create the library. Try again later.");
		}

		try {
			BunnyTeacherLibrary saved = store.activate(row.getId(), library, adopted, actorId);
			return new Creation(toView(saved, saved.getStatus().name()), true);
		}
		catch (DataIntegrityViolationException ex) {
			// That Bunny library is already recorded for another teacher.
			store.fail(row.getId(), "The matching Bunny.net library is already assigned to another teacher.", actorId);
			throw new ConflictException("The matching Bunny.net library is already assigned to another teacher.");
		}
	}

	// ------------------------------------------------------------------
	// Shared
	// ------------------------------------------------------------------

	/** A PROVISIONING row older than {@link #STALE_PROVISIONING} was interrupted and may be taken over. */
	private static boolean isStaleProvisioning(BunnyTeacherLibrary row) {
		return row.getStatus() == BunnyLibraryStatus.PROVISIONING && row.getCreatedAt() != null
				&& !row.getCreatedAt().isAfter(Instant.now().minus(STALE_PROVISIONING));
	}

	private Optional<MediaProviderConnection> activeConnection() {
		return connections.findCurrentForTenant(MediaProvider.BUNNY).filter(MediaProviderConnection::isActive);
	}

	/**
	 * Stored status, except an ACTIVE library shows UNAVAILABLE when there is
	 * no ACTIVE Bunny account, or when it was made under an older account that
	 * the current key cannot see. Bunny is called at most once (one listing,
	 * security review M-1) and only when a library predates the current
	 * connection; a Bunny outage leaves statuses as stored.
	 */
	private Map<UUID, String> displayStatuses(Collection<BunnyTeacherLibrary> rows) {
		Map<UUID, String> statuses = new HashMap<>();
		rows.forEach(row -> statuses.put(row.getId(), row.getStatus().name()));
		List<BunnyTeacherLibrary> active = rows.stream()
			.filter(row -> row.getStatus() == BunnyLibraryStatus.ACTIVE)
			.toList();
		if (active.isEmpty()) {
			return statuses;
		}
		MediaProviderConnection connection = activeConnection().orElse(null);
		if (connection == null) {
			active.forEach(row -> statuses.put(row.getId(), UNAVAILABLE));
			return statuses;
		}
		List<BunnyTeacherLibrary> older = active.stream()
			.filter(row -> !row.getConnectionId().equals(connection.getId()))
			.toList();
		if (older.isEmpty()) {
			return statuses;
		}
		Set<Long> visible;
		try {
			visible = bunny.listLibraryIds(codec.open(connection).apiKey());
		}
		catch (MediaProviderException ex) {
			if (ex.getKind() == MediaProviderException.Kind.UNAVAILABLE) {
				log.atInfo().setMessage("media_integration.bunny_library_check_skipped").log();
				return statuses;
			}
			visible = Set.of();
		}
		for (BunnyTeacherLibrary row : older) {
			if (!visible.contains(row.getBunnyLibraryId())) {
				statuses.put(row.getId(), UNAVAILABLE);
			}
		}
		return statuses;
	}

	private static BunnyLibraryView toView(BunnyTeacherLibrary row, String status) {
		String error = UNAVAILABLE.equals(status) ? "Not visible to the current Bunny.net account." : row.getLastError();
		return new BunnyLibraryView(row.getId(), status, row.getLibraryName(), row.getCreatedAt(), error);
	}

	/** 12 hex chars from the random tail of the teacher's UUIDv7 - stable per teacher. */
	static String tagFor(UUID teacherUserId) {
		String hex = teacherUserId.toString().replace("-", "");
		return hex.substring(hex.length() - 12);
	}

	private String libraryName(TeacherSummary teacher, String tag) {
		String tenantName = tenantLookupApi.resolveTenantSummaries(Set.of(tenantContext.getTenantId()))
			.stream()
			.findFirst()
			.map(TenantSummary::name)
			.orElse("");
		String teacherName = Objects.requireNonNullElse(teacher.name(), "").strip();
		String display = tenantName.isBlank() ? teacherName : tenantName.strip() + " - " + teacherName;
		if (display.isBlank()) {
			display = "Teacher";
		}
		if (display.length() > MAX_DISPLAY_NAME) {
			display = display.substring(0, MAX_DISPLAY_NAME).strip();
		}
		return display + " [" + tag + "]";
	}

}

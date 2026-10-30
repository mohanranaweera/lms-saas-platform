package db.migration;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-031 (LOGIN-01 Phase C) one-off backfill: give existing students a
 * sign-in mobile from their profile mobile ({@code student_profile.mobile},
 * free text), only where the number is valid and unique within the tenant.
 *
 * <p>Rules, per tenant: the profile mobile is normalised to E.164 with the
 * tenant's {@code GENERAL.phone_region} (default {@code LK}); only valid
 * mobile / fixed-line-or-mobile numbers count. A number shared by two or more
 * students of the tenant, or already used as another user's sign-in mobile,
 * is not assigned to anyone (those students keep email login until an admin
 * sets a unique number). Users that already have a sign-in mobile are left
 * alone, so a re-run changes nothing.
 *
 * <p>Self-contained (no application classes) so its behaviour can never drift
 * with later code. Logs counts only, never a number.
 */
public class V108__backfill_tenant_user_login_mobile extends BaseJavaMigration {

	private static final Logger log = LoggerFactory.getLogger(V108__backfill_tenant_user_login_mobile.class);

	static final String DEFAULT_REGION = "LK";

	private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

	/** A student without a sign-in mobile whose profile has a mobile. */
	record Candidate(UUID tenantId, UUID userId, String rawMobile) {
	}

	/** What {@link #plan} decided. {@code assignments} is userId -> E.164. */
	record Plan(Map<UUID, String> assignments, int invalid, int duplicate, int alreadyTaken) {
	}

	@Override
	public void migrate(Context context) throws Exception {
		Connection connection = context.getConnection();
		Plan plan = plan(loadCandidates(connection), loadRegions(connection), loadTaken(connection));
		try (PreparedStatement update = connection
			.prepareStatement("UPDATE tenant_user SET login_mobile = ? WHERE id = ? AND login_mobile IS NULL")) {
			int pending = 0;
			for (Map.Entry<UUID, String> assignment : plan.assignments().entrySet()) {
				update.setString(1, assignment.getValue());
				update.setObject(2, assignment.getKey());
				update.addBatch();
				if (++pending == 500) {
					update.executeBatch();
					pending = 0;
				}
			}
			if (pending > 0) {
				update.executeBatch();
			}
		}
		log.info("login_mobile backfill: assigned={}, skippedInvalid={}, skippedDuplicate={}, skippedAlreadyTaken={}",
				plan.assignments().size(), plan.invalid(), plan.duplicate(), plan.alreadyTaken());
	}

	/**
	 * Pure decision step (unit-tested): which candidate gets which number.
	 * {@code taken} holds {@code tenantId|e164} keys already used as a sign-in
	 * mobile in that tenant.
	 */
	static Plan plan(List<Candidate> candidates, Map<UUID, String> regions, Set<String> taken) {
		Map<String, List<UUID>> usersByKey = new LinkedHashMap<>();
		Map<String, String> numberByKey = new HashMap<>();
		int invalid = 0;
		for (Candidate candidate : candidates) {
			String e164 = normalize(candidate.rawMobile(), regions.getOrDefault(candidate.tenantId(), DEFAULT_REGION));
			if (e164 == null) {
				invalid++;
				continue;
			}
			String key = candidate.tenantId() + "|" + e164;
			usersByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate.userId());
			numberByKey.put(key, e164);
		}
		Map<UUID, String> assignments = new LinkedHashMap<>();
		int duplicate = 0;
		int alreadyTaken = 0;
		for (Map.Entry<String, List<UUID>> entry : usersByKey.entrySet()) {
			if (taken.contains(entry.getKey())) {
				alreadyTaken += entry.getValue().size();
			}
			else if (entry.getValue().size() > 1) {
				duplicate += entry.getValue().size();
			}
			else {
				assignments.put(entry.getValue().get(0), numberByKey.get(entry.getKey()));
			}
		}
		return new Plan(assignments, invalid, duplicate, alreadyTaken);
	}

	/** E.164 for a valid mobile / fixed-line-or-mobile number, else {@code null}. */
	static String normalize(String raw, String region) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String effectiveRegion = region != null
				&& PHONE_NUMBERS.getSupportedRegions().contains(region.toUpperCase(Locale.ROOT))
						? region.toUpperCase(Locale.ROOT) : DEFAULT_REGION;
		try {
			PhoneNumber number = PHONE_NUMBERS.parse(raw.strip(), effectiveRegion);
			if (!PHONE_NUMBERS.isValidNumber(number)) {
				return null;
			}
			PhoneNumberType type = PHONE_NUMBERS.getNumberType(number);
			if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
				return null;
			}
			return PHONE_NUMBERS.format(number, PhoneNumberFormat.E164);
		}
		catch (NumberParseException ex) {
			return null;
		}
	}

	private static List<Candidate> loadCandidates(Connection connection) throws Exception {
		List<Candidate> candidates = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT sp.tenant_id, sp.user_id, sp.mobile
						FROM student_profile sp
						JOIN tenant_user tu ON tu.tenant_id = sp.tenant_id AND tu.id = sp.user_id
						WHERE tu.login_mobile IS NULL AND sp.mobile IS NOT NULL AND btrim(sp.mobile) <> ''
						ORDER BY sp.tenant_id, sp.user_id""")) {
			while (rows.next()) {
				candidates.add(new Candidate(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
						rows.getString(3)));
			}
		}
		return candidates;
	}

	private static Map<UUID, String> loadRegions(Connection connection) throws Exception {
		Map<UUID, String> regions = new HashMap<>();
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("""
						SELECT tenant_id, value #>> '{}'
						FROM tenant_config_entry
						WHERE config_domain = 'GENERAL' AND config_key = 'phone_region'""")) {
			while (rows.next()) {
				regions.put(rows.getObject(1, UUID.class), rows.getString(2));
			}
		}
		return regions;
	}

	private static Set<String> loadTaken(Connection connection) throws Exception {
		Set<String> taken = new HashSet<>();
		try (Statement statement = connection.createStatement();
				ResultSet rows = statement
					.executeQuery("SELECT tenant_id, login_mobile FROM tenant_user WHERE login_mobile IS NOT NULL")) {
			while (rows.next()) {
				taken.add(rows.getObject(1, UUID.class) + "|" + rows.getString(2));
			}
		}
		return taken;
	}

}

package com.lms.coursemanagement.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The fixed catalog of {@link LessonResourceType}s offered by the "Add New
 * Session" form (KLASS-T3; spec {@code docs/specs/Klass_Course_Management.md}
 * §5, plan {@code docs/plans/klass-enrollment-payment-parity.md} §4.1
 * {@code GET /api/v1/courses/lesson-resource-types}). Entries are listed in
 * the same order as the Klass dropdown.
 *
 * <p>This is a plain, stateless utility class with static methods only. It
 * is not a Spring bean, holds no tenant data and needs no tenant context. It
 * never calls a provider. Each descriptor records which EXISTING resource
 * delivers the session's content, so callers reuse the real integrations and
 * do not invent a new one:
 * <ul>
 * <li>{@link Attachment#MATERIAL_FILE}: a {@code material} file upload
 * (content-management).</li>
 * <li>{@link Attachment#MATERIAL_VIDEO_ASSET}: a {@code material} of type
 * {@code VIDEO}/{@code RECORDING} that points at a {@code video_asset}
 * (video-access-management). It plays in the LMS secure player or the AOC
 * Player (ADR-025).</li>
 * <li>{@link Attachment#MATERIAL_LINK}: a {@code material} of type {@code
 * LINK} with {@code external_url}. No provider integration is involved.</li>
 * <li>{@link Attachment#CLASS_SESSION}: a {@code class_session} whose {@code
 * lesson_id} is this lesson (live-class-management, provisioned through
 * integration-management's {@code LiveClassProviderApi}).</li>
 * <li>{@link Attachment#CLASS_SESSION_RECORDING}: a {@code
 * class_session_recording} of such a class session.</li>
 * </ul>
 * Material type names are kept as strings so this {@code api} package does
 * not import content-management's {@code domain} enum (per {@code
 * .claude/rules/architecture.md}).
 *
 * <p><b>Support levels describe the code, not tenant configuration.</b>
 * {@link LessonResourceSupport#INTEGRATED} means a real code path exists.
 * The tenant may still have to configure it (for {@link
 * LessonResourceType#ZOOM_MEETING}: the platform Zoom OAuth app, Zoom enabled
 * for the tenant, and a licensed Zoom connection for the teacher). Where setup
 * is missing, the owning service still refuses the action (409/503). This
 * catalog never makes it succeed.
 */
public final class LessonResourceTypeCatalog {

	/** Which existing resource delivers a lesson's content. See the class javadoc. */
	public enum Attachment {

		MATERIAL_FILE, MATERIAL_VIDEO_ASSET, MATERIAL_LINK, CLASS_SESSION, CLASS_SESSION_RECORDING

	}

	/**
	 * One catalog entry.
	 *
	 * @param type the stored {@code course_lesson.resource_type} value.
	 * @param label the label shown in the admin dropdown. Klass's wording is
	 * kept, except "Vidrora Video", which becomes our own player name.
	 * @param support how far the platform really supports this type.
	 * @param help admin-facing help text that states what the type does and
	 * does not do.
	 * @param attachesTo which existing resource holds the content.
	 * @param materialTypes the {@code material.material_type} values that may
	 * carry this content, as strings. Empty when the content is not a material.
	 * @param liveClass {@code true} for live/scheduled meeting types. Always
	 * equal to {@link LessonResourceTypeCatalog#isLiveType(LessonResourceType)}.
	 */
	public record LessonResourceTypeDescriptor(LessonResourceType type, String label, LessonResourceSupport support,
			String help, Attachment attachesTo, List<String> materialTypes, boolean liveClass) {

		public LessonResourceTypeDescriptor {
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(support, "support");
			Objects.requireNonNull(help, "help");
			Objects.requireNonNull(attachesTo, "attachesTo");
			materialTypes = List.copyOf(materialTypes);
		}

	}

	private static final List<LessonResourceTypeDescriptor> DESCRIPTORS = List.of(
			new LessonResourceTypeDescriptor(LessonResourceType.VIMEO_VIDEO, "Vimeo Video",
					LessonResourceSupport.LINK_ONLY,
					"Paste a Vimeo video link. The video opens on Vimeo. Vimeo videos are not imported into or "
							+ "played by our secure player, so our playback protection does not apply. For protected "
							+ "playback, upload the video as a Player Video instead.",
					Attachment.MATERIAL_LINK, List.of("LINK"), false),
			new LessonResourceTypeDescriptor(LessonResourceType.SECURE_VIDEO, "Player Video",
					LessonResourceSupport.INTEGRATED,
					"Add a video from your institute's secure video library. Students play it through short-lived "
							+ "signed playback in the LMS player or the AOC Player app, depending on your "
							+ "institute's playback policy.",
					Attachment.MATERIAL_VIDEO_ASSET, List.of("VIDEO"), false),
			new LessonResourceTypeDescriptor(LessonResourceType.DOCUMENT, "Document",
					LessonResourceSupport.INTEGRATED,
					"Upload a PDF or document. Students get it through a short-lived signed download link, "
							+ "issued only after their access to this session is checked.",
					Attachment.MATERIAL_FILE, List.of("PDF", "DOCUMENT"), false),
			new LessonResourceTypeDescriptor(LessonResourceType.IMAGE, "Image",
					LessonResourceSupport.INTEGRATED,
					"Choose an image from your institute's Media Library. Students get it through a short-lived "
							+ "signed download link issued only after their access to this session is checked.",
					Attachment.MATERIAL_FILE, List.of("IMAGE"), false),
			new LessonResourceTypeDescriptor(LessonResourceType.ZOOM_LINK, "Zoom Link",
					LessonResourceSupport.LINK_ONLY,
					"Paste an existing Zoom join link. The platform does not create or check the meeting and "
							+ "does not record attendance. Students can pass the link on to others. To have the "
							+ "platform create the meeting, use Zoom Meeting.",
					Attachment.MATERIAL_LINK, List.of("LINK"), true),
			new LessonResourceTypeDescriptor(LessonResourceType.ZOOM_MEETING, "Zoom Meeting",
					LessonResourceSupport.INTEGRATED,
					"Schedule a live class. The platform creates a real Zoom meeting on the course teacher's own "
							+ "Zoom account, and students get a personal join link when the class is live. Zoom must "
							+ "be enabled for your institute, and the teacher must connect a licensed (paid) Zoom "
							+ "account in Settings > Integrations. Without that, scheduling is refused.",
					Attachment.CLASS_SESSION, List.of(), true),
			new LessonResourceTypeDescriptor(LessonResourceType.ZOOM_WEBINAR, "Zoom Webinar",
					LessonResourceSupport.LINK_ONLY,
					"The platform cannot create Zoom Webinars yet. Paste the webinar's join or registration link. "
							+ "Registration, panelists and attendance stay in Zoom.",
					Attachment.MATERIAL_LINK, List.of("LINK"), true),
			new LessonResourceTypeDescriptor(LessonResourceType.ZOOM_RECORDING, "Zoom Recording",
					LessonResourceSupport.UNSUPPORTED,
					"Importing Zoom cloud recordings is not available yet. To share a class recording, download it "
							+ "from Zoom and upload it as a Player Video.",
					Attachment.CLASS_SESSION_RECORDING, List.of(), false),
			new LessonResourceTypeDescriptor(LessonResourceType.VIDEO_LINK, "Video Link",
					LessonResourceSupport.LINK_ONLY,
					"Paste a link to a video hosted on another site. The video opens on that site, without our "
							+ "playback protection. For protected playback, upload the video as a Player Video.",
					Attachment.MATERIAL_LINK, List.of("LINK"), false),
			new LessonResourceTypeDescriptor(LessonResourceType.GOOGLE_DRIVE_LINK, "Google Drive Link",
					LessonResourceSupport.LINK_ONLY,
					"Paste a Google Drive share link. Students open it in Google Drive, and the file's Drive "
							+ "sharing settings decide who can see it. The platform cannot restrict it.",
					Attachment.MATERIAL_LINK, List.of("LINK"), false),
			new LessonResourceTypeDescriptor(LessonResourceType.MS_TEAMS_LINK, "MS Teams Link",
					LessonResourceSupport.LINK_ONLY,
					"Paste a Microsoft Teams meeting link. The platform does not create or check the meeting and "
							+ "does not record attendance.",
					Attachment.MATERIAL_LINK, List.of("LINK"), true),
			new LessonResourceTypeDescriptor(LessonResourceType.GOOGLE_MEET, "Google Meet",
					LessonResourceSupport.LINK_ONLY,
					"Paste a Google Meet link. The platform does not create or check the meeting and does not "
							+ "record attendance.",
					Attachment.MATERIAL_LINK, List.of("LINK"), true));

	private static final Map<LessonResourceType, LessonResourceTypeDescriptor> BY_TYPE;

	static {
		Map<LessonResourceType, LessonResourceTypeDescriptor> byType = new EnumMap<>(LessonResourceType.class);
		for (LessonResourceTypeDescriptor descriptor : DESCRIPTORS) {
			if (byType.put(descriptor.type(), descriptor) != null) {
				throw new IllegalStateException("Duplicate lesson resource type descriptor: " + descriptor.type());
			}
			if (descriptor.liveClass() != isLiveType(descriptor.type())) {
				throw new IllegalStateException("liveClass flag disagrees with isLiveType for " + descriptor.type());
			}
		}
		for (LessonResourceType type : LessonResourceType.values()) {
			if (!byType.containsKey(type)) {
				throw new IllegalStateException("Missing lesson resource type descriptor: " + type);
			}
		}
		BY_TYPE = Collections.unmodifiableMap(byType);
	}

	private LessonResourceTypeCatalog() {
	}

	/** Every descriptor, in Klass dropdown order. The list is immutable. */
	public static List<LessonResourceTypeDescriptor> all() {
		return DESCRIPTORS;
	}

	/** @throws NullPointerException if {@code type} is {@code null} */
	public static LessonResourceTypeDescriptor descriptor(LessonResourceType type) {
		return BY_TYPE.get(Objects.requireNonNull(type, "type"));
	}

	/**
	 * Whether {@code type} is a live/scheduled meeting type: {@code
	 * ZOOM_MEETING}, {@code ZOOM_WEBINAR}, {@code ZOOM_LINK}, {@code
	 * MS_TEAMS_LINK} or {@code GOOGLE_MEET}. Returns {@code false} for {@code
	 * null}, because {@code course_lesson.resource_type} is nullable.
	 */
	public static boolean isLiveType(LessonResourceType type) {
		if (type == null) {
			return false;
		}
		return switch (type) {
			case ZOOM_MEETING, ZOOM_WEBINAR, ZOOM_LINK, MS_TEAMS_LINK, GOOGLE_MEET -> true;
			case VIMEO_VIDEO, SECURE_VIDEO, DOCUMENT, IMAGE, ZOOM_RECORDING, VIDEO_LINK, GOOGLE_DRIVE_LINK -> false;
		};
	}

}

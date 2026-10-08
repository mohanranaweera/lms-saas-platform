import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import "./styles.css";

// Mirrors `player::View` in the Rust core. The UI only displays state; every
// decision (pairing, entitlement, stopping) is made in Rust or on the server.
type View =
  | { screen: "notPaired" }
  | { screen: "pairing" }
  | { screen: "ready" }
  | { screen: "opening" }
  | { screen: "playing"; lessonTitle: string | null; watermarkText: string | null }
  | { screen: "stopped"; reason: string }
  | { screen: "error"; code: string };

const PLAY_AGAIN = "Press Play on the lesson in your LMS to open it again.";

const STOP_REASONS: Record<string, string> = {
  ENDED_BY_STUDENT: "You ended the session.",
  ENDED: "The session has ended.",
  EXPIRED: `Your session expired. ${PLAY_AGAIN}`,
  CONNECTION_LOST: `The connection to the LMS was lost, so playback stopped. ${PLAY_AGAIN}`,
  ADMIN: "Your institute stopped this session.",
  DEVICE_REVOKED: "This computer was removed from your account. Ask your institute or pair again from the LMS.",
  INSTALLATION_REVOKED: "This player was removed from your account. Pair it again from the AOC Player page in the LMS.",
  ACCESS_REVOKED: "You no longer have access to this video.",
  SUPERSEDED_BY_NEW_SESSION: "This video was opened in a new session.",
  PLAYER_VERSION_UNSUPPORTED: "This version of the AOC Player is no longer supported. Install the latest version.",
};

const ERRORS: Record<string, string> = {
  INVALID_LINK: "That isn't a valid AOC Player link. Open videos with the Play button in your LMS.",
  NOT_PAIRED: "This player isn't paired yet. In the LMS, open AOC Player in your student menu and choose “Pair the AOC Player”.",
  PLAYER_PAIRING_CODE_INVALID: "This pairing link has expired or was already used. Start pairing again from the LMS.",
  PLAYBACK_LAUNCH_CODE_INVALID: `This link has expired or was already used. ${PLAY_AGAIN}`,
  PLAYER_VERSION_UNSUPPORTED: "This version of the AOC Player is too old. Install the latest version.",
  PLAYER_RATE_LIMITED: "Too many attempts. Wait a minute and try again.",
  NETWORK: "The player couldn't reach the LMS. Check your internet connection and try again.",
  KEY_UNAVAILABLE: "The player couldn't use this computer's secure key storage.",
  STORAGE_UNAVAILABLE: "The player couldn't save its settings on this computer.",
};

const element = (id: string): HTMLElement => {
  const found = document.getElementById(id);
  if (!found) throw new Error(`missing #${id}`);
  return found;
};

const eyebrow = element("eyebrow");
const title = element("title");
const message = element("message");
const watermark = element("watermark");
const end = element("end") as HTMLButtonElement;
const card = document.querySelector<HTMLElement>(".card");

function render(view: View): void {
  let heading: string;
  let text = "";
  let label = "";
  let mark: string | null = null;
  let canEnd = false;
  let tone = "neutral";

  switch (view.screen) {
    case "notPaired":
      heading = "Set up the AOC Player";
      text = ERRORS.NOT_PAIRED;
      break;
    case "pairing":
      heading = "Pairing with your account…";
      break;
    case "ready":
      heading = "Ready";
      text = "Press Play on a lesson video in your LMS to watch it here.";
      tone = "ok";
      break;
    case "opening":
      heading = "Opening your video…";
      break;
    case "playing":
      label = "Session active";
      heading = view.lessonTitle ?? "Lesson video";
      text = "This session is checked with the LMS while it is open.";
      mark = view.watermarkText;
      canEnd = true;
      tone = "ok";
      break;
    case "stopped":
      label = "Playback stopped";
      heading = "Session ended";
      text = STOP_REASONS[view.reason] ?? "Playback stopped.";
      break;
    case "error":
      heading = "Couldn't continue";
      text = ERRORS[view.code] ?? "Something went wrong. Try again from the LMS.";
      tone = "error";
      break;
  }

  eyebrow.textContent = label;
  title.textContent = heading;
  message.textContent = text;
  watermark.textContent = mark ?? "";
  watermark.hidden = !mark;
  end.hidden = !canEnd;
  end.disabled = false;
  card?.setAttribute("data-tone", tone);
}

end.addEventListener("click", () => {
  end.disabled = true;
  void invoke("end_session");
});

element("footer").textContent = "Your lessons are opened from the LMS. This app never asks for your password.";

await listen<View>("player://view", (event) => render(event.payload));
render(await invoke<View>("get_state"));

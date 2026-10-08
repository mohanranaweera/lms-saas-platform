/**
 * Control bar for the file engine (Phase 10): play/pause, back 10 s, a
 * scrubber only when the institute allows seeking, time, mute + volume,
 * speed and fullscreen, plus keyboard shortcuts. Forward jumps stay blocked
 * when seeking is off (the engine also undoes them); rewinding is always fine.
 */

const SKIP_SECONDS = 10;
const VOLUME_STEP = 0.1;
export const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2] as const;

export function controlBar(video: HTMLVideoElement, fullscreenTarget: HTMLElement, allowSeeking: boolean): HTMLElement {
  const bar = document.createElement("div");
  bar.className = "file-controls";

  const play = iconButton("Play", () => togglePlay(video));
  const back = iconButton(`Back ${SKIP_SECONDS} seconds`, () => skip(video, -SKIP_SECONDS, allowSeeking), "−10s");

  const scrubber = document.createElement("input");
  scrubber.type = "range";
  scrubber.className = "scrubber";
  scrubber.min = "0";
  scrubber.step = "0.1";
  scrubber.value = "0";
  scrubber.setAttribute("aria-label", "Seek");
  scrubber.hidden = !allowSeeking;
  scrubber.addEventListener("input", () => {
    video.currentTime = Number(scrubber.value);
  });

  const time = document.createElement("span");
  time.className = "file-time";

  const mute = iconButton("Mute", () => {
    video.muted = !video.muted;
  });
  const volume = document.createElement("input");
  volume.type = "range";
  volume.className = "volume";
  volume.min = "0";
  volume.max = "1";
  volume.step = "0.05";
  volume.value = String(video.volume);
  volume.setAttribute("aria-label", "Volume");
  volume.addEventListener("input", () => {
    video.volume = Number(volume.value);
    video.muted = video.volume === 0;
  });

  const speed = document.createElement("select");
  speed.className = "speed";
  speed.setAttribute("aria-label", "Playback speed");
  for (const rate of SPEEDS) {
    const option = document.createElement("option");
    option.value = String(rate);
    option.textContent = `${rate}×`;
    option.selected = rate === 1;
    speed.append(option);
  }
  speed.addEventListener("change", () => {
    video.playbackRate = Number(speed.value);
  });

  const fullscreen = iconButton("Fullscreen", () => toggleFullscreen(fullscreenTarget));

  const sync = () => {
    play.textContent = video.paused ? "Play" : "Pause";
    play.setAttribute("aria-label", video.paused ? "Play video" : "Pause video");
    if (Number.isFinite(video.duration)) scrubber.max = String(video.duration);
    if (document.activeElement !== scrubber) scrubber.value = String(video.currentTime);
    time.textContent = `${clock(video.currentTime)} / ${clock(video.duration)}`;
    mute.textContent = video.muted || video.volume === 0 ? "Unmute" : "Mute";
    mute.setAttribute("aria-label", video.muted ? "Unmute" : "Mute");
    volume.value = String(video.muted ? 0 : video.volume);
    fullscreen.textContent = document.fullscreenElement ? "Exit full screen" : "Full screen";
  };
  for (const event of ["play", "pause", "timeupdate", "loadedmetadata", "durationchange", "volumechange"]) {
    video.addEventListener(event, sync);
  }
  document.addEventListener("fullscreenchange", sync);
  sync();

  // Keyboard shortcuts while the player has focus.
  fullscreenTarget.tabIndex = 0;
  fullscreenTarget.addEventListener("keydown", (event) => {
    if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) return;
    const handled = shortcut(event.key, video, fullscreenTarget, allowSeeking);
    if (handled) event.preventDefault();
  });
  video.addEventListener("click", () => {
    togglePlay(video);
    fullscreenTarget.focus();
  });

  const spacer = document.createElement("span");
  spacer.className = "spacer";
  bar.append(play, back, scrubber, time, spacer, mute, volume, speed, fullscreen);
  return bar;
}

function shortcut(key: string, video: HTMLVideoElement, target: HTMLElement, allowSeeking: boolean): boolean {
  switch (key) {
    case " ":
    case "k":
      togglePlay(video);
      return true;
    case "ArrowLeft":
    case "j":
      skip(video, -SKIP_SECONDS, allowSeeking);
      return true;
    case "ArrowRight":
    case "l":
      skip(video, SKIP_SECONDS, allowSeeking);
      return true;
    case "ArrowUp":
      video.muted = false;
      video.volume = Math.min(1, video.volume + VOLUME_STEP);
      return true;
    case "ArrowDown":
      video.volume = Math.max(0, video.volume - VOLUME_STEP);
      return true;
    case "m":
      video.muted = !video.muted;
      return true;
    case "f":
      toggleFullscreen(target);
      return true;
    default:
      return false;
  }
}

function togglePlay(video: HTMLVideoElement) {
  if (video.paused) void video.play().catch(() => undefined);
  else video.pause();
}

function skip(video: HTMLVideoElement, seconds: number, allowSeeking: boolean) {
  if (seconds > 0 && !allowSeeking) return;
  const end = Number.isFinite(video.duration) ? video.duration : Infinity;
  video.currentTime = Math.min(end, Math.max(0, video.currentTime + seconds));
}

function toggleFullscreen(target: HTMLElement) {
  if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
  else void target.requestFullscreen().catch(() => undefined);
}

function iconButton(label: string, onClick: () => void, text = label): HTMLButtonElement {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "secondary";
  button.textContent = text;
  button.setAttribute("aria-label", label);
  button.addEventListener("click", onClick);
  return button;
}

export function clock(seconds: number): string {
  if (!Number.isFinite(seconds)) return "0:00";
  const whole = Math.floor(seconds);
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const secs = String(whole % 60).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${secs}` : `${minutes}:${secs}`;
}

/**
 * VIDEO Phase 13 (ADR-026): plays a signed HLS playlist (DynTube) in a plain
 * `<video>` element. hls.js is loaded on demand, so pages without HLS video do
 * not ship it. Where Media Source Extensions are unavailable but the browser
 * plays HLS itself (Safari on iOS), the playlist URL is set as `src`.
 *
 * The playlist URL is short-lived and signed by the server; once hls.js has
 * loaded it, the variant playlists and segments carry the CDN's own
 * signatures, so a running stream needs no new URL. `onFatal` is called when
 * playback cannot continue (expired/refused playlist - DynTube answers some
 * refusals with an empty playlist - or an unrecoverable error); the caller
 * decides whether to fetch a fresh session. `KEY_REFUSED` means the host's
 * key server refused the AES decryption key (a 4xx): a fresh session can't
 * fix that - it is a setting on the host's account (e.g. DynTube security).
 *
 * @returns a function that stops loading and releases the element
 */
export type HlsFailure = "STREAM" | "KEY_REFUSED";

/** A single 4xx could be a blip; two in a row from the key server is a refusal. */
const KEY_REFUSALS_BEFORE_GIVING_UP = 2;

export async function attachHls(
  video: HTMLVideoElement,
  playlistUrl: string,
  onFatal: (failure: HlsFailure) => void
): Promise<() => void> {
  const { default: Hls } = await import("hls.js");
  if (Hls.isSupported()) {
    const hls = new Hls({ enableWorker: true });
    let lastMediaRecovery = 0;
    let refusedKeys = 0;
    hls.on(Hls.Events.ERROR, (_event, data) => {
      // hls.js reports a refused key as non-fatal and keeps retrying; a repeated 4xx is final.
      const code = data.response?.code ?? 0;
      if (data.details === Hls.ErrorDetails.KEY_LOAD_ERROR && code >= 400 && code < 500) {
        refusedKeys += 1;
        // Only once: the caller detaches (destroys) this stream in response.
        if (refusedKeys === KEY_REFUSALS_BEFORE_GIVING_UP || (data.fatal && refusedKeys < KEY_REFUSALS_BEFORE_GIVING_UP)) {
          onFatal("KEY_REFUSED");
        }
        return;
      }
      if (!data.fatal) return;
      // One media-pipeline recovery per 5 s (hls.js API docs); anything else ends this stream.
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && Date.now() - lastMediaRecovery > 5000) {
        lastMediaRecovery = Date.now();
        hls.recoverMediaError();
        return;
      }
      onFatal("STREAM");
    });
    hls.loadSource(playlistUrl);
    hls.attachMedia(video);
    return () => hls.destroy();
  }
  if (video.canPlayType("application/vnd.apple.mpegurl")) {
    video.src = playlistUrl;
    return () => {
      video.removeAttribute("src");
      video.load();
    };
  }
  onFatal("STREAM");
  return () => undefined;
}

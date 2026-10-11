import { embedEngine } from "./embed";
import { fileEngine } from "./file";
import { hlsEngine } from "./hls";
import type { Engine, EngineError, Media, PlaybackReport } from "./types";
import { youtubeEngine } from "./youtube";

export type { Engine, Media, PlaybackReport, PlaybackState } from "./types";

/**
 * Picks the engine for the media Rust validated. `unsupported` is the
 * FutureProviderEngine of plan §22/§24: it never plays anything.
 */
export function createEngine(
  stage: HTMLElement,
  media: Media,
  title: string,
  onError: EngineError,
  onPlayback: PlaybackReport,
): Engine {
  switch (media.engine) {
    case "youtube":
      return youtubeEngine(stage, media, title, onError, onPlayback);
    case "file":
      return fileEngine(stage, media, title, onError, onPlayback);
    case "hls":
      return hlsEngine(stage, media, title, onError, onPlayback);
    case "embed":
      return embedEngine(stage, media, title, onError, onPlayback);
    case "unsupported":
      onError("This video can't be played in the AOC Player.");
      stage.replaceChildren();
      return { kind: "unsupported", refresh() {}, destroy() {} };
  }
}

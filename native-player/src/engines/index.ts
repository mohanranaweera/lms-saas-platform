import { fileEngine } from "./file";
import type { Engine, EngineError, Media } from "./types";
import { youtubeEngine } from "./youtube";

export type { Engine, Media } from "./types";

/**
 * Picks the engine for the media Rust validated. `unsupported` is the
 * FutureProviderEngine of plan §22/§24: it never plays anything.
 */
export function createEngine(stage: HTMLElement, media: Media, title: string, onError: EngineError): Engine {
  switch (media.engine) {
    case "youtube":
      return youtubeEngine(stage, media.videoId, title, onError);
    case "file":
      return fileEngine(stage, media, title, onError);
    case "unsupported":
      onError("This video can't be played in the AOC Player.");
      stage.replaceChildren();
      return { kind: "unsupported", refresh() {}, destroy() {} };
  }
}

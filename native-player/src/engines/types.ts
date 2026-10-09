// Mirrors `media::Media` in the Rust core, which validates every reference
// before it reaches the UI.
export type Media =
  | { engine: "youtube"; videoId: string }
  | { engine: "file"; url: string; allowSeeking: boolean }
  | { engine: "unsupported" };

/**
 * A playback engine (plan §22). Engines only play: no auth, no session, no
 * decisions - Rust starts and stops them from the server's answers.
 */
export interface Engine {
  readonly kind: Media["engine"];
  /** A fresh signed URL from a heartbeat (file engine only). */
  refresh(media: Media): void;
  /** Stops playback and drops every media reference. */
  destroy(): void;
}

export type EngineError = (message: string) => void;

/** Mirrors `player::PlaybackState` in the Rust core. */
export type PlaybackState = "PLAYING" | "PAUSED" | "IDLE";

/**
 * Where playback is and whether it is running (VIDEO Phase 12). Telemetry
 * only: Rust sends it with the next heartbeat and the server decides what
 * counts as watched.
 */
export type PlaybackReport = (positionSeconds: number, state: PlaybackState) => void;

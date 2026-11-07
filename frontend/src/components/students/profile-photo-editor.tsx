"use client";

import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type PointerEvent } from "react";
import { ImagePlus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  useRemoveStudentPhoto,
  useStudentPhoto,
  useUploadStudentPhoto,
  studentErrorMessage,
  type PhotoOwner,
} from "@/lib/api/student-management";
import { StudentAvatar } from "./student-avatar";

const ACCEPTED = ["image/jpeg", "image/png"];
const MAX_BYTES = 2 * 1024 * 1024;
const VIEW = 240;
const OUTPUT = 400;

interface CropState {
  image: HTMLImageElement;
  objectUrl: string;
  zoom: number;
  ox: number;
  oy: number;
}

function baseScale(image: HTMLImageElement): number {
  return VIEW / Math.min(image.naturalWidth, image.naturalHeight);
}

function clampOffsets(state: CropState): CropState {
  const scale = baseScale(state.image) * state.zoom;
  const w = state.image.naturalWidth * scale;
  const h = state.image.naturalHeight * scale;
  return {
    ...state,
    ox: Math.min(0, Math.max(VIEW - w, state.ox)),
    oy: Math.min(0, Math.max(VIEW - h, state.oy)),
  };
}

function draw(canvas: HTMLCanvasElement, state: CropState, size: number) {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const factor = size / VIEW;
  const scale = baseScale(state.image) * state.zoom * factor;
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, size, size);
  ctx.drawImage(
    state.image,
    state.ox * factor,
    state.oy * factor,
    state.image.naturalWidth * scale,
    state.image.naturalHeight * scale
  );
}

function errorText(error: unknown, fallback: string): string {
  return studentErrorMessage(error, fallback);
}

/**
 * Profile photo editor (STU-MGMT-02 §6). Preview + simple square crop (zoom
 * slider, drag or arrow keys to position) on a canvas, exported as a 400×400
 * JPEG and uploaded as multipart. The server re-validates, re-encodes and
 * strips metadata — this is only a convenience for a well-framed photo.
 * Used by staff (`/students/{id}/photo`) and by the student themself (`/students/me/photo`).
 */
export function ProfilePhotoEditor({
  owner,
  name,
  canEdit = true,
  onChanged,
}: {
  owner: PhotoOwner;
  name: string;
  canEdit?: boolean;
  onChanged?: (message: string) => void;
}) {
  const inputId = useId();
  const guidanceId = useId();
  const photoQuery = useStudentPhoto(owner);
  const upload = useUploadStudentPhoto(owner);
  const remove = useRemoveStudentPhoto(owner);
  const [crop, setCrop] = useState<CropState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const drag = useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);

  useEffect(() => {
    if (crop && canvasRef.current) draw(canvasRef.current, crop, VIEW);
  }, [crop]);

  // Release the local preview URL whenever the crop session ends or changes.
  const objectUrl = crop?.objectUrl ?? null;
  useEffect(() => {
    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [objectUrl]);

  const announce = useCallback(
    (message: string) => {
      setNotice(message);
      onChanged?.(message);
    },
    [onChanged]
  );

  function onFile(file: File | undefined) {
    setError(null);
    setNotice(null);
    if (!file) return;
    if (!ACCEPTED.includes(file.type)) {
      setError("Choose a JPG or PNG image.");
      return;
    }
    if (file.size > MAX_BYTES) {
      setError("The image is larger than 2 MB. Choose a smaller image.");
      return;
    }
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      if (image.naturalWidth < 64 || image.naturalHeight < 64) {
        URL.revokeObjectURL(url);
        setError("The image is too small. Use at least 64 × 64 pixels.");
        return;
      }
      const scale = baseScale(image);
      setCrop({
        image,
        objectUrl: url,
        zoom: 1,
        ox: (VIEW - image.naturalWidth * scale) / 2,
        oy: (VIEW - image.naturalHeight * scale) / 2,
      });
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      setError("This image couldn't be read. Try another file.");
    };
    image.src = url;
  }

  function setZoom(zoom: number) {
    setCrop((current) => {
      if (!current) return current;
      // Keep the centre of the viewport fixed while zooming.
      const ratio = zoom / current.zoom;
      const cx = VIEW / 2;
      return clampOffsets({
        ...current,
        zoom,
        ox: cx - (cx - current.ox) * ratio,
        oy: cx - (cx - current.oy) * ratio,
      });
    });
  }

  function onPointerDown(event: PointerEvent<HTMLCanvasElement>) {
    if (!crop) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { x: event.clientX, y: event.clientY, ox: crop.ox, oy: crop.oy };
  }

  function onPointerMove(event: PointerEvent<HTMLCanvasElement>) {
    const start = drag.current;
    if (!start) return;
    setCrop((current) =>
      current
        ? clampOffsets({ ...current, ox: start.ox + event.clientX - start.x, oy: start.oy + event.clientY - start.y })
        : current
    );
  }

  function onKeyDown(event: KeyboardEvent<HTMLCanvasElement>) {
    const step = event.shiftKey ? 20 : 5;
    const delta: Record<string, [number, number]> = {
      ArrowLeft: [step, 0],
      ArrowRight: [-step, 0],
      ArrowUp: [0, step],
      ArrowDown: [0, -step],
    };
    const move = delta[event.key];
    if (!move) return;
    event.preventDefault();
    setCrop((current) => (current ? clampOffsets({ ...current, ox: current.ox + move[0], oy: current.oy + move[1] }) : current));
  }

  async function save() {
    if (!crop) return;
    setError(null);
    const canvas = document.createElement("canvas");
    canvas.width = OUTPUT;
    canvas.height = OUTPUT;
    draw(canvas, crop, OUTPUT);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.9));
    if (!blob) {
      setError("The photo couldn't be prepared. Try another image.");
      return;
    }
    try {
      await upload.mutateAsync(blob);
      setCrop(null);
      if (fileRef.current) fileRef.current.value = "";
      announce("Profile photo updated.");
    } catch (uploadError) {
      setError(errorText(uploadError, "The photo couldn't be uploaded. Please try again."));
    }
  }

  async function removePhoto() {
    setError(null);
    try {
      await remove.mutateAsync();
      announce("Profile photo removed.");
    } catch (removeError) {
      setError(errorText(removeError, "The photo couldn't be removed. Please try again."));
    }
  }

  const photoUrl = photoQuery.data?.url ?? null;
  const busy = upload.isPending || remove.isPending;

  return (
    <section className="flex flex-col gap-3" aria-label="Profile photo" data-testid="profile-photo-editor">
      <div className="flex flex-wrap items-center gap-4">
        <StudentAvatar name={name} photoUrl={photoUrl} size="lg" />
        {canEdit ? (
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap gap-2">
              <Label
                htmlFor={inputId}
                className="inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-lg border border-border bg-background px-2.5 text-sm font-medium hover:bg-muted focus-within:ring-3 focus-within:ring-ring/50"
              >
                <ImagePlus className="size-4" aria-hidden="true" />
                {photoUrl ? "Change photo" : "Upload photo"}
              </Label>
              <input
                ref={fileRef}
                id={inputId}
                type="file"
                accept={ACCEPTED.join(",")}
                className="sr-only"
                aria-describedby={guidanceId}
                disabled={busy}
                onChange={(event) => onFile(event.target.files?.[0])}
              />
              {photoUrl && !crop ? (
                <Button type="button" variant="outline" disabled={busy} onClick={() => void removePhoto()}>
                  <Trash2 aria-hidden="true" />
                  {remove.isPending ? "Removing…" : "Remove photo"}
                </Button>
              ) : null}
            </div>
            <p id={guidanceId} className="text-xs text-muted-foreground">
              JPG or PNG, max 2 MB, square ~400×400 px recommended.
            </p>
          </div>
        ) : null}
      </div>

      {crop ? (
        <div className="flex flex-col gap-3 rounded-lg border border-border p-3" data-testid="photo-cropper">
          <p className="text-sm text-muted-foreground">
            Drag the photo (or use the arrow keys) to position it, and zoom to frame the face.
          </p>
          <canvas
            ref={canvasRef}
            width={VIEW}
            height={VIEW}
            tabIndex={0}
            role="img"
            aria-label="Photo crop preview. Use arrow keys to move the photo."
            className="size-60 cursor-move touch-none rounded-full border border-border bg-muted outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={() => (drag.current = null)}
            onPointerCancel={() => (drag.current = null)}
            onKeyDown={onKeyDown}
          />
          <div className="flex max-w-60 flex-col gap-1.5">
            <Label htmlFor={`${inputId}-zoom`}>Zoom</Label>
            <input
              id={`${inputId}-zoom`}
              type="range"
              min={1}
              max={3}
              step={0.05}
              value={crop.zoom}
              onChange={(event) => setZoom(Number(event.target.value))}
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="button" disabled={busy} onClick={() => void save()} aria-busy={upload.isPending}>
              {upload.isPending ? "Uploading…" : "Save photo"}
            </Button>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() => {
                setCrop(null);
                if (fileRef.current) fileRef.current.value = "";
              }}
            >
              Cancel
            </Button>
          </div>
        </div>
      ) : null}

      <div role="status" aria-live="polite" className="text-sm text-muted-foreground">
        {notice}
      </div>
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </section>
  );
}

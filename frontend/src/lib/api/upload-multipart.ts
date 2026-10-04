import type { PresignedPart } from "@/lib/api/media";

/**
 * VIDEO Phase 5 - sends a file to S3 staging in parts, straight from the
 * browser, using the presigned PUT URLs the backend issued (plan §13). The
 * request carries no Authorization header and no cookies: the URL's
 * signature is the only credential, and it is short-lived and limited to one
 * part of one server-chosen object.
 *
 * - Up to {@link UploadPartsOptions.concurrency} parts at a time.
 * - Each part is retried (1 s, 2 s, 4 s) on network / 5xx errors.
 * - A URL about to expire (or answered with 403) is re-signed through
 *   `presign` before the next try.
 * - `signal` aborts every in-flight request.
 * - The ETag of each part is read from the response; the bucket's CORS rule
 *   must expose it (`docs/api/video-access-management.md`).
 */

export interface CompletedPart {
  partNumber: number;
  etag: string;
}

export interface UploadPartsOptions {
  file: Blob;
  partSizeBytes: number;
  partCount: number;
  /** URLs returned with the upload (the first ≤ 100 parts). */
  initialParts: PresignedPart[];
  /** Fresh URLs for these part numbers (≤ 100 per call). */
  presign: (partNumbers: number[]) => Promise<PresignedPart[]>;
  /** Total bytes acknowledged so far. */
  onProgress?: (uploadedBytes: number, totalBytes: number) => void;
  signal?: AbortSignal;
  concurrency?: number;
  maxRetries?: number;
}

export class UploadAbortedError extends Error {
  constructor() {
    super("The upload was cancelled.");
    this.name = "UploadAbortedError";
  }
}

export class PartUploadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PartUploadError";
  }
}

const RENEW_BEFORE_EXPIRY_MS = 60_000;
const PRESIGN_BATCH = 100;

export async function uploadFileParts(options: UploadPartsOptions): Promise<CompletedPart[]> {
  const { file, partSizeBytes, partCount, presign, onProgress, signal } = options;
  const concurrency = Math.max(1, options.concurrency ?? 3);
  const maxRetries = Math.max(0, options.maxRetries ?? 3);
  const urls = new Map<number, PresignedPart>(options.initialParts.map((p) => [p.partNumber, p]));
  const loaded = new Map<number, number>();
  const completed: CompletedPart[] = [];
  const queue = Array.from({ length: partCount }, (_, i) => i + 1);

  const report = () => {
    let sum = 0;
    loaded.forEach((bytes) => (sum += bytes));
    onProgress?.(sum, file.size);
  };

  const urlFor = async (partNumber: number, forceRenew: boolean): Promise<string> => {
    const known = urls.get(partNumber);
    const fresh = known && Date.parse(known.expiresAt) - Date.now() > RENEW_BEFORE_EXPIRY_MS;
    if (known && fresh && !forceRenew) {
      return known.url;
    }
    // Re-sign this part and the next ones still waiting, in one call.
    const wanted = [partNumber, ...queue.filter((n) => n !== partNumber)].slice(0, PRESIGN_BATCH);
    for (const part of await presign(wanted)) {
      urls.set(part.partNumber, part);
    }
    const renewed = urls.get(partNumber);
    if (!renewed) {
      throw new PartUploadError("The upload link could not be renewed.");
    }
    return renewed.url;
  };

  const uploadOne = async (partNumber: number) => {
    const start = (partNumber - 1) * partSizeBytes;
    const blob = file.slice(start, Math.min(start + partSizeBytes, file.size));
    let renew = false;
    for (let attempt = 0; ; attempt++) {
      throwIfAborted(signal);
      const url = await urlFor(partNumber, renew);
      try {
        const etag = await putPart(url, blob, signal, (bytes) => {
          loaded.set(partNumber, bytes);
          report();
        });
        loaded.set(partNumber, blob.size);
        report();
        completed.push({ partNumber, etag });
        return;
      } catch (error) {
        if (error instanceof UploadAbortedError) throw error;
        loaded.set(partNumber, 0);
        report();
        const status = error instanceof HttpStatusError ? error.status : 0;
        if (attempt >= maxRetries || (status >= 400 && status < 500 && status !== 403 && status !== 408)) {
          throw new PartUploadError(
            error instanceof PartUploadError
              ? error.message
              : "Part of the file could not be sent. Check your connection and try again."
          );
        }
        renew = status === 403;
        await delay(1000 * 2 ** attempt, signal);
      }
    }
  };

  const worker = async () => {
    for (let next = queue.shift(); next !== undefined; next = queue.shift()) {
      await uploadOne(next);
    }
  };

  report();
  await Promise.all(Array.from({ length: Math.min(concurrency, partCount) }, () => worker()));
  return completed.sort((a, b) => a.partNumber - b.partNumber);
}

class HttpStatusError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`);
  }
}

function putPart(url: string, blob: Blob, signal: AbortSignal | undefined, onBytes: (bytes: number) => void) {
  return new Promise<string>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const onAbort = () => xhr.abort();
    signal?.addEventListener("abort", onAbort, { once: true });
    const done = () => signal?.removeEventListener("abort", onAbort);

    xhr.open("PUT", url);
    xhr.withCredentials = false;
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onBytes(event.loaded);
    };
    xhr.onload = () => {
      done();
      if (xhr.status >= 200 && xhr.status < 300) {
        const etag = xhr.getResponseHeader("ETag");
        if (!etag) {
          reject(new PartUploadError("The storage service did not confirm the upload. Contact support."));
          return;
        }
        resolve(etag);
        return;
      }
      reject(new HttpStatusError(xhr.status));
    };
    xhr.onerror = () => {
      done();
      reject(new HttpStatusError(0));
    };
    xhr.onabort = () => {
      done();
      reject(new UploadAbortedError());
    };
    xhr.send(blob);
  });
}

function throwIfAborted(signal: AbortSignal | undefined) {
  if (signal?.aborted) throw new UploadAbortedError();
}

function delay(ms: number, signal: AbortSignal | undefined) {
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      reject(new UploadAbortedError());
    };
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

import type { Page } from "@playwright/test";
import { apiSuccess, fulfillJson } from "./auth-mocks";

/**
 * MEDIA-FOLDERS-01: the Uploads tab also asks for folders and uploader filter
 * options. Specs that don't exercise folders register this after their own
 * `/v1/media-files` mock (later routes win) so the library renders with no
 * folders and no uploader options. Synthetic, empty data only.
 */
export async function mockMediaFolderDefaults(page: Page): Promise<void> {
  await page.route(/\/v1\/media-folders(\/|\?|$)/, (route) => fulfillJson(route, 200, apiSuccess([])));
  await page.route(/\/v1\/media-files\/uploaders(\?|$)/, (route) => fulfillJson(route, 200, apiSuccess([])));
}

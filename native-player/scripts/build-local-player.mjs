// Builds the local AOC Player that `aocplayer://` links open during
// development, so links work whether or not Vite / `tauri dev` is running.
//
// `tauri build --debug --no-bundle` compiles the UI into a debug exe (still
// talking to localhost). The exe is copied to src-tauri/target/local-player/
// because the next `tauri dev` overwrites target/debug/aoc-player.exe with a
// build whose UI lives on Vite. The scheme is registered for the current user
// to the copy; the copy also re-registers itself whenever it starts.
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

if (process.platform !== "win32") {
  console.error("The AOC Player is Windows-only; nothing to build here.");
  process.exit(1);
}

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const built = join(root, "src-tauri", "target", "debug", "aoc-player.exe");
const targetDir = join(root, "src-tauri", "target", "local-player");
const target = join(targetDir, "aoc-player.exe");

const build = spawnSync("npx tauri build --debug --no-bundle", {
  cwd: root,
  stdio: "inherit",
  shell: true,
});
if (build.status !== 0) {
  process.exit(build.status ?? 1);
}

mkdirSync(targetDir, { recursive: true });
try {
  copyFileSync(built, target);
} catch (error) {
  console.error(`Couldn't replace ${target} - close the AOC Player and run this again.`);
  console.error(String(error));
  process.exit(1);
}

const key = "HKCU\\Software\\Classes\\aocplayer";
const registrations = [
  [key, "/ve", "/d", "URL:aocplayer"],
  [key, "/v", "URL Protocol", "/d", ""],
  [`${key}\\shell\\open\\command`, "/ve", "/d", `"${target}" "%1"`],
];
for (const args of registrations) {
  const result = spawnSync("reg", ["add", ...args, "/f"], { stdio: "ignore" });
  if (result.status !== 0) {
    console.error(`Couldn't register the aocplayer:// scheme (reg add ${args[0]}).`);
    process.exit(1);
  }
}

console.log(`\nLocal AOC Player ready: ${target}`);
console.log("aocplayer:// links now open it, with or without Vite running.");

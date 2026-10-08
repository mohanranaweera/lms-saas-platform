import { defineConfig } from "vite";

// The UI is bundled into the binary; the dev server is only used by `tauri dev`.
export default defineConfig({
  clearScreen: false,
  server: {
    port: 1430,
    strictPort: true,
    host: "127.0.0.1",
    // The Rust build writes (and locks) files under src-tauri/target.
    watch: { ignored: ["**/src-tauri/**"] },
  },
  build: {
    target: "es2022",
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
  },
});

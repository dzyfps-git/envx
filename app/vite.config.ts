import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The renderer is plain static files loaded from disk by Electron: relative asset paths, no dev server needed.
export default defineConfig({
  root: "src/renderer",
  base: "./",
  plugins: [react()],
  build: { outDir: "../../dist/renderer", emptyOutDir: true, assetsInlineLimit: 0 },
});

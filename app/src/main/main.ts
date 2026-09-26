import path from "node:path";
import { app, BrowserWindow, dialog, ipcMain, shell } from "electron";
import { Engine } from "./engine";

let win: BrowserWindow | undefined;

const engine = new Engine(
  (e) => win?.webContents.send("envx:progress", e),
  (s) => win?.webContents.send("envx:engine-state", s),
);

function createWindow(): void {
  win = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: "#0b0b0d",
    title: "envx",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "..", "preload", "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  // Development aid: ENVX_APP_PREVIEW="agent=codex&effort=low" previews another agent or effort (never when packaged).
  const preview = !app.isPackaged ? process.env.ENVX_APP_PREVIEW : undefined;
  win.loadFile(path.join(__dirname, "..", "renderer", "index.html"), preview ? { search: preview } : undefined);
  // Development aid: ENVX_APP_SHOT=<png> [ENVX_APP_PAGE=<nav label>] saves a screenshot after loading, then quits.
  const shot = !app.isPackaged ? process.env.ENVX_APP_SHOT : undefined;
  if (shot) {
    win.webContents.once("did-finish-load", async () => {
      const page = process.env.ENVX_APP_PAGE;
      await new Promise((r) => setTimeout(r, 2500));
      if (page) {
        await win!.webContents.executeJavaScript(
          `document.querySelector(${JSON.stringify(`[data-nav="${page}"]`)})?.click()`);
        await new Promise((r) => setTimeout(r, 1500));
      }
      for (const click of (process.env.ENVX_APP_CLICK ?? "").split("|").filter(Boolean)) { // buttons to press, in order
        await win!.webContents.executeJavaScript(
          `[...document.querySelectorAll("button")].reverse().find(b => b.textContent.trim().startsWith(${JSON.stringify(click)}))?.click()`);
        await new Promise((r) => setTimeout(r, 1000));
      }
      await new Promise((r) => setTimeout(r, Number(process.env.ENVX_APP_WAIT ?? 0)));
      const img = await win!.webContents.capturePage();
      (await import("node:fs")).writeFileSync(shot, img.toPNG());
      app.quit();
    });
  }
  // Links open in the user's browser, never inside the app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith("https://")) void shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e) => e.preventDefault());
}

ipcMain.handle("envx:request", async (_e, op: string, params: Record<string, unknown>, progressKey?: string) => {
  try {
    return { ok: true, result: await engine.request(op, params, progressKey) };
  } catch (error) {
    return { ok: false, error };
  }
});
ipcMain.handle("envx:engine-state", () => engine.getState());
ipcMain.handle("envx:pick-folder", async (_e, title: string, defaultPath?: string) => {
  const r = await dialog.showOpenDialog(win!, { title, defaultPath, properties: ["openDirectory", "createDirectory"] });
  return r.canceled || r.filePaths.length === 0 ? null : r.filePaths[0];
});
ipcMain.handle("envx:open-external", async (_e, url: string) => {
  if (typeof url === "string" && url.startsWith("https://")) await shell.openExternal(url);
});

app.whenReady().then(() => {
  engine.start();
  createWindow();
});

app.on("window-all-closed", () => {
  engine.stop();
  app.quit();
});

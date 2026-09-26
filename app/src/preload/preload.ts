import { contextBridge, ipcRenderer } from "electron";
import type { Bridge, EngineState, ProgressEvent } from "../shared/protocol";

// The page's only way to reach the engine and the OS: a fixed list of calls, no Node access (ADR 0013).
const bridge: Bridge = {
  async request<T>(op: string, params: Record<string, unknown> = {}, progressKey?: string): Promise<T> {
    const r = await ipcRenderer.invoke("envx:request", op, params, progressKey);
    if (r.ok) return r.result as T;
    throw r.error;
  },
  onProgress(listener: (e: ProgressEvent) => void) {
    const h = (_e: unknown, p: ProgressEvent) => listener(p);
    ipcRenderer.on("envx:progress", h);
    return () => ipcRenderer.removeListener("envx:progress", h);
  },
  onEngineState(listener: (s: EngineState) => void) {
    const h = (_e: unknown, s: EngineState) => listener(s);
    ipcRenderer.on("envx:engine-state", h);
    return () => ipcRenderer.removeListener("envx:engine-state", h);
  },
  pickFolder: (title: string, defaultPath?: string) => ipcRenderer.invoke("envx:pick-folder", title, defaultPath),
  openExternal: (url: string) => ipcRenderer.invoke("envx:open-external", url),
  engineState: () => ipcRenderer.invoke("envx:engine-state"),
};

contextBridge.exposeInMainWorld("envx", bridge);

import type { Bridge, EngineError } from "../shared/protocol";

declare global {
  interface Window {
    sevli: Bridge;
  }
}

export const sevli: Bridge = window.sevli;

export function errorText(e: unknown): string {
  const err = e as Partial<EngineError> | undefined;
  return err?.message ?? String(e);
}

/** 75000000 -> "75 MB", 2500000000 -> "2.5 GB". */
export function size(bytes?: number): string {
  if (!bytes) return "unknown size";
  return bytes >= 1e9 ? `${(bytes / 1e9).toFixed(1)} GB` : `${Math.round(bytes / 1e6)} MB`;
}

/** An ISO time as "12 min ago", "3 h ago", "2 days ago". */
export function ago(iso?: string): string {
  if (!iso) return "never";
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 172800) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} days ago`;
}

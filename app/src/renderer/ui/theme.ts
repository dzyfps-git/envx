import type { Agent, AgentId } from "../../shared/protocol";

/**
 * The app takes its character from the agent in focus: its palette from which agent it is, its intensity (motion,
 * particle density, glow) from the reasoning effort that agent is actually set to. Nothing here is decorative data.
 */
export interface Palette {
  accent: string;
  accent2: string;
  /** r,g,b of the accent, for canvas drawing and rgba() in CSS. */
  rgb: [number, number, number];
  ground: string;
}

export const PALETTES: Record<AgentId | "none", Palette> = {
  claude: { accent: "#ff8f5e", accent2: "#ffc58a", rgb: [255, 143, 94], ground: "#0c0806" },
  codex: { accent: "#58d6ff", accent2: "#b4f1ff", rgb: [88, 214, 255], ground: "#05090c" },
  none: { accent: "#6c8cff", accent2: "#a9b9ff", rgb: [108, 140, 255], ground: "#08090d" },
};

/** Effort names both agents use, in order; the gauge lights one segment per step. */
export const EFFORT_STEPS = ["low", "medium", "high", "xhigh"] as const;

/** 0..1 intensity for an effort name; the agent's default (unset) sits in the middle. */
export function energy(effort?: string | null): number {
  switch ((effort ?? "").toLowerCase()) {
    case "minimal": return 0.12;
    case "low": return 0.25;
    case "medium": return 0.5;
    case "high": return 0.75;
    case "xhigh": case "extra-high": case "max": return 1;
    default: return 0.5;
  }
}

/** How many gauge segments an effort lights (0..4). */
export function effortStep(effort?: string | null): number {
  const e = energy(effort);
  return e >= 1 ? 4 : e >= 0.75 ? 3 : e >= 0.5 ? 2 : e > 0.12 ? 1 : 0;
}

export function effortLabel(effort?: string | null): string {
  if (!effort) return "Default";
  return effort.toLowerCase() === "xhigh" ? "Extra high" : effort[0].toUpperCase() + effort.slice(1);
}

/** "claude-opus-5-5" -> "Opus 5.5", "gpt-6-sol" -> "GPT-6 Sol"; unknown shapes pass through. */
export function modelLabel(model?: string | null): string {
  if (!model) return "Default model";
  const m = model.replace(/\[.*\]$/, "");
  const claude = /^claude-([a-z]+)-(\d+)(?:-(\d+))?/.exec(m);
  if (claude) return `${cap(claude[1])} ${claude[2]}${claude[3] && claude[3].length <= 2 ? "." + claude[3] : ""}`;
  if (/^(opus|sonnet|haiku|fable)$/i.test(m)) return cap(m);
  const gpt = /^gpt-([\w.]+?)(?:-(\w+))?$/i.exec(m);
  if (gpt) return `GPT-${gpt[1]}${gpt[2] ? " " + cap(gpt[2]) : ""}`;
  return m;
}

function cap(s: string): string {
  return s[0].toUpperCase() + s.slice(1);
}

/** The agent to focus first: the last one chosen, else one with sevli on, else Claude Code. */
export function initialAgent(agents: Agent[], remembered?: string | null): AgentId {
  if (remembered === "claude" || remembered === "codex") return remembered;
  return agents.find((a) => a.installed && a.sevli)?.id ?? agents.find((a) => a.installed)?.id ?? "claude";
}

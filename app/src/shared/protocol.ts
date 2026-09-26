// The engine's app protocol (envx app-server, protocol 1; ADR 0013), as the renderer sees it.

export interface Home {
  dir: string;
  exists: boolean;
  default: string;
}

export interface Baseline {
  id: string;
  name: string;
  minecraft?: string;
  loader?: string;
  downloadBytes?: number;
  diskBytes?: number;
  installed: boolean;
  supported: boolean;
}

export interface PackVersion {
  version: string;
  recipe: string;
  mods: number;
  downloadBytes: number;
  diskBytes: number;
}

export interface Pack {
  id: string;
  name: string;
  summary?: string;
  baseline: string;
  source?: string;
  versions: PackVersion[];
  supported: boolean;
  installed: boolean;
}

export interface Environment {
  name: string;
  default: boolean;
  minecraft: string;
  loader: string;
  snapshot?: number;
  checkedAt?: string;
  label?: string;
  sourceJarsDone?: number;
  sourceJarsTotal?: number;
  syncRunning: boolean;
}

export interface Status {
  envx: string;
  home: Home;
  agents: string;
  baselines: Baseline[];
  environments: Environment[];
  backgroundRunning: boolean;
}

export type AgentId = "claude" | "codex";

/** op "agents": each coding agent as its own settings describe it (sign-in files are never read). */
export interface Agent {
  id: AgentId;
  name: string;
  installed: boolean;
  /** envx is switched on for this agent. */
  envx: boolean;
  registered: boolean;
  model?: string | null;
  /** Reasoning effort as the agent's settings name it (low, medium, high, xhigh, max...); null = its default. */
  effort?: string | null;
  instructionFiles: number;
  projects: number;
}

export interface AgentActivity {
  /** One value per day, oldest first; the last is today. */
  sessions: number[];
  envxCalls: number[];
  lastModel?: string | null;
  lastSession?: string | null;
}

/** op "agents.activity": the last `days` days of each agent's sessions on this PC. */
export interface Activity {
  claude: AgentActivity;
  codex: AgentActivity;
  days: number;
}

export interface CatalogResult {
  baselines: Baseline[];
  packs: Pack[];
  error?: string;
}

export interface EngineError {
  code: string;
  message: string;
}

/** A line of progress from a long operation, tagged with the key the page gave its request. */
export interface ProgressEvent {
  key: string;
  line: string;
}

/** What the preload script exposes to the page as window.envx. */
export interface Bridge {
  /** progressKey: progress lines of this request arrive through onProgress with that key. */
  request<T>(op: string, params?: Record<string, unknown>, progressKey?: string): Promise<T>;
  onProgress(listener: (e: ProgressEvent) => void): () => void;
  onEngineState(listener: (state: EngineState) => void): () => void;
  pickFolder(title: string, defaultPath?: string): Promise<string | null>;
  openExternal(url: string): Promise<void>;
  engineState(): Promise<EngineState>;
}

export interface EngineState {
  running: boolean;
  message?: string;
}

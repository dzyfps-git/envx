import { useCallback, useEffect, useState } from "react";
import type { Activity, Agent, AgentId, CatalogResult, EngineState, Status } from "../shared/protocol";
import { sevli, errorText } from "./api";
import { Icon, IconName } from "./ui/Icon";
import { Backdrop } from "./ui/Backdrop";
import { energy, initialAgent, PALETTES } from "./ui/theme";
import { HomePage } from "./features/home/HomePage";
import { BaselinesPage } from "./features/catalog/BaselinesPage";
import { PacksPage } from "./features/catalog/PacksPage";
import { ServersPage } from "./features/servers/ServersPage";
import { AgentsPage } from "./features/agents/AgentsPage";
import { InsightsPage } from "./features/insights/InsightsPage";
import { SettingsPage } from "./features/settings/SettingsPage";

export type PageId = "home" | "baselines" | "packs" | "servers" | "agents" | "insights" | "settings";

const NAV: { id: PageId; label: string; icon: IconName; gap?: boolean }[] = [
  { id: "home", label: "Core", icon: "overview" },
  { id: "baselines", label: "Baselines", icon: "ledger", gap: true },
  { id: "packs", label: "Modpacks", icon: "findings" },
  { id: "servers", label: "Servers", icon: "servers" },
  { id: "agents", label: "Agents", icon: "guide", gap: true },
  { id: "insights", label: "Insights", icon: "reports" },
  { id: "settings", label: "Settings", icon: "settings", gap: true },
];

/** What every page reads: the engine's status, the catalog and the agents, refreshed after anything changes. */
export interface AppData {
  status?: Status;
  catalog?: CatalogResult;
  agents?: Agent[];
  activity?: Activity;
  /** The agent in focus: it sets the app's colour and intensity. */
  agent?: Agent;
  error?: string;
  refresh: () => Promise<void>;
  go: (page: PageId) => void;
}

// Development aid: the screenshot script can preview another effort (?effort=low); packaged builds never pass it.
const PREVIEW = new URLSearchParams(location.search);

function remembered(): string | null {
  try { return localStorage.getItem("sevli.agent"); } catch { return null; }
}

export function App() {
  const [page, setPage] = useState<PageId>("home");
  const [status, setStatus] = useState<Status>();
  const [catalog, setCatalog] = useState<CatalogResult>();
  const [agents, setAgents] = useState<Agent[]>();
  const [activity, setActivity] = useState<Activity>();
  const [focus, setFocus] = useState<AgentId>();
  const [error, setError] = useState<string>();
  const [engine, setEngine] = useState<EngineState>({ running: true });

  const refresh = useCallback(async () => {
    try {
      const [s, c, a] = await Promise.all([
        sevli.request<Status>("status"), sevli.request<CatalogResult>("catalog"), sevli.request<Agent[]>("agents")]);
      setStatus(s);
      setCatalog(c);
      setAgents(a);
      setFocus((f) => f ?? (PREVIEW.get("agent") as AgentId | null) ?? initialAgent(a, remembered()));
      setError(undefined);
    } catch (e) {
      setError(errorText(e));
    }
    sevli.request<Activity>("agents.activity").then(setActivity, () => undefined);
  }, []);

  useEffect(() => {
    void sevli.engineState().then(setEngine);
    const off = sevli.onEngineState(setEngine);
    void refresh();
    return off;
  }, [refresh]);

  function choose(id: AgentId) {
    setFocus(id);
    try { localStorage.setItem("sevli.agent", id); } catch { /* remembered for this run only */ }
  }

  const agent = agents?.find((a) => a.id === focus);
  const effort = PREVIEW.get("effort") ?? agent?.effort;
  const shown = agent && effort !== agent.effort ? { ...agent, effort } : agent;
  const palette = PALETTES[agent?.installed ? agent.id : "none"];
  const en = agent?.installed ? energy(effort) : 0.2;
  const data: AppData = { status, catalog, agents, activity, agent: shown, error, refresh, go: setPage };

  const style = {
    "--agent": palette.accent,
    "--agent-2": palette.accent2,
    "--agent-rgb": palette.rgb.join(","),
    "--ground": palette.ground,
    "--energy": en,
  } as React.CSSProperties;

  return (
    <div className="hud" data-agent={agent?.installed ? agent.id : "none"} style={style}>
      <Backdrop rgb={palette.rgb} energy={en} />
      <TopBar data={data} engine={engine} focus={focus} choose={choose} />
      <nav className="rail">
        {NAV.map((n) => (
          <button key={n.id} data-nav={n.label} className={"rail-item" + (page === n.id ? " active" : "") + (n.gap ? " gap" : "")}
            onClick={() => setPage(n.id)} title={n.label} aria-label={n.label}>
            <Icon name={n.icon} />
            <span>{n.label}</span>
          </button>
        ))}
        <div className="rail-foot">
          <span className={"dot " + (engine.running ? "ok" : "warn")} title={engine.running ? "Engine running" : engine.message} />
          <span className="ver">{status?.sevli ?? ""}</span>
        </div>
      </nav>
      <main className="stage" key={page}>
        {page === "home" && <HomePage data={data} />}
        {page !== "home" && (
          <div className="page">
            {page === "baselines" && <BaselinesPage data={data} />}
            {page === "packs" && <PacksPage data={data} />}
            {page === "servers" && <ServersPage data={data} />}
            {page === "agents" && <AgentsPage data={data} />}
            {page === "insights" && <InsightsPage />}
            {page === "settings" && <SettingsPage data={data} />}
          </div>
        )}
      </main>
    </div>
  );
}

function TopBar({ data, engine, focus, choose }: { data: AppData; engine: EngineState; focus?: AgentId; choose: (id: AgentId) => void }) {
  const now = useClock();
  const s = data.status;
  const envs = s?.environments ?? [];
  const busy = envs.some((e) => e.syncRunning) ? "Syncing" : s?.backgroundRunning ? "Decompiling" : "Idle";
  const state = !engine.running ? "Offline" : !s ? "Starting" : "Online";
  const agents = data.agents ?? [];
  const index = Math.max(0, agents.findIndex((a) => a.id === focus));
  return (
    <header className="topbar">
      <div className="brandline">
        <div className="wordmark display">SEVL<span>I</span></div>
        <div className="statusline">
          <span className={"pulse" + (state === "Online" ? "" : " warn")} />
          Core · {state} · {busy} · {envs.length} {envs.length === 1 ? "environment" : "environments"}
        </div>
      </div>
      <div className="switch" role="tablist" aria-label="Agent in focus" style={{ "--i": index } as React.CSSProperties}>
        <span className="switch-glow" />
        {agents.map((a) => (
          <button key={a.id} role="tab" aria-selected={a.id === focus} className={a.id === focus ? "on" : ""} onClick={() => choose(a.id)}>
            <span className={"adot" + (!a.installed ? " missing" : a.sevli ? " live" : "")} />
            {a.name}
          </button>
        ))}
      </div>
      <div className="clock">
        <div className="time display">{pad(now.getHours())}:{pad(now.getMinutes())}<span>{pad(now.getSeconds())}</span></div>
        <div className="date">{now.toLocaleDateString(undefined, { weekday: "short", day: "2-digit", month: "short" }).toUpperCase()}</div>
      </div>
    </header>
  );
}

function useClock(): Date {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);
  return now;
}

function pad(n: number): string {
  return String(n).padStart(2, "0");
}

export function PageHead({ crumb, title, sub, actions }: { crumb: string; title: string; sub?: string; actions?: React.ReactNode }) {
  return (
    <div className="page-head">
      <div className="titles">
        <div className="cap">{crumb}</div>
        <h1 className="display">{title}</h1>
        {sub && <div className="sub">{sub}</div>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

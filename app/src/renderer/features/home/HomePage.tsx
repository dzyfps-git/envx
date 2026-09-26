import type { AppData, PageId } from "../../App";
import { ago, sevli, size } from "../../api";
import { Core } from "../../ui/Core";
import { Icon, IconName } from "../../ui/Icon";
import { Sparkline } from "../../ui/Sparkline";
import { modelLabel } from "../../ui/theme";

const INSTALL: Record<string, string> = {
  claude: "https://claude.com/claude-code",
  codex: "https://developers.openai.com/codex",
};

/** The core screen: the agent in focus, what sevli holds for it, and what to do next. */
export function HomePage({ data }: { data: AppData }) {
  const s = data.status;
  const a = data.agent;
  const act = a ? data.activity?.[a.id] : undefined;
  const envs = s?.environments ?? [];
  const baselines = s?.baselines ?? [];
  const installed = baselines.filter((b) => b.installed);
  const firstBaseline = baselines.find((b) => !b.installed && b.supported);
  const calls = sum(act?.sevliCalls);
  const sessions = sum(act?.sessions);
  const done = envs.reduce((n, e) => n + (e.sourceJarsDone ?? 0), 0);
  const total = envs.reduce((n, e) => n + (e.sourceJarsTotal ?? 0), 0);
  const freshest = envs.map((e) => e.checkedAt).filter(Boolean).sort().at(-1);
  const packs = data.catalog?.packs.filter((p) => p.installed).length ?? 0;

  return (
    <div className="home">
      <section className="hero glass-edge">
        <div className="hero-text">
          <div className="cap">Agent in focus</div>
          <h1 className={"agent-name display" + (a?.installed ? "" : " dim")}>{a?.name ?? "—"}</h1>
          {a?.installed ? (
            <div className="chips">
              <span className="chip">{modelLabel(a.model ?? act?.lastModel)}</span>
              <span className={"chip" + (a.sevli ? " live" : " off")}>Sevli {a.sevli ? "on" : "off"}</span>
              <span className="chip">{a.instructionFiles} instruction {a.instructionFiles === 1 ? "file" : "files"}</span>
            </div>
          ) : (
            <div className="hero-note">
              {a?.name ?? "This agent"} isn't on this PC yet. Install it, sign in in its own app, and sevli will show up here.
              <div><button className="glow-btn" onClick={() => a && void sevli.openExternal(INSTALL[a.id])}>Get {a?.name}</button></div>
            </div>
          )}
          <p className="hero-line">
            {installed.length === 0
              ? "Nothing is indexed yet. Pick a baseline and Sevli builds its index on this PC; your agents can then look up Minecraft code instead of decompiling it."
              : `${a?.name ?? "Your agent"} can look up ${installed.map((b) => b.name).join(", ")}${envs.length ? ` and ${envs.length} ${envs.length === 1 ? "environment" : "environments"}` : ""} through Sevli.`}
          </p>
        </div>
        <Core effort={a?.effort} present={!!a?.installed} />
      </section>

      <aside className="side-col">
        <div className="glass panel-hud">
          <div className="cap">Link status</div>
          <Row k="Installed" v={a?.installed ? "Yes" : "Not found"} tone={a?.installed ? "ok" : "bad"} />
          <Row k="Sevli" v={!a?.registered ? "Not set up" : a.sevli ? "On" : "Off"} tone={a?.sevli ? "ok" : "warn"} />
          <Row k="Projects" v={a ? `${a.projects} linked` : "—"} />
          <Row k="Last session" v={act?.lastSession ? ago(act.lastSession) : "none in 14 days"} />
          <button className="line-btn" onClick={() => data.go("agents")}>Manage agents <Icon name="chevron" /></button>
        </div>
        <div className="glass panel-hud actions">
          <div className="cap">Actions</div>
          <Action icon="ledger" t={firstBaseline ? `Download ${firstBaseline.minecraft ?? ""} ${firstBaseline.loader ? firstBaseline.loader[0].toUpperCase() + firstBaseline.loader.slice(1) : firstBaseline.name}`.replace(/\s+/g, " ") : "Baselines"} s={firstBaseline ? `${size(firstBaseline.downloadBytes)} download` : `${installed.length} installed`} page="baselines" go={data.go} primary={installed.length === 0} />
          <Action icon="findings" t="Add a modpack" s={`${packs} installed`} page="packs" go={data.go} />
          <Action icon="servers" t="Connect a server" s="optional" page="servers" go={data.go} />
          <Action icon="reports" t="Insights" s="how agents used Sevli" page="insights" go={data.go} />
        </div>
      </aside>

      <section className="vitals">
        <Vital label="Sevli calls" note="last 14 days" value={data.activity ? String(calls) : "…"} spark={act?.sevliCalls} />
        <Vital label="Sessions" note={`${a?.name ?? "agent"}, last 14 days`} value={data.activity ? String(sessions) : "…"} spark={act?.sessions} />
        <Vital label="Environments" note={freshest ? `checked ${ago(freshest)}` : "none yet"} value={String(envs.length)}
          bar={envs.length ? 1 : 0} />
        <Vital label="Source decompiled" note={s?.backgroundRunning ? "working in the background" : total ? `${done} of ${total} jars` : "starts after the first index"}
          value={total ? `${Math.floor((done / total) * 100)}%` : "—"} bar={total ? done / total : 0} busy={s?.backgroundRunning} />
      </section>

      <section className="dock glass">
        <div className="dock-head">
          <div className="cap">Indexed</div>
          <div className="cap faint">Agent terminals · v2</div>
        </div>
        <div className="dock-row">
          {installed.map((b) => (
            <div className="tile" key={b.id}>
              <span className="dot ok" />
              <div><div className="t">{b.name}</div><div className="s">baseline</div></div>
            </div>
          ))}
          {envs.map((e) => (
            <div className="tile" key={e.name}>
              <span className={"dot " + (e.syncRunning ? "busy" : "ok")} />
              <div><div className="t">{e.name}</div><div className="s">{e.minecraft} · {e.loader} · {ago(e.checkedAt)}</div></div>
            </div>
          ))}
          {installed.length + envs.length === 0 && <div className="dock-empty">Nothing indexed yet. Baselines and modpacks you download appear here.</div>}
        </div>
      </section>
    </div>
  );
}

function Row({ k, v, tone }: { k: string; v: string; tone?: "ok" | "warn" | "bad" }) {
  return (
    <div className="kvrow">
      <span className="k">{k}</span>
      <span className={"v" + (tone ? " " + tone : "")}>{v}</span>
    </div>
  );
}

function Action({ icon, t, s, page, go, primary }: { icon: IconName; t: string; s: string; page: PageId; go: (p: PageId) => void; primary?: boolean }) {
  return (
    <button className={"action" + (primary ? " primary" : "")} onClick={() => go(page)}>
      <Icon name={icon} />
      <span className="a-text"><span className="t">{t}</span><span className="s">{s}</span></span>
      <Icon name="chevron" />
    </button>
  );
}

function Vital({ label, note, value, spark, bar, busy }: { label: string; note: string; value: string; spark?: number[]; bar?: number; busy?: boolean }) {
  return (
    <div className="vital glass">
      <div className="cap">{label}</div>
      <div className="v-row">
        <div className="v display">{value}</div>
        {spark && <Sparkline values={spark} />}
      </div>
      {bar !== undefined && <div className={"meter" + (busy ? " busy" : "")}><span style={{ width: `${Math.round(bar * 100)}%` }} /></div>}
      <div className="note-line">{note}</div>
    </div>
  );
}

function sum(v?: number[]): number {
  return (v ?? []).reduce((a, b) => a + b, 0);
}

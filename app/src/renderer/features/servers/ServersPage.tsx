import type { AppData } from "../../App";
import { PageHead } from "../../App";
import { ago } from "../../api";

export function ServersPage({ data }: { data: AppData }) {
  const envs = data.status?.environments ?? [];
  return (
    <>
      <PageHead crumb="Indexes" title="My servers"
        sub="Optional: connect your own server so agents know exactly what it runs. envx only reads from it, and only servers on a supported baseline can be added." />
      <div className="panel">
        <div className="panel-head"><h2>Connected</h2><span className="panel-meta">{envs.length}</span></div>
        <div className="panel-body">
          {envs.length === 0 && <div className="empty">No server connected.</div>}
          {envs.map((e) => (
            <div className="list-row" key={e.name}>
              <div className="main">
                <div className="t">{e.name}{e.label ? ` · ${e.label}` : ""}</div>
                <div className="s">Minecraft {e.minecraft} · {e.loader} · checked {ago(e.checkedAt)}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="note faint coming">Connecting a server from here (a folder, a network share or SSH) arrives in the next build.</div>
    </>
  );
}

import type { AppData } from "../../App";
import { PageHead } from "../../App";

export function AgentsPage({ data }: { data: AppData }) {
  return (
    <>
      <PageHead crumb="Use" title="Agents"
        sub="Claude Code and Codex use Sevli through their own tools. Sign in to them in their own apps; Sevli never sees your accounts." />
      <div className="panel">
        <div className="panel-head"><h2>Status</h2></div>
        <div className="panel-body">{data.status?.agents ?? "unknown"}</div>
      </div>
      <div className="note faint coming">Next build: switch Sevli on or off for each agent, see whether each is installed and signed in, open its sign-in, and choose the mod project folders where agents should use Sevli.</div>
    </>
  );
}

import type { AppData } from "../../App";
import { PageHead } from "../../App";
import { size } from "../../api";

export function PacksPage({ data }: { data: AppData }) {
  const c = data.catalog;
  const packs = c?.packs ?? [];
  return (
    <>
      <PageHead crumb="Indexes" title="Modpacks"
        sub="Modpacks prepared and tested for Sevli. Their mods come from Modrinth or CurseForge and are checked against the published list before anything is indexed." />
      {c?.error && <div className="banner tone-warn"><div className="banner-text">The catalog can't be reached right now, so modpacks aren't listed. Baselines still work.</div></div>}
      <div className="panel">
        <div className="panel-head"><h2>Catalog</h2><span className="panel-meta">{packs.length} published</span></div>
        <div className="panel-body">
          {packs.length === 0 && <div className="empty">No modpacks are published yet. Supported packs will appear here once they have been prepared and tested.</div>}
          {packs.map((p) => {
            const v = p.versions[p.versions.length - 1];
            return (
              <div className="item-row" key={p.id}>
                <div className="main">
                  <div className="t">
                    {p.name} {v && <span className="faint">{v.version}</span>}
                    {p.installed && <span className="tag ok">Installed</span>}
                    {!p.supported && <span className="tag warn">Needs a newer Sevli</span>}
                  </div>
                  {p.summary && <div className="s">{p.summary}</div>}
                  {v && (
                    <div className="figs">
                      <span><b>{v.mods}</b> mods</span>
                      <span>Download <b>{size(v.downloadBytes)}</b></span>
                      <span>Space used <b>{size(v.diskBytes)}</b></span>
                      <span>Baseline <b>{p.baseline}</b></span>
                    </div>
                  )}
                </div>
                <button disabled title="Installing modpacks arrives in the next build">Download</button>
              </div>
            );
          })}
        </div>
      </div>
    </>
  );
}

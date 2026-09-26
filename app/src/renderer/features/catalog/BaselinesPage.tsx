import { useState } from "react";
import type { AppData } from "../../App";
import { PageHead } from "../../App";
import { size } from "../../api";
import { DownloadDialog, Downloadable } from "./DownloadDialog";

export function BaselinesPage({ data }: { data: AppData }) {
  const [downloading, setDownloading] = useState<Downloadable>();
  const baselines = data.catalog?.baselines ?? data.status?.baselines ?? [];

  return (
    <>
      <PageHead crumb="Indexes" title="Baselines"
        sub="A baseline is a Minecraft version with its mod loader and names. Modpacks and servers build on one. Downloaded from Mojang and the Fabric maven, then indexed on this PC." />
      <div className="panel">
        <div className="panel-head"><h2>Supported baselines</h2><span className="panel-meta">{baselines.filter((b) => b.installed).length} installed</span></div>
        <div className="panel-body">
          {baselines.map((b) => (
            <div className="item-row" key={b.id}>
              <div className="main">
                <div className="t">
                  {b.name}
                  {b.installed && <span className="tag ok">Installed</span>}
                  {!b.supported && <span className="tag warn">Needs a newer envx</span>}
                </div>
                <div className="figs">
                  <span>Download <b>{size(b.downloadBytes)}</b></span>
                  <span>Space used <b>{size(b.diskBytes)}</b></span>
                </div>
              </div>
              {!b.installed && b.supported && <button onClick={() => setDownloading(b)}>Download</button>}
            </div>
          ))}
        </div>
      </div>
      <div className="note faint">More baselines (other Minecraft versions and loaders) appear here as envx supports them.</div>
      {downloading && data.status && (
        <DownloadDialog item={downloading} home={data.status.home} onClose={(installed) => { setDownloading(undefined); if (installed) void data.refresh(); }} />
      )}
    </>
  );
}

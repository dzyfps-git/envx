import type { AppData } from "../../App";
import { PageHead } from "../../App";

export function SettingsPage({ data }: { data: AppData }) {
  const home = data.status?.home;
  return (
    <>
      <PageHead crumb="App" title="Settings" />
      <div className="group">
        <h3>Data</h3>
        <div className="setting">
          <div>
            <div className="label">Data location</div>
            <div className="help">Where Sevli keeps its indexes. {home?.exists ? "" : "Nothing is stored yet: you choose the place with your first download."}</div>
          </div>
          <div className="control"><code>{home?.dir ?? "…"}</code></div>
        </div>
      </div>
      <div className="group">
        <h3>About</h3>
        <div className="setting">
          <div><div className="label">Version</div><div className="help">The Sevli engine this app runs.</div></div>
          <div className="control"><code>{data.status?.sevli ?? "…"}</code></div>
        </div>
        <div className="setting">
          <div>
            <div className="label">Licences</div>
            <div className="help">Sevli is MIT-licensed. The components it bundles and their licences are listed in THIRD-PARTY-NOTICES.md in the app's folder.</div>
          </div>
        </div>
        <div className="setting">
          <div>
            <div className="label">Not an official Minecraft product</div>
            <div className="help">Not approved by or associated with Mojang or Microsoft. Minecraft files come from Mojang's servers and stay on your PC.</div>
          </div>
        </div>
      </div>
      <div className="note faint coming">Next build: move the data, background work limits, automatic syncing, and adding the sevli command to your terminal.</div>
    </>
  );
}

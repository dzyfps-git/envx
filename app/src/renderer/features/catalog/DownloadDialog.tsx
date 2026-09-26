import { useEffect, useRef, useState } from "react";
import type { Home } from "../../../shared/protocol";
import { sevli, errorText, size } from "../../api";

export interface Downloadable {
  id: string;
  name: string;
  downloadBytes?: number;
  diskBytes?: number;
}

type Phase = "confirm" | "working" | "done" | "failed";

/**
 * Downloading an index: its size and where it goes first (the data location can only be chosen before the first
 * download; later it moves from Settings), then live progress. Nothing starts until the user presses Download.
 */
export function DownloadDialog({ item, home, onClose }: { item: Downloadable; home: Home; onClose: (installed: boolean) => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const [dir, setDir] = useState(home.dir);
  const [phase, setPhase] = useState<Phase>("confirm");
  const [lines, setLines] = useState<string[]>([]);
  const [error, setError] = useState<string>();
  const out = useRef<HTMLDivElement>(null);
  const key = useRef(`install-${item.id}-${Date.now()}`);
  const firstInstall = !home.exists;

  useEffect(() => {
    ref.current?.showModal();
    return sevli.onProgress((e) => {
      if (e.key === key.current) setLines((l) => [...l.slice(-200), e.line]);
    });
  }, []);

  useEffect(() => {
    out.current?.scrollTo({ top: out.current.scrollHeight });
  }, [lines]);

  async function change() {
    const picked = await sevli.pickFolder("Where should Sevli keep its data?", dir);
    if (picked) setDir(/[\\/]sevli$/i.test(picked) ? picked : `${picked}${picked.endsWith("\\") ? "" : "\\"}Sevli`);
  }

  async function start() {
    setPhase("working");
    try {
      if (firstInstall && dir !== home.dir) await sevli.request("home.set", { dir });
      await sevli.request("install", { target: item.id }, key.current);
      setPhase("done");
    } catch (e) {
      setError(errorText(e));
      setPhase("failed");
    }
  }

  async function cancel() {
    await sevli.request("cancel", { target: item.id }).catch(() => undefined);
  }

  function close() {
    ref.current?.close();
    onClose(phase === "done");
  }

  return (
    <dialog className="confirm" ref={ref} onCancel={(e) => { e.preventDefault(); if (phase !== "working") close(); }}>
      <h3>{phase === "done" ? `${item.name} is ready` : `Download ${item.name}`}</h3>
      <div className="figures">
        <div><div className="k">Download</div><div className="v">{size(item.downloadBytes)}</div></div>
        <div><div className="k">Space used</div><div className="v">{size(item.diskBytes)}</div></div>
      </div>
      {phase === "confirm" && (
        <>
          <div className="k faint" style={{ font: "500 10.5px var(--font-mono)", letterSpacing: ".09em", textTransform: "uppercase", marginBottom: 6 }}>Stored in</div>
          <div className="location">
            <code>{dir}</code>
            {firstInstall && <button className="ghost small" onClick={change}>Change</button>}
          </div>
          {!firstInstall && <div className="dialog-note">To keep data somewhere else, move it in Settings.</div>}
          <p>Files come from their official sources and are checked against their published hashes before anything is indexed.</p>
        </>
      )}
      {phase !== "confirm" && <div className="progress-out" ref={out}>{lines.join("\n") || "Starting…"}</div>}
      {phase === "failed" && <div className="note bad">{error}</div>}
      <div className="actions">
        {phase === "confirm" && (<><button className="ghost" onClick={close}>Not now</button><button autoFocus onClick={start}>Download {size(item.downloadBytes)}</button></>)}
        {phase === "working" && (<><span className="working"><span className="spin" />Downloading and indexing…</span><button className="ghost" onClick={cancel}>Cancel</button></>)}
        {(phase === "done" || phase === "failed") && <button onClick={close}>{phase === "done" ? "Done" : "Close"}</button>}
      </div>
    </dialog>
  );
}

import { EFFORT_STEPS, effortLabel, effortStep } from "./theme";

/**
 * The agent's core: a gauge of the reasoning effort it is set to (one lit segment per step), inside tick rings that
 * turn faster with more effort. Dim and still when the agent is not on this PC.
 */
export function Core({ effort, present }: { effort?: string | null; present: boolean }) {
  const lit = present && effort ? effortStep(effort) : 0;
  const unset = present && !effort; // the agent's own default: not known here, so nothing is lit
  const size = 248, c = size / 2, r = 104;
  const seg = 360 / EFFORT_STEPS.length, gap = 7;
  return (
    <div className={"core" + (present ? "" : " off")}>
      <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size}>
        <defs>
          <filter id="core-glow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="4" result="b" />
            <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
          </filter>
          <radialGradient id="core-fill">
            <stop offset="0" stopColor="var(--agent)" stopOpacity=".22" />
            <stop offset=".7" stopColor="var(--agent)" stopOpacity=".04" />
            <stop offset="1" stopColor="var(--agent)" stopOpacity="0" />
          </radialGradient>
        </defs>
        <circle cx={c} cy={c} r={r - 14} fill="url(#core-fill)" />
        <g className="ring ring-a">
          {Array.from({ length: 72 }, (_, i) => {
            const a = (i / 72) * Math.PI * 2, long = i % 6 === 0;
            const r1 = r + 10, r2 = r + (long ? 18 : 14);
            return <line key={i} x1={c + r1 * Math.cos(a)} y1={c + r1 * Math.sin(a)} x2={c + r2 * Math.cos(a)} y2={c + r2 * Math.sin(a)} />;
          })}
        </g>
        <g className="ring ring-b">
          <circle cx={c} cy={c} r={r - 24} strokeDasharray="2 7" />
        </g>
        {EFFORT_STEPS.map((step, i) => {
          const from = -90 + i * seg + gap / 2, to = from + seg - gap;
          return <path key={step} className={"seg" + (i < lit ? " on" : unset ? " unset" : "")} d={arc(c, c, r, from, to)} filter={i < lit ? "url(#core-glow)" : undefined} />;
        })}
        <circle className="hair" cx={c} cy={c} r={r - 10} />
      </svg>
      <div className="core-label">
        <div className="cap">Reasoning</div>
        <div className="val display">{present ? effortLabel(effort) : "—"}</div>
        <div className="cap faint">{!present ? "not found" : unset ? "agent default" : `${lit} / ${EFFORT_STEPS.length}`}</div>
      </div>
    </div>
  );
}

function arc(cx: number, cy: number, r: number, fromDeg: number, toDeg: number): string {
  const p = (d: number) => [cx + r * Math.cos((d * Math.PI) / 180), cy + r * Math.sin((d * Math.PI) / 180)];
  const [x1, y1] = p(fromDeg), [x2, y2] = p(toDeg);
  return `M ${x1} ${y1} A ${r} ${r} 0 ${toDeg - fromDeg > 180 ? 1 : 0} 1 ${x2} ${y2}`;
}

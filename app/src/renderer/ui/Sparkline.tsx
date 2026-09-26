import { useId } from "react";

/** A small line of daily values (oldest first) with a soft fill and today's point marked. */
export function Sparkline({ values, width = 150, height = 38 }: { values: number[]; width?: number; height?: number }) {
  const id = useId();
  if (values.length < 2) return <svg className="spark" width={width} height={height} />;
  const max = Math.max(1, ...values);
  const x = (i: number) => (i / (values.length - 1)) * (width - 4) + 2;
  const y = (v: number) => height - 3 - (v / max) * (height - 8);
  const line = values.map((v, i) => `${i ? "L" : "M"} ${x(i).toFixed(1)} ${y(v).toFixed(1)}`).join(" ");
  const last = values.length - 1;
  return (
    <svg className="spark" width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="var(--agent)" stopOpacity=".35" />
          <stop offset="1" stopColor="var(--agent)" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L ${x(last)} ${height} L ${x(0)} ${height} Z`} fill={`url(#${id})`} />
      <path d={line} className="spark-line" />
      <circle cx={x(last)} cy={y(values[last])} r="2.6" className="spark-dot" />
    </svg>
  );
}

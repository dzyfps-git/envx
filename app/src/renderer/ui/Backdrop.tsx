import { useEffect, useRef } from "react";

interface Props {
  rgb: [number, number, number];
  /** 0..1: particle count, speed and link brightness. */
  energy: number;
}

interface Dot { x: number; y: number; vx: number; vy: number; r: number; }

const MAX_DOTS = 170;
const LINK = 120;

/**
 * The field behind every screen: drifting points that link up when close, in the focused agent's colour. Colour and
 * energy ease towards new values, so switching agents flows instead of cutting. Still frame for reduced motion.
 */
export function Backdrop({ rgb, energy }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const target = useRef({ rgb, energy });
  target.current = { rgb, energy };

  useEffect(() => {
    const c = canvas.current!;
    const ctx = c.getContext("2d")!;
    const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    let w = 0, h = 0, raf = 0;
    const col = [...target.current.rgb];
    let en = target.current.energy;
    const dots: Dot[] = Array.from({ length: MAX_DOTS }, () => ({
      x: Math.random(), y: Math.random(),
      vx: (Math.random() - 0.5), vy: (Math.random() - 0.5),
      r: 0.6 + Math.random() * 1.3,
    }));

    function resize() {
      w = c.clientWidth; h = c.clientHeight;
      c.width = w * dpr; c.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    function frame() {
      const t = target.current;
      for (let i = 0; i < 3; i++) col[i] += (t.rgb[i] - col[i]) * 0.04;
      en += (t.energy - en) * 0.03;
      const [r, g, b] = col.map(Math.round);
      const n = Math.round(50 + (MAX_DOTS - 50) * en);
      const speed = 0.012 + 0.05 * en;
      ctx.clearRect(0, 0, w, h);
      const pts: [number, number][] = [];
      for (let i = 0; i < n; i++) {
        const d = dots[i];
        if (!still) {
          d.x += (d.vx * speed) / w * 16; d.y += (d.vy * speed) / h * 16;
          if (d.x < 0) d.x += 1; if (d.x > 1) d.x -= 1;
          if (d.y < 0) d.y += 1; if (d.y > 1) d.y -= 1;
        }
        pts.push([d.x * w, d.y * h]);
      }
      ctx.lineWidth = 0.6;
      for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
          const dx = pts[i][0] - pts[j][0], dy = pts[i][1] - pts[j][1];
          const dist = Math.hypot(dx, dy);
          if (dist > LINK) continue;
          ctx.strokeStyle = `rgba(${r},${g},${b},${(1 - dist / LINK) * (0.05 + 0.13 * en)})`;
          ctx.beginPath(); ctx.moveTo(pts[i][0], pts[i][1]); ctx.lineTo(pts[j][0], pts[j][1]); ctx.stroke();
        }
      }
      for (let i = 0; i < n; i++) {
        ctx.fillStyle = `rgba(${r},${g},${b},${0.25 + 0.45 * en})`;
        ctx.beginPath(); ctx.arc(pts[i][0], pts[i][1], dots[i].r, 0, Math.PI * 2); ctx.fill();
      }
      if (!still && !document.hidden) raf = requestAnimationFrame(frame);
    }

    function wake() {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(frame);
    }

    resize();
    frame();
    const ro = new ResizeObserver(() => { resize(); wake(); });
    ro.observe(c);
    document.addEventListener("visibilitychange", wake);
    return () => { cancelAnimationFrame(raf); ro.disconnect(); document.removeEventListener("visibilitychange", wake); };
  }, []);

  return (
    <div className="backdrop" aria-hidden="true">
      <div className="bd-glow" />
      <canvas ref={canvas} />
      <div className="bd-floor" />
      <div className="bd-vignette" />
    </div>
  );
}

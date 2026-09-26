// The design system's hand-drawn 20px stroke icons, drawn in currentColor at 18px.
const files = import.meta.glob("../assets/icons/*.svg", { query: "?raw", import: "default", eager: true }) as Record<string, string>;

const icons: Record<string, string> = {};
for (const [path, svg] of Object.entries(files)) {
  const name = path.slice(path.lastIndexOf("/") + 1, -4);
  icons[name] = svg.replace(/stroke="[^"]*"/, 'stroke="currentColor"').replace(/width="20" height="20"/, 'width="18" height="18"');
}

export type IconName =
  | "overview" | "findings" | "ledger" | "changes" | "server" | "servers" | "reports" | "settings"
  | "guide" | "chevron" | "check" | "alert" | "pause" | "plus" | "folder";

export function Icon({ name }: { name: IconName }) {
  return <span className="icon" aria-hidden="true" dangerouslySetInnerHTML={{ __html: icons[name] ?? "" }} />;
}

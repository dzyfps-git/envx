import { PageHead } from "../../App";

export function InsightsPage() {
  return (
    <>
      <PageHead crumb="Use" title="Insights"
        sub="What your agents still worked out by hand, read from their own session files on this PC. Nothing leaves this PC and nothing is stored." />
      <div className="note faint coming">The report arrives in the next build.</div>
    </>
  );
}

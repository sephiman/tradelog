import { Rectangle, type RectangleProps } from "recharts";
import { barColor } from "./chartTheme";

/**
 * Bar `shape` renderer that colors each bar by the sign of its datum's `pnl`.
 * Replaces the deprecated <Cell> (removed in Recharts 4): pass `shape={<SignedBar />}` to a <Bar>.
 */
export function SignedBar(props: RectangleProps & { payload?: { pnl?: number } }) {
  return <Rectangle {...props} fill={barColor(Number(props.payload?.pnl ?? 0))} />;
}

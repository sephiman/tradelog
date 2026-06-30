import type { CSSProperties } from "react";
import { Pie, PieChart, Rectangle, ResponsiveContainer, Tooltip, type RectangleProps } from "recharts";
import { barColor } from "./chartTheme";

/**
 * Bar `shape` renderer that colors each bar by the sign of its datum's `pnl`.
 * Replaces the deprecated <Cell> (removed in Recharts 4): pass `shape={<SignedBar />}` to a <Bar>.
 */
export function SignedBar(props: RectangleProps & { payload?: { pnl?: number } }) {
  return <Rectangle {...props} fill={barColor(Number(props.payload?.pnl ?? 0))} />;
}

export interface DonutDatum {
  name: string;
  value: number;
  fill: string;
}

/**
 * A donut chart. Each datum carries its own `fill` — Recharts reads the per-entry fill directly, so
 * no deprecated <Cell> children are needed. `format` renders the tooltip value.
 */
export function Donut({
  data,
  tooltipStyle,
  format,
}: {
  data: DonutDatum[];
  tooltipStyle: CSSProperties;
  format: (value: number, name: string) => string;
}) {
  return (
    <ResponsiveContainer width="100%" height="100%">
      <PieChart>
        <Pie
          data={data}
          dataKey="value"
          nameKey="name"
          innerRadius="58%"
          outerRadius="85%"
          paddingAngle={data.length > 1 ? 2 : 0}
          stroke="none"
          isAnimationActive={false}
        />
        <Tooltip contentStyle={tooltipStyle} formatter={(v, n) => [format(Number(v), String(n)), String(n)]} />
      </PieChart>
    </ResponsiveContainer>
  );
}

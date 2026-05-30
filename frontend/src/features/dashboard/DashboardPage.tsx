import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { usePnlCumulative, type PnlSeries } from "@/api/analytics";
import { useTheme } from "@/lib/theme";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";

const COLORS = ["#0ea5e9", "#22c55e", "#f59e0b", "#a855f7", "#ef4444", "#14b8a6", "#ec4899"];

interface MergedRow {
  date: string;
  [profileId: string]: string | number;
}

/** Merge per-profile series into one date-indexed dataset, carrying each profile's last value forward. */
function mergeSeries(series: PnlSeries[]): MergedRow[] {
  const dates = Array.from(new Set(series.flatMap((s) => s.points.map((p) => p.date)))).sort();
  const last: Record<string, number> = {};
  return dates.map((date) => {
    const row: MergedRow = { date };
    for (const s of series) {
      const point = s.points.find((p) => p.date === date);
      if (point) last[s.profileId] = Number(point.cumulative);
      if (last[s.profileId] !== undefined) row[s.profileId] = last[s.profileId];
    }
    return row;
  });
}

export function DashboardPage() {
  const { t } = useTranslation();
  const { resolvedTheme } = useTheme();
  const { data: series = [], isLoading } = usePnlCumulative();

  const data = useMemo(() => mergeSeries(series), [series]);
  const hasData = series.some((s) => s.points.length > 0);
  const axisColor = resolvedTheme === "dark" ? "#9ca3af" : "#6b7280";
  const gridColor = resolvedTheme === "dark" ? "#374151" : "#e5e7eb";

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("dashboard.title")}</h1>
      <Card>
        <CardHeader>
          <h2 className="font-semibold">{t("dashboard.cumulativePnl")}</h2>
        </CardHeader>
        <CardBody>
          {isLoading ? (
            <p className="text-sm text-gray-500">{t("common.loading")}</p>
          ) : !hasData ? (
            <p className="py-12 text-center text-sm text-gray-500 dark:text-gray-400">{t("dashboard.noData")}</p>
          ) : (
            <div className="h-80 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={data} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke={gridColor} />
                  <XAxis dataKey="date" stroke={axisColor} fontSize={12} />
                  <YAxis stroke={axisColor} fontSize={12} width={64} />
                  <Tooltip
                    contentStyle={{
                      backgroundColor: resolvedTheme === "dark" ? "#1f2937" : "#fff",
                      border: `1px solid ${gridColor}`,
                      borderRadius: 8,
                      fontSize: 12,
                    }}
                  />
                  <Legend />
                  {series.map((s, i) => (
                    <Line
                      key={s.profileId}
                      type="monotone"
                      dataKey={s.profileId}
                      name={s.profileName}
                      stroke={COLORS[i % COLORS.length]}
                      dot={false}
                      strokeWidth={2}
                      connectNulls
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  );
}

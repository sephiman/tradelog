import type { ReactNode } from "react";
import { Card, CardBody, CardHeader } from "@/components/ui/primitives";
import { InfoTooltip } from "./InfoTooltip";

/** A dashboard card: title, an optional info tooltip, and a body. Used by every metric/chart. */
export function MetricCard({
  title,
  info,
  action,
  children,
  className,
}: {
  title: ReactNode;
  info?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <Card className={className}>
      <CardHeader className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h3 className="font-semibold">{title}</h3>
          {info && <InfoTooltip text={info} />}
        </div>
        {action}
      </CardHeader>
      <CardBody>{children}</CardBody>
    </Card>
  );
}

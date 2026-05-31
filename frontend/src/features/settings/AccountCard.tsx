import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { Card, CardBody, CardHeader, Label, Select } from "@/components/ui/primitives";

const FALLBACK_ZONES = [
  "UTC",
  "Europe/London",
  "Europe/Madrid",
  "Europe/Berlin",
  "America/New_York",
  "America/Chicago",
  "America/Los_Angeles",
  "America/Sao_Paulo",
  "Asia/Tokyo",
  "Asia/Singapore",
  "Australia/Sydney",
];

const supportedValuesOf = (Intl as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
const TIME_ZONES = supportedValuesOf ? supportedValuesOf("timeZone") : FALLBACK_ZONES;

/** Account settings: email (read-only) and the analytics time zone. */
export function AccountCard() {
  const { t } = useTranslation();
  const { user, setTimeZone } = useAuth();
  if (!user) return null;

  // The stored zone may be absent from the runtime's list — keep it selectable so it isn't lost.
  const zones = TIME_ZONES.includes(user.timeZone) ? TIME_ZONES : [user.timeZone, ...TIME_ZONES];

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("settings.account")}</h2>
      </CardHeader>
      <CardBody className="space-y-4">
        <div>
          <Label>{t("settings.email")}</Label>
          <p className="text-sm text-gray-600 dark:text-gray-300">{user.email}</p>
        </div>
        <div>
          <Label htmlFor="tz-select">{t("settings.timeZone")}</Label>
          <Select
            id="tz-select"
            className="max-w-xs"
            value={user.timeZone}
            onChange={(e) => void setTimeZone(e.target.value)}
          >
            {zones.map((z) => (
              <option key={z} value={z}>
                {z}
              </option>
            ))}
          </Select>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{t("settings.timeZoneHint")}</p>
        </div>
      </CardBody>
    </Card>
  );
}

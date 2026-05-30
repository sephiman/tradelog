import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Card, CardBody } from "@/components/ui/primitives";

export function NoActiveProfile() {
  const { t } = useTranslation();
  return (
    <Card>
      <CardBody className="text-center text-sm text-gray-500 dark:text-gray-400">
        <p className="mb-3">{t("profiles.none")}</p>
        <Link to="/settings" className="text-primary hover:underline">
          {t("profiles.createFirst")}
        </Link>
      </CardBody>
    </Card>
  );
}

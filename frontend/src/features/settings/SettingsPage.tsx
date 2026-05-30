import { useTranslation } from "react-i18next";
import { useActiveProfile } from "@/features/profiles/ActiveProfile";
import { ProfilesCard } from "./ProfilesCard";
import { DataSourcesCard } from "./DataSourcesCard";
import { TaxonomyCard } from "./TaxonomyCard";

export function SettingsPage() {
  const { t } = useTranslation();
  const { activeProfile } = useActiveProfile();

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("nav.settings")}</h1>
      <ProfilesCard />
      {activeProfile && <DataSourcesCard profileId={activeProfile.id} profileName={activeProfile.name} />}
      <TaxonomyCard />
    </div>
  );
}

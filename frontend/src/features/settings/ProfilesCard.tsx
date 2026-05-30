import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCreateProfile,
  useDeleteProfile,
  useProfiles,
  type ProfileKind,
} from "@/api/profiles";
import { Badge, Button, Card, CardBody, CardHeader, Input, Select, Textarea } from "@/components/ui/primitives";

export function ProfilesCard() {
  const { t } = useTranslation();
  const { data: profiles = [] } = useProfiles();
  const createProfile = useCreateProfile();
  const deleteProfile = useDeleteProfile();

  const [kind, setKind] = useState<ProfileKind>("PERSONAL");
  const [name, setName] = useState("");
  const [note, setNote] = useState("");

  const onCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    createProfile.mutate(
      { kind, name: name.trim(), strategyNote: note.trim() || null },
      { onSuccess: () => { setName(""); setNote(""); setKind("PERSONAL"); } },
    );
  };

  return (
    <Card>
      <CardHeader><h2 className="font-semibold">{t("profiles.title")}</h2></CardHeader>
      <CardBody className="space-y-4">
        <ul className="divide-y divide-border dark:divide-gray-700">
          {profiles.map((p) => (
            <li key={p.id} className="flex items-center gap-3 py-2">
              <Badge tone={p.kind === "BOT" ? "sky" : "gray"}>{p.kind === "BOT" ? t("profiles.bot") : t("profiles.personal")}</Badge>
              <div className="min-w-0 flex-1">
                <p className="font-medium">{p.name}</p>
                {p.strategyNote && <p className="truncate text-xs text-gray-500 dark:text-gray-400">{p.strategyNote}</p>}
              </div>
              <Button
                variant="ghost"
                onClick={() => { if (confirm(t("profiles.deleteConfirm"))) deleteProfile.mutate(p.id); }}
              >
                {t("common.delete")}
              </Button>
            </li>
          ))}
          {profiles.length === 0 && <li className="py-2 text-sm text-gray-500 dark:text-gray-400">{t("profiles.none")}</li>}
        </ul>

        <form onSubmit={onCreate} className="flex flex-wrap items-end gap-3 border-t border-border pt-4 dark:border-gray-700">
          <label className="flex flex-col gap-1">
            <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("profiles.kind")}</span>
            <Select className="w-32" value={kind} onChange={(e) => setKind(e.target.value as ProfileKind)}>
              <option value="PERSONAL">{t("profiles.personal")}</option>
              <option value="BOT">{t("profiles.bot")}</option>
            </Select>
          </label>
          <label className="flex flex-1 flex-col gap-1">
            <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("profiles.name")}</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("profiles.new")} />
          </label>
          {kind === "BOT" && (
            <label className="flex w-full flex-col gap-1">
              <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{t("profiles.strategyNote")}</span>
              <Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
            </label>
          )}
          <Button type="submit" disabled={createProfile.isPending || !name.trim()}>{t("common.create")}</Button>
        </form>
      </CardBody>
    </Card>
  );
}

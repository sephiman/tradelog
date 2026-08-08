import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  isArchived,
  useCreateTag,
  useDeleteTag,
  useSetTagArchived,
  useTaxonomy,
  useUpdateTag,
  type Tag,
  type TagGroup,
} from "@/api/taxonomy";
import { Button, Card, CardBody, CardHeader, Input } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";

export function TaxonomyCard() {
  const { t } = useTranslation();
  const { data: groups = [] } = useTaxonomy();

  return (
    <Card>
      <CardHeader><h2 className="font-semibold">{t("taxonomy.title")}</h2></CardHeader>
      <CardBody className="space-y-5">
        <p className="text-xs text-gray-500 dark:text-gray-400">{t("taxonomy.hint")}</p>
        {groups.map((g) => <GroupEditor key={g.id} group={g} />)}
      </CardBody>
    </Card>
  );
}

function GroupEditor({ group }: { group: TagGroup }) {
  const { t } = useTranslation();
  const createTag = useCreateTag();
  const [newTag, setNewTag] = useState("");

  const active = group.tags.filter((tag) => !isArchived(tag));
  const archived = group.tags.filter(isArchived);

  return (
    <div className="rounded-md border border-border p-3">
      <p className="mb-2 text-sm font-medium">{group.name}</p>
      <ul className="space-y-2">
        {active.map((tag) => <TagRow key={tag.id} groupId={group.id} tag={tag} />)}
      </ul>
      <div className="mt-3 flex items-center gap-2">
        <Input
          className="flex-1"
          value={newTag}
          onChange={(e) => setNewTag(e.target.value)}
          placeholder={t("taxonomy.tagName")}
        />
        <Button
          variant="secondary"
          disabled={!newTag.trim() || createTag.isPending}
          onClick={() => createTag.mutate({ groupId: group.id, name: newTag.trim() }, { onSuccess: () => setNewTag("") })}
        >
          {t("taxonomy.newTag")}
        </Button>
      </div>
      {archived.length > 0 && (
        <div className="mt-4 border-t border-border pt-3">
          <p className="mb-1 text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">
            {t("taxonomy.archivedSection")}
          </p>
          <p className="mb-2 text-xs text-gray-500 dark:text-gray-400">{t("taxonomy.archivedHint")}</p>
          <ul className="space-y-2">
            {archived.map((tag) => <TagRow key={tag.id} groupId={group.id} tag={tag} />)}
          </ul>
        </div>
      )}
    </div>
  );
}

function TagRow({ groupId, tag }: { groupId: string; tag: Tag }) {
  const { t } = useTranslation();
  const updateTag = useUpdateTag();
  const deleteTag = useDeleteTag();
  const setArchived = useSetTagArchived();
  const archived = isArchived(tag);

  return (
    <li className={cn("flex items-center gap-2", archived && "opacity-60")}>
      <Input
        className="flex-1"
        defaultValue={tag.name}
        onBlur={(e) => {
          const v = e.target.value.trim();
          if (v && v !== tag.name) updateTag.mutate({ groupId, tagId: tag.id, name: v });
        }}
      />
      <Button
        variant="ghost"
        disabled={setArchived.isPending}
        onClick={() => setArchived.mutate({ groupId, tagId: tag.id, archived: !archived })}
      >
        {archived ? t("taxonomy.unarchive") : t("taxonomy.archive")}
      </Button>
      <Button variant="ghost" onClick={() => deleteTag.mutate({ groupId, tagId: tag.id })}>
        {t("common.delete")}
      </Button>
    </li>
  );
}

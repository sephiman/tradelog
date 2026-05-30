import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  useCreateTag,
  useDeleteTag,
  useTaxonomy,
  useUpdateTag,
  type TagGroup,
} from "@/api/taxonomy";
import { Button, Card, CardBody, CardHeader, Input } from "@/components/ui/primitives";

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
  const updateTag = useUpdateTag();
  const deleteTag = useDeleteTag();
  const [newTag, setNewTag] = useState("");

  return (
    <div className="rounded-md border border-border p-3 dark:border-gray-700">
      <p className="mb-2 text-sm font-medium">{group.name}</p>
      <ul className="space-y-2">
        {group.tags.map((tag) => (
          <li key={tag.id} className="flex items-center gap-2">
            <Input
              className="flex-1"
              defaultValue={tag.name}
              onBlur={(e) => {
                const v = e.target.value.trim();
                if (v && v !== tag.name) updateTag.mutate({ groupId: group.id, tagId: tag.id, name: v });
              }}
            />
            <Button variant="ghost" onClick={() => deleteTag.mutate({ groupId: group.id, tagId: tag.id })}>
              {t("common.delete")}
            </Button>
          </li>
        ))}
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
    </div>
  );
}

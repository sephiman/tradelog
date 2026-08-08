import { useTranslation } from "react-i18next";
import { isArchived, type Tag } from "@/api/taxonomy";

/** Option label for a tag: archived ones stay available to filter by, but say that they are retired. */
export function useTagLabel() {
  const { t } = useTranslation();
  return (tag: Tag) => (isArchived(tag) ? t("taxonomy.archivedOption", { name: tag.name }) : tag.name);
}

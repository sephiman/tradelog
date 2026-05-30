import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Tag {
  id: string;
  code: string;
  name: string;
  sortOrder: number;
}

export interface TagGroup {
  id: string;
  code: string;
  name: string;
  sortOrder: number;
  tags: Tag[];
}

export function useTaxonomy() {
  return useQuery({
    queryKey: ["taxonomy"],
    queryFn: async () => (await apiClient.get<TagGroup[]>("/taxonomy/groups")).data,
  });
}

function useInvalidate() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ["taxonomy"] });
    qc.invalidateQueries({ queryKey: ["positions"] });
  };
}

export function useCreateGroup() {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async (name: string) => (await apiClient.post<TagGroup>("/taxonomy/groups", { name })).data,
    onSuccess: inv,
  });
}

export function useCreateTag() {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async ({ groupId, name }: { groupId: string; name: string }) =>
      (await apiClient.post<Tag>(`/taxonomy/groups/${groupId}/tags`, { name })).data,
    onSuccess: inv,
  });
}

export function useUpdateTag() {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async ({ groupId, tagId, name }: { groupId: string; tagId: string; name: string }) =>
      (await apiClient.put<Tag>(`/taxonomy/groups/${groupId}/tags/${tagId}`, { name })).data,
    onSuccess: inv,
  });
}

export function useDeleteTag() {
  const inv = useInvalidate();
  return useMutation({
    mutationFn: async ({ groupId, tagId }: { groupId: string; tagId: string }) => {
      await apiClient.delete(`/taxonomy/groups/${groupId}/tags/${tagId}`);
    },
    onSuccess: inv,
  });
}

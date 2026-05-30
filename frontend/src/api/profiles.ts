import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export type ProfileKind = "PERSONAL" | "BOT";

export interface Profile {
  id: string;
  kind: ProfileKind;
  name: string;
  strategyNote: string | null;
  createdAt: string;
}

export interface ProfileRequest {
  kind: ProfileKind;
  name: string;
  strategyNote?: string | null;
}

export function useProfiles() {
  return useQuery({
    queryKey: ["profiles"],
    queryFn: async () => (await apiClient.get<Profile[]>("/profiles")).data,
  });
}

export function useCreateProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: ProfileRequest) => (await apiClient.post<Profile>("/profiles", body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["profiles"] }),
  });
}

export function useUpdateProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: ProfileRequest }) =>
      (await apiClient.put<Profile>(`/profiles/${id}`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["profiles"] }),
  });
}

export function useDeleteProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await apiClient.delete(`/profiles/${id}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["profiles"] }),
  });
}

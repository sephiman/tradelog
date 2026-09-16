import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { EmailChangeCard } from "./EmailChangeCard";
import { requestEmailChange, useAuthFeatures } from "@/api/auth";
import i18n from "@/i18n";

const refresh = vi.fn();

vi.mock("@/api/auth", () => ({
  useAuthFeatures: vi.fn(),
  requestEmailChange: vi.fn(),
}));
vi.mock("@/auth/AuthContext", () => ({
  useAuth: () => ({ user: { id: "u1", email: "old@example.com", locale: "en", timeZone: "UTC" }, refresh }),
}));

function renderCard(emailChangeVerified: boolean) {
  vi.mocked(useAuthFeatures).mockReturnValue({
    data: { passwordReset: emailChangeVerified, emailChangeVerified },
  } as unknown as ReturnType<typeof useAuthFeatures>);
  render(<EmailChangeCard />);
}

function submit(email: string, password: string) {
  fireEvent.change(screen.getByLabelText(i18n.t("settings.newEmail")), { target: { value: email } });
  fireEvent.change(screen.getByLabelText(i18n.t("settings.currentPassword")), { target: { value: password } });
  fireEvent.click(screen.getByRole("button", { name: i18n.t("settings.changeEmailCta") }));
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("email change card", () => {
  it("reports that a confirmation link was mailed, naming the address", async () => {
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "pending", email: "new@example.com" });
    renderCard(true);

    submit("new@example.com", "password1234");

    expect(await screen.findByRole("status")).toHaveTextContent("new@example.com");
    expect(requestEmailChange).toHaveBeenCalledWith("new@example.com", "password1234");
    expect(refresh).not.toHaveBeenCalled();
  });

  it("picks up the new identity when the change applied straight away", async () => {
    vi.mocked(requestEmailChange).mockResolvedValue({ status: "applied", email: "new@example.com" });
    renderCard(false);

    expect(screen.getByText(i18n.t("settings.changeEmailDirectHint"))).toBeInTheDocument();
    submit("new@example.com", "password1234");

    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("requires a password and a well-formed address before calling the server", async () => {
    renderCard(true);

    submit("not-an-address", "");

    expect(await screen.findByText(i18n.t("validation.emailInvalid"))).toBeInTheDocument();
    expect(screen.getByText(i18n.t("validation.fieldRequired"))).toBeInTheDocument();
    expect(requestEmailChange).not.toHaveBeenCalled();
  });

  it("surfaces a rejected password from the server", async () => {
    vi.mocked(requestEmailChange).mockRejectedValue({
      response: { data: { code: "PASSWORD_MISMATCH", message: "Current password is incorrect" } },
    });
    renderCard(true);

    submit("new@example.com", "wrong-password");

    expect(await screen.findByText("Current password is incorrect")).toBeInTheDocument();
  });
});

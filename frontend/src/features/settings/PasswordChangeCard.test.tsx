import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PasswordChangeCard } from "./PasswordChangeCard";
import { changePassword } from "@/api/auth";
import i18n from "@/i18n";

vi.mock("@/api/auth", () => ({ changePassword: vi.fn() }));

function submit(current: string, next: string, confirmation: string) {
  fireEvent.change(screen.getByLabelText(i18n.t("settings.currentPassword")), { target: { value: current } });
  fireEvent.change(screen.getByLabelText(i18n.t("settings.newPassword")), { target: { value: next } });
  fireEvent.change(screen.getByLabelText(i18n.t("settings.confirmNewPassword")), { target: { value: confirmation } });
  fireEvent.click(screen.getByRole("button", { name: i18n.t("settings.changePasswordCta") }));
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("password change card", () => {
  it("submits the pair and empties the form", async () => {
    vi.mocked(changePassword).mockResolvedValue();
    render(<PasswordChangeCard />);

    submit("password1234", "brand-new-pass", "brand-new-pass");

    await waitFor(() => expect(changePassword).toHaveBeenCalledWith("password1234", "brand-new-pass"));
    expect(screen.getByLabelText(i18n.t("settings.currentPassword"))).toHaveValue("");
    expect(screen.getByLabelText(i18n.t("settings.newPassword"))).toHaveValue("");
  });

  it("refuses a short password and a mismatched confirmation before calling the server", async () => {
    render(<PasswordChangeCard />);

    submit("password1234", "short", "different");

    expect(await screen.findByText(i18n.t("validation.passwordTooShort"))).toBeInTheDocument();
    expect(screen.getByText(i18n.t("validation.passwordConfirmationMismatch"))).toBeInTheDocument();
    expect(changePassword).not.toHaveBeenCalled();
  });

  it("surfaces a wrong current password from the server", async () => {
    vi.mocked(changePassword).mockRejectedValue({
      response: { data: { code: "PASSWORD_MISMATCH", message: "Current password is incorrect" } },
    });
    render(<PasswordChangeCard />);

    submit("not-the-password", "brand-new-pass", "brand-new-pass");

    expect(await screen.findByText("Current password is incorrect")).toBeInTheDocument();
  });
});

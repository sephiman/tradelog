import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/lib/theme";
import { ResetPasswordPage } from "@/auth/ResetPasswordPage";
import { confirmPasswordReset, validatePasswordResetToken } from "@/api/auth";
import i18n from "@/i18n";

vi.mock("@/api/auth", () => ({
  useAuthFeatures: () => ({ data: { passwordReset: true, emailChangeVerified: true } }),
  validatePasswordResetToken: vi.fn(),
  confirmPasswordReset: vi.fn(),
}));

function renderAt(search: string) {
  render(
    <ThemeProvider>
      <MemoryRouter initialEntries={[`/reset-password${search}`]}>
        <Routes>
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/login" element={<p>login page</p>} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

async function submit(password: string, confirmation: string) {
  fireEvent.change(await screen.findByLabelText(i18n.t("auth.newPassword")), { target: { value: password } });
  fireEvent.change(screen.getByLabelText(i18n.t("auth.confirmNewPassword")), { target: { value: confirmation } });
  fireEvent.click(screen.getByRole("button", { name: i18n.t("auth.setNewPassword") }));
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("reset password page", () => {
  it("rejects a mismatched confirmation before calling the server", async () => {
    vi.mocked(validatePasswordResetToken).mockResolvedValue();
    renderAt("?token=abc");

    await submit("password1234", "password9999");

    expect(await screen.findByText(i18n.t("validation.passwordConfirmationMismatch"))).toBeInTheDocument();
    expect(confirmPasswordReset).not.toHaveBeenCalled();
  });

  it("submits matching passwords and lands on the login page flagged as reset", async () => {
    vi.mocked(validatePasswordResetToken).mockResolvedValue();
    vi.mocked(confirmPasswordReset).mockResolvedValue();
    renderAt("?token=abc");

    await submit("password1234", "password1234");

    expect(await screen.findByText("login page")).toBeInTheDocument();
    expect(confirmPasswordReset).toHaveBeenCalledWith("abc", "password1234");
  });

  it("offers a new link when the token is rejected, without saying why", async () => {
    vi.mocked(validatePasswordResetToken).mockRejectedValue({
      response: { data: { code: "PASSWORD_RESET_TOKEN_INVALID", message: "nope" } },
    });
    renderAt("?token=expired");

    expect(await screen.findByRole("alert")).toHaveTextContent(i18n.t("auth.resetLinkInvalid"));
    expect(screen.getByRole("link", { name: i18n.t("auth.requestNewLink") })).toHaveAttribute("href", "/forgot-password");
    expect(screen.queryByLabelText(i18n.t("auth.newPassword"))).not.toBeInTheDocument();
  });

  it("treats a missing token as an invalid link without asking the server", () => {
    renderAt("");

    expect(screen.getByRole("alert")).toHaveTextContent(i18n.t("auth.resetLinkInvalid"));
    expect(validatePasswordResetToken).not.toHaveBeenCalled();
  });
});

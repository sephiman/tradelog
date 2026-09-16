import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/lib/theme";
import { ForgotPasswordPage } from "@/auth/ForgotPasswordPage";
import { requestPasswordReset, useAuthFeatures } from "@/api/auth";
import i18n from "@/i18n";

vi.mock("@/api/auth", () => ({
  useAuthFeatures: vi.fn(),
  requestPasswordReset: vi.fn(),
}));

function renderPage(passwordReset: boolean | undefined = true) {
  vi.mocked(useAuthFeatures).mockReturnValue({
    data: passwordReset === undefined ? undefined : { passwordReset, emailChangeVerified: passwordReset },
  } as unknown as ReturnType<typeof useAuthFeatures>);
  render(
    <ThemeProvider>
      <MemoryRouter initialEntries={["/forgot-password"]}>
        <ForgotPasswordPage />
      </MemoryRouter>
    </ThemeProvider>,
  );
}

function submit(email: string) {
  fireEvent.change(screen.getByLabelText(i18n.t("auth.email")), { target: { value: email } });
  fireEvent.click(screen.getByRole("button", { name: i18n.t("auth.sendResetLink") }));
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("forgot password page", () => {
  it("says the same thing after submitting, without revealing whether the account exists", async () => {
    vi.mocked(requestPasswordReset).mockResolvedValue();
    renderPage();

    submit("someone@example.com");

    expect(await screen.findByRole("status")).toHaveTextContent(i18n.t("auth.resetRequested"));
    expect(requestPasswordReset).toHaveBeenCalledWith("someone@example.com");
    expect(screen.queryByLabelText(i18n.t("auth.email"))).not.toBeInTheDocument();
  });

  it("rejects a malformed address before calling the server", async () => {
    renderPage();

    submit("not-an-address");

    expect(await screen.findByText(i18n.t("validation.emailInvalid"))).toBeInTheDocument();
    expect(requestPasswordReset).not.toHaveBeenCalled();
  });

  it("offers no form at all when the instance has no mail configured", () => {
    renderPage(false);

    expect(screen.getByText(i18n.t("auth.resetUnavailable"))).toBeInTheDocument();
    expect(screen.queryByLabelText(i18n.t("auth.email"))).not.toBeInTheDocument();
  });
});

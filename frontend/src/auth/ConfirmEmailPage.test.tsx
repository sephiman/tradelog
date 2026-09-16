import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ThemeProvider } from "@/lib/theme";
import { ConfirmEmailPage } from "@/auth/ConfirmEmailPage";
import { confirmEmailChange } from "@/api/auth";
import i18n from "@/i18n";

vi.mock("@/api/auth", () => ({
  confirmEmailChange: vi.fn(),
}));

function LoginProbe() {
  const { search } = useLocation();
  return <p>login page{search}</p>;
}

function renderAt(search: string) {
  render(
    <ThemeProvider>
      <MemoryRouter initialEntries={[`/confirm-email${search}`]}>
        <Routes>
          <Route path="/confirm-email" element={<ConfirmEmailPage />} />
          <Route path="/login" element={<LoginProbe />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  );
}

beforeAll(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("confirm email page", () => {
  it("confirms the token and sends the user to log in with the new address", async () => {
    vi.mocked(confirmEmailChange).mockResolvedValue();
    renderAt("?token=abc");

    expect(await screen.findByText("login page?email=changed")).toBeInTheDocument();
    expect(confirmEmailChange).toHaveBeenCalledWith("abc");
  });

  it("shows one neutral message when the link is rejected", async () => {
    vi.mocked(confirmEmailChange).mockRejectedValue({
      response: { data: { code: "EMAIL_CHANGE_TOKEN_INVALID", message: "nope" } },
    });
    renderAt("?token=stale");

    expect(await screen.findByRole("alert")).toHaveTextContent(i18n.t("auth.emailChangeLinkInvalid"));
  });

  it("treats a missing token as an invalid link without asking the server", () => {
    renderAt("");

    expect(screen.getByRole("alert")).toHaveTextContent(i18n.t("auth.emailChangeLinkInvalid"));
    expect(confirmEmailChange).not.toHaveBeenCalled();
  });
});

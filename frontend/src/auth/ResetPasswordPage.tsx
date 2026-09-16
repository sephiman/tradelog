import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { confirmPasswordReset, useAuthFeatures, validatePasswordResetToken } from "@/api/auth";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, FieldError, Input, Label } from "@/components/ui/primitives";
import { Logo } from "@/components/ui/Logo";

type TokenState = "checking" | "valid" | "invalid";

export function ResetPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const features = useAuthFeatures();
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [tokenState, setTokenState] = useState<TokenState>(token ? "checking" : "invalid");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [confirmationError, setConfirmationError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    void (async () => {
      try {
        await validatePasswordResetToken(token);
        if (!cancelled) setTokenState("valid");
      } catch {
        // Unknown, expired and used all come back as one code; the page never learns which.
        if (!cancelled) setTokenState("invalid");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    let invalid = false;
    if (!password) {
      setPasswordError(t("validation.fieldRequired"));
      invalid = true;
    } else if (password.length < 8) {
      setPasswordError(t("validation.passwordTooShort"));
      invalid = true;
    }
    if (confirmation !== password) {
      setConfirmationError(t("validation.passwordConfirmationMismatch"));
      invalid = true;
    }
    if (invalid) return;
    setSubmitting(true);
    try {
      await confirmPasswordReset(token, password);
      navigate("/login?reset=done", { replace: true });
    } catch (err) {
      if (asApiError(err).code === "PASSWORD_RESET_TOKEN_INVALID") setTokenState("invalid");
      else setError(asApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  const unavailable = features.data ? !features.data.passwordReset : false;

  return (
    <div className="flex min-h-full items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardBody>
          <Logo className="mx-auto mb-2 h-12 w-auto" />
          <p className="mb-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("auth.resetPassword")}</p>
          {unavailable ? (
            <p className="text-sm text-gray-600 dark:text-gray-300">{t("auth.resetUnavailable")}</p>
          ) : tokenState === "checking" ? (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
          ) : tokenState === "invalid" ? (
            <>
              <p role="alert" className="text-sm text-gray-700 dark:text-gray-200">
                {t("auth.resetLinkInvalid")}
              </p>
              <Link to="/forgot-password" className="mt-2 block text-sm text-primary hover:underline">
                {t("auth.requestNewLink")}
              </Link>
            </>
          ) : (
            <form noValidate onSubmit={onSubmit} className="space-y-4">
              <div>
                <Label htmlFor="reset-new-password">{t("auth.newPassword")}</Label>
                <Input
                  id="reset-new-password"
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  value={password}
                  invalid={!!passwordError}
                  onChange={(e) => {
                    setPassword(e.target.value);
                    if (passwordError) setPasswordError(null);
                  }}
                />
                <FieldError message={passwordError} />
              </div>
              <div>
                <Label htmlFor="reset-confirm-password">{t("auth.confirmNewPassword")}</Label>
                <Input
                  id="reset-confirm-password"
                  type="password"
                  autoComplete="new-password"
                  value={confirmation}
                  invalid={!!confirmationError}
                  onChange={(e) => {
                    setConfirmation(e.target.value);
                    if (confirmationError) setConfirmationError(null);
                  }}
                />
                <FieldError message={confirmationError} />
              </div>
              <FieldError message={error} />
              <Button type="submit" className="w-full" disabled={submitting}>
                {t("auth.setNewPassword")}
              </Button>
            </form>
          )}
          <p className="mt-4 text-center text-sm">
            <Link to="/login" className="text-primary hover:underline">
              {t("auth.backToLogin")}
            </Link>
          </p>
        </CardBody>
      </Card>
    </div>
  );
}

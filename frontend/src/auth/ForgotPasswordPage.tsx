import { useState } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { requestPasswordReset, useAuthFeatures } from "@/api/auth";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, FieldError, Input, Label } from "@/components/ui/primitives";
import { Logo } from "@/components/ui/Logo";

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const features = useAuthFeatures();
  const [email, setEmail] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    const trimmed = email.trim();
    if (!trimmed) {
      setEmailError(t("validation.fieldRequired"));
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
      setEmailError(t("validation.emailInvalid"));
      return;
    }
    setSubmitting(true);
    try {
      await requestPasswordReset(trimmed);
      setSent(true);
    } catch (err) {
      setError(asApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-full items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardBody>
          <Logo className="mx-auto mb-2 h-12 w-auto" />
          <p className="mb-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("auth.forgotPassword")}</p>
          {features.data && !features.data.passwordReset ? (
            <p className="text-sm text-gray-600 dark:text-gray-300">{t("auth.resetUnavailable")}</p>
          ) : sent ? (
            // The same text whether or not the address is known; the server answers identically too.
            <p role="status" className="text-sm text-gray-700 dark:text-gray-200">
              {t("auth.resetRequested")}
            </p>
          ) : (
            <form noValidate onSubmit={onSubmit} className="space-y-4">
              <p className="text-sm text-gray-600 dark:text-gray-300">{t("auth.forgotPasswordIntro")}</p>
              <div>
                <Label htmlFor="forgot-email">{t("auth.email")}</Label>
                <Input
                  id="forgot-email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  invalid={!!emailError}
                  onChange={(e) => {
                    setEmail(e.target.value);
                    if (emailError) setEmailError(null);
                  }}
                />
                <FieldError message={emailError} />
              </div>
              <FieldError message={error} />
              <Button type="submit" className="w-full" disabled={submitting}>
                {t("auth.sendResetLink")}
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

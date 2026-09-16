import { useState } from "react";
import { useTranslation } from "react-i18next";
import { requestEmailChange, useAuthFeatures } from "@/api/auth";
import { asApiError } from "@/api/client";
import { useAuth } from "@/auth/AuthContext";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";

/**
 * Moving the account to another address. With SMTP configured nothing changes until the link mailed
 * to the new address is opened; without it the password check alone carries the change and it
 * applies at once. Either way the current password is required — a stolen session cookie must not
 * be enough to take over the account's recovery channel.
 */
export function EmailChangeCard() {
  const { t } = useTranslation();
  const { user, refresh } = useAuth();
  const features = useAuthFeatures();
  const [newEmail, setNewEmail] = useState("");
  const [password, setPassword] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pendingFor, setPendingFor] = useState<string | null>(null);

  if (!user) return null;

  const verified = features.data?.emailChangeVerified ?? false;

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setPendingFor(null);
    const trimmed = newEmail.trim();
    let invalid = false;
    if (!trimmed) {
      setEmailError(t("validation.fieldRequired"));
      invalid = true;
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
      setEmailError(t("validation.emailInvalid"));
      invalid = true;
    }
    if (!password) {
      setPasswordError(t("validation.fieldRequired"));
      invalid = true;
    }
    if (invalid) return;
    setSubmitting(true);
    try {
      const result = await requestEmailChange(trimmed, password);
      setNewEmail("");
      setPassword("");
      if (result.status === "pending") {
        setPendingFor(result.email);
      } else {
        // The server rebuilt the session under the new principal; pick the new identity up.
        await refresh();
        showToast(t("settings.emailChanged"));
      }
    } catch (err) {
      setError(asApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("settings.changeEmail")}</h2>
      </CardHeader>
      <CardBody>
        <form noValidate onSubmit={onSubmit} className="max-w-xs space-y-4">
          <p className="text-sm text-gray-600 dark:text-gray-300">
            {verified ? t("settings.changeEmailVerifiedHint") : t("settings.changeEmailDirectHint")}
          </p>
          {pendingFor && (
            <p role="status" className="rounded-md bg-surface-raised px-3 py-2 text-sm text-gray-700 dark:text-gray-200">
              {t("settings.emailChangePending", { email: pendingFor })}
            </p>
          )}
          <div>
            <Label htmlFor="new-email">{t("settings.newEmail")}</Label>
            <Input
              id="new-email"
              type="email"
              autoComplete="email"
              value={newEmail}
              invalid={!!emailError}
              onChange={(e) => {
                setNewEmail(e.target.value);
                if (emailError) setEmailError(null);
              }}
            />
            <FieldError message={emailError} />
          </div>
          <div>
            <Label htmlFor="email-current-password">{t("settings.currentPassword")}</Label>
            <Input
              id="email-current-password"
              type="password"
              autoComplete="current-password"
              value={password}
              invalid={!!passwordError}
              onChange={(e) => {
                setPassword(e.target.value);
                if (passwordError) setPasswordError(null);
              }}
            />
            <FieldError message={passwordError} />
          </div>
          <FieldError message={error} />
          <Button type="submit" disabled={submitting}>
            {t("settings.changeEmailCta")}
          </Button>
        </form>
      </CardBody>
    </Card>
  );
}

import { useState } from "react";
import { useTranslation } from "react-i18next";
import { changePassword } from "@/api/auth";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, CardHeader, FieldError, Input, Label } from "@/components/ui/primitives";
import { showToast } from "@/lib/toastBus";

export function PasswordChangeCard() {
  const { t } = useTranslation();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [currentError, setCurrentError] = useState<string | null>(null);
  const [nextError, setNextError] = useState<string | null>(null);
  const [confirmationError, setConfirmationError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    let invalid = false;
    if (!current) {
      setCurrentError(t("validation.fieldRequired"));
      invalid = true;
    }
    if (!next) {
      setNextError(t("validation.fieldRequired"));
      invalid = true;
    } else if (next.length < 8) {
      setNextError(t("validation.passwordTooShort"));
      invalid = true;
    }
    if (confirmation !== next) {
      setConfirmationError(t("validation.passwordConfirmationMismatch"));
      invalid = true;
    }
    if (invalid) return;
    setSubmitting(true);
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirmation("");
      showToast(t("settings.passwordChanged"));
    } catch (err) {
      setError(asApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <h2 className="font-semibold">{t("settings.changePassword")}</h2>
      </CardHeader>
      <CardBody>
        <form noValidate onSubmit={onSubmit} className="max-w-xs space-y-4">
          <div>
            <Label htmlFor="password-current">{t("settings.currentPassword")}</Label>
            <Input
              id="password-current"
              type="password"
              autoComplete="current-password"
              value={current}
              invalid={!!currentError}
              onChange={(e) => {
                setCurrent(e.target.value);
                if (currentError) setCurrentError(null);
              }}
            />
            <FieldError message={currentError} />
          </div>
          <div>
            <Label htmlFor="password-new">{t("settings.newPassword")}</Label>
            <Input
              id="password-new"
              type="password"
              autoComplete="new-password"
              minLength={8}
              value={next}
              invalid={!!nextError}
              onChange={(e) => {
                setNext(e.target.value);
                if (nextError) setNextError(null);
              }}
            />
            <FieldError message={nextError} />
          </div>
          <div>
            <Label htmlFor="password-confirm">{t("settings.confirmNewPassword")}</Label>
            <Input
              id="password-confirm"
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
          <Button type="submit" disabled={submitting}>
            {t("settings.changePasswordCta")}
          </Button>
        </form>
      </CardBody>
    </Card>
  );
}

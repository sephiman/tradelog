import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { confirmEmailChange } from "@/api/auth";
import { asApiError } from "@/api/client";
import { Card, CardBody } from "@/components/ui/primitives";
import { Logo } from "@/components/ui/Logo";

/** The link lands here, usually on the device holding the mailbox rather than the signed-in one.
 *  The token is the whole proof, so nothing is asked for: confirm and send the user to log in. */
export function ConfirmEmailPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const [error, setError] = useState<string | null>(token ? null : t("auth.emailChangeLinkInvalid"));

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    void (async () => {
      try {
        await confirmEmailChange(token);
        if (!cancelled) navigate("/login?email=changed", { replace: true });
      } catch (err) {
        const { code, message } = asApiError(err);
        if (!cancelled) setError(code === "EMAIL_CHANGE_TOKEN_INVALID" ? t("auth.emailChangeLinkInvalid") : message);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token, navigate, t]);

  return (
    <div className="flex min-h-full items-center justify-center p-4">
      <Card className="w-full max-w-sm">
        <CardBody>
          <Logo className="mx-auto mb-2 h-12 w-auto" />
          <p className="mb-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("auth.confirmEmail")}</p>
          {error ? (
            <p role="alert" className="text-sm text-gray-700 dark:text-gray-200">
              {error}
            </p>
          ) : (
            <p className="text-sm text-gray-500 dark:text-gray-400">{t("common.loading")}</p>
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

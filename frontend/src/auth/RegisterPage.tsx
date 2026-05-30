import { useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useAuth } from "@/auth/AuthContext";
import { asApiError } from "@/api/client";
import { Button, Card, CardBody, FieldError, Input, Label, Select } from "@/components/ui/primitives";
import { Logo } from "@/components/ui/Logo";

export function RegisterPage() {
  const { t, i18n } = useTranslation();
  const { user, loading, register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [locale, setLocale] = useState<"en" | "es">((i18n.language?.startsWith("es") ? "es" : "en"));
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!loading && user) return <Navigate to="/dashboard" replace />;

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await register(email, password, locale);
      navigate("/dashboard", { replace: true });
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
          <p className="mb-6 text-center text-sm text-gray-500 dark:text-gray-400">{t("auth.register")}</p>
          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <Label htmlFor="email">{t("auth.email")}</Label>
              <Input id="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div>
              <Label htmlFor="password">{t("auth.password")}</Label>
              <Input id="password" type="password" autoComplete="new-password" minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} required />
            </div>
            <div>
              <Label htmlFor="locale">{t("auth.language")}</Label>
              <Select id="locale" value={locale} onChange={(e) => setLocale(e.target.value as "en" | "es")}>
                <option value="en">English</option>
                <option value="es">Español</option>
              </Select>
            </div>
            <FieldError message={error} />
            <Button type="submit" className="w-full" disabled={submitting}>
              {t("auth.registerCta")}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm">
            <Link to="/login" className="text-primary hover:underline">{t("auth.haveAccount")}</Link>
          </p>
        </CardBody>
      </Card>
    </div>
  );
}

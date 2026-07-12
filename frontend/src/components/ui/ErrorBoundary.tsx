import { Component, type ReactNode } from "react";
import i18n from "@/i18n";

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
}

/** Last-resort catch for render-time errors so a single broken view can't blank the whole SPA. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  render() {
    if (!this.state.hasError) return this.props.children;
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-3 text-gray-600 dark:text-gray-300">
        <p>{i18n.t("common.errorTitle")}</p>
        <button
          type="button"
          className="rounded-md border border-border px-3 py-1.5 text-sm hover:bg-gray-100 dark:border-gray-600 dark:hover:bg-gray-700"
          onClick={() => window.location.reload()}
        >
          {i18n.t("common.reload")}
        </button>
      </div>
    );
  }
}

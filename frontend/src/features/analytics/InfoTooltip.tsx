/** Small info icon that reveals an explanation on hover or keyboard focus. No external dependency. */
export function InfoTooltip({ text }: { text: string }) {
  return (
    <span className="group relative inline-flex shrink-0">
      <button
        type="button"
        aria-label={text}
        className="flex h-5 w-5 items-center justify-center rounded-full text-violet-400 hover:text-violet-600 focus:outline-none focus:ring-2 focus:ring-violet-500 dark:text-violet-400 dark:hover:text-violet-300"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
          <path
            fillRule="evenodd"
            d="M10 18a8 8 0 100-16 8 8 0 000 16zM9 9a1 1 0 012 0v4a1 1 0 11-2 0V9zm1-4.25a1.1 1.1 0 100 2.2 1.1 1.1 0 000-2.2z"
            clipRule="evenodd"
          />
        </svg>
      </button>
      <span
        role="tooltip"
        className="pointer-events-none absolute right-0 top-6 z-20 hidden w-60 rounded-md border border-border bg-white p-2 text-xs font-normal leading-snug text-gray-600 shadow-lg group-hover:block group-focus-within:block dark:border-gray-600 dark:bg-gray-900 dark:text-gray-300"
      >
        {text}
      </span>
    </span>
  );
}

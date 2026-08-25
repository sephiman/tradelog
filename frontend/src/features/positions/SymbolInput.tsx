import { usePositionSymbols } from "@/api/positions";
import { Input } from "@/components/ui/primitives";

/**
 * Symbol field suggesting the pairs the profile has already traded, the same way the exchange field
 * suggests venues already in use. Free text stays allowed, for a pair traded here for the first time.
 */
export function SymbolInput({
  id,
  profileId,
  value,
  onChange,
}: {
  id: string;
  profileId: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const { data: symbols = [] } = usePositionSymbols(profileId);
  const listId = `${id}-options`;
  return (
    <>
      <Input
        id={id}
        list={listId}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="SOL-USDT"
        autoComplete="off"
      />
      <datalist id={listId}>
        {symbols.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </>
  );
}

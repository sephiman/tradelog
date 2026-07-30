CREATE TEMP TABLE venue_spelling (profile_id UUID, exchange TEXT, uses BIGINT) ON COMMIT DROP;

INSERT INTO venue_spelling (profile_id, exchange, uses)
SELECT profile_id, exchange, count(*) FROM positions WHERE exchange IS NOT NULL GROUP BY 1, 2
UNION ALL
SELECT profile_id, exchange, count(*) FROM capital_snapshots GROUP BY 1, 2;

-- The one spelling each (profile, venue) keeps.
CREATE TEMP TABLE venue_target (profile_id UUID, vkey TEXT, label TEXT) ON COMMIT DROP;

INSERT INTO venue_target (profile_id, vkey, label)
SELECT profile_id, vkey, label FROM (
    SELECT s.profile_id,
           s.vkey,
           COALESCE(
               -- A venue tradelog connects to always wins: its connector keeps writing that label.
               min(c.label),
               (array_agg(s.exchange ORDER BY s.uses DESC, s.exchange)
                    FILTER (WHERE length(s.exchange) <= 64))[1]
           ) AS label
    FROM (
        SELECT profile_id,
               exchange,
               sum(uses) AS uses,
               regexp_replace(lower(exchange), '[^a-z0-9]', '', 'g') AS vkey
        FROM venue_spelling
        GROUP BY 1, 2
    ) s
    LEFT JOIN (VALUES ('Bitunix'), ('BingX'), ('BitMart'), ('Quantfury')) AS c (label)
           ON regexp_replace(lower(c.label), '[^a-z0-9]', '', 'g') = s.vkey
    WHERE s.vkey <> ''
    GROUP BY 1, 2
) t
WHERE label IS NOT NULL;

UPDATE positions p
SET exchange = t.label
FROM venue_target t
WHERE t.profile_id = p.profile_id
  AND regexp_replace(lower(p.exchange), '[^a-z0-9]', '', 'g') = t.vkey
  AND p.exchange <> t.label;

WITH ranked AS (
    SELECT s.id,
           row_number() OVER (
               PARTITION BY s.profile_id, t.label, s.snapshot_date
               ORDER BY (s.source = 'MANUAL') DESC, (s.exchange = t.label) DESC, s.id
           ) AS rn
    FROM capital_snapshots s
    JOIN venue_target t
      ON t.profile_id = s.profile_id
     AND regexp_replace(lower(s.exchange), '[^a-z0-9]', '', 'g') = t.vkey
)
DELETE FROM capital_snapshots WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

UPDATE capital_snapshots s
SET exchange = t.label, updated_at = now()
FROM venue_target t
WHERE t.profile_id = s.profile_id
  AND regexp_replace(lower(s.exchange), '[^a-z0-9]', '', 'g') = t.vkey
  AND s.exchange <> t.label;

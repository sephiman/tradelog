-- Per-user IANA time zone, used to bucket analytics by hour-of-day and weekday in the user's
-- local time. Defaults to UTC so existing rows and new registrations are valid without a choice.
ALTER TABLE users ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC';

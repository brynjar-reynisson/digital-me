-- Widen TEXT_ENTRY.TIME from VARCHAR(23) to TEXT to accommodate full-precision ISO-8601 timestamps
-- (Instant.toString() can produce up to 30 characters with nanosecond precision)
ALTER TABLE TEXT_ENTRY ALTER COLUMN TIME TYPE TEXT;

INSERT INTO APPLICATION_METADATA (KEY, VALUE) VALUES ('database.version', '2')
ON CONFLICT (KEY) DO UPDATE SET VALUE = '2';

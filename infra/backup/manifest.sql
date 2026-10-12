-- Stable fingerprints of every application table, including schema migrations.
-- Each row is represented by all its columns. Ordering is independent of heap order.
-- This file emits fingerprints only, never financial values or authentication data.
SELECT format(
    'SELECT json_build_array(%L, count(*)::text, md5(COALESCE(string_agg(md5(row_to_json(t)::text), %L ORDER BY md5(row_to_json(t)::text)), %L)))::text FROM %I.%I AS t',
    table_name, '', '', table_schema, table_name
)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name
\gexec

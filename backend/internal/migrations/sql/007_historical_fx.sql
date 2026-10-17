-- Public USD-anchored daily observations only. A coverage row is retained even
-- when a particular currency is absent; a missing quote is never invented.
CREATE TABLE fx_historical_snapshots (
    year integer PRIMARY KEY CHECK (year BETWEEN 1900 AND 2199),
    covered_from date NOT NULL,
    covered_to date NOT NULL,
    rates jsonb NOT NULL CHECK (jsonb_typeof(rates) = 'object'),
    fetched_at timestamptz NOT NULL,
    CHECK (covered_from = make_date(year, 1, 1) - 14),
    CHECK (covered_to BETWEEN make_date(year, 1, 1) AND make_date(year, 12, 31))
);

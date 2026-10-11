ALTER TABLE users ADD COLUMN base_currency text NOT NULL DEFAULT 'KZT'
    CHECK (base_currency IN ('KZT','USD','EUR','JPY','KWD','RUB','GBP','CHF','AUD','CAD','CNY'));

ALTER TABLE accounts DROP CONSTRAINT accounts_currency_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_currency_check
    CHECK (currency IN ('KZT','USD','EUR','JPY','KWD','RUB','GBP','CHF','AUD','CAD','CNY'));
ALTER TABLE transactions DROP CONSTRAINT transactions_currency_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_currency_check
    CHECK (currency IN ('KZT','USD','EUR','JPY','KWD','RUB','GBP','CHF','AUD','CAD','CNY'));

-- One atomic snapshot: decimal strings and individual observation dates for all
-- returned quotes. Deliberately shared; it contains no user financial data.
CREATE TABLE fx_snapshots (
    anchor text PRIMARY KEY CHECK (anchor = 'USD'),
    rates jsonb NOT NULL CHECK (jsonb_typeof(rates) = 'object'),
    fetched_at timestamptz NOT NULL
);

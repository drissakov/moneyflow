CREATE TABLE schema_migrations(version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now());
INSERT INTO schema_migrations VALUES ('fixture', 'test', '2026-10-12T03:00:00Z');
INSERT INTO users(id,email,password_hash,created_at) VALUES
('11111111-1111-4111-8111-111111111111','PLAINTEXT_MUST_NOT_APPEAR_IN_BACKUP@example.com','fixture-password-hash','2026-10-12T01:00:00Z');
INSERT INTO accounts(id,user_id,name,currency,opening_balance_minor,created_at) VALUES
('22222222-2222-4222-8222-222222222222','11111111-1111-4111-8111-111111111111','Fixture Account','KZT',1234500,'2026-10-12T01:00:00Z');
INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at,category_id) VALUES
('33333333-3333-4333-8333-333333333333','11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','expense',34900,'KZT','Кофе и завтрак','2026-10-12T02:00:00Z','2026-10-12T02:00:00Z','30000000-0000-4000-8000-000000000003');

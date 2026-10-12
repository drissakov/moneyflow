CREATE TABLE categories (
    id uuid PRIMARY KEY,
    user_id uuid REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('expense', 'income')),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100 AND name = btrim(name)),
    parent_id uuid REFERENCES categories(id) ON DELETE RESTRICT,
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((is_default AND user_id IS NULL) OR (NOT is_default AND user_id IS NOT NULL)),
    CHECK (parent_id IS NULL OR kind = 'expense'),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX categories_visible_idx ON categories(user_id, kind, parent_id);
CREATE UNIQUE INDEX categories_name_scope_idx ON categories(
    COALESCE(user_id, '00000000-0000-0000-0000-000000000000'::uuid),
    kind, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid),
    translate(lower(name), 'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ', 'абвгдеёжзийклмнопрстуфхцчшщъыьэюя')
);

-- A default category is shared; a custom category belongs only to its author.
-- Custom expense children may use either a default root or their own root.
CREATE FUNCTION validate_category_hierarchy() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent categories%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.kind IS DISTINCT FROM OLD.kind OR
        NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.parent_id IS DISTINCT FROM OLD.parent_id OR
        NEW.is_default IS DISTINCT FROM OLD.is_default) THEN
        RAISE EXCEPTION 'category hierarchy and ownership are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT * INTO parent FROM categories WHERE id = NEW.parent_id;
        IF NOT FOUND OR parent.kind <> 'expense' OR parent.parent_id IS NOT NULL OR
            (parent.user_id IS NOT NULL AND parent.user_id IS DISTINCT FROM NEW.user_id) OR
            (NEW.is_default AND NOT parent.is_default) THEN
            RAISE EXCEPTION 'invalid category parent' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER categories_hierarchy_guard BEFORE INSERT OR UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION validate_category_hierarchy();

INSERT INTO categories(id, kind, name, is_default) VALUES
('10000000-0000-4000-8000-000000000001', 'expense', 'Продукты и питание', true),
('10000000-0000-4000-8000-000000000002', 'expense', 'Жильё и коммунальные услуги', true),
('10000000-0000-4000-8000-000000000003', 'expense', 'Транспорт', true),
('10000000-0000-4000-8000-000000000004', 'expense', 'Покупки', true),
('10000000-0000-4000-8000-000000000005', 'expense', 'Здоровье', true),
('10000000-0000-4000-8000-000000000006', 'expense', 'Красота и уход', true),
('10000000-0000-4000-8000-000000000007', 'expense', 'Развлечения и хобби', true),
('10000000-0000-4000-8000-000000000008', 'expense', 'Образование', true),
('10000000-0000-4000-8000-000000000009', 'expense', 'Связь и интернет', true),
('10000000-0000-4000-8000-000000000010', 'expense', 'Путешествия', true),
('10000000-0000-4000-8000-000000000011', 'expense', 'Семья и дети', true),
('10000000-0000-4000-8000-000000000012', 'expense', 'Подарки и благотворительность', true),
('10000000-0000-4000-8000-000000000013', 'expense', 'Финансовые расходы', true),
('10000000-0000-4000-8000-000000000014', 'expense', 'Прочие расходы', true),
('20000000-0000-4000-8000-000000000001', 'income', 'Зарплата', true),
('20000000-0000-4000-8000-000000000002', 'income', 'Подработка и фриланс', true),
('20000000-0000-4000-8000-000000000003', 'income', 'Бизнес', true),
('20000000-0000-4000-8000-000000000004', 'income', 'Проценты и дивиденды', true),
('20000000-0000-4000-8000-000000000005', 'income', 'Аренда', true),
('20000000-0000-4000-8000-000000000006', 'income', 'Продажа имущества', true),
('20000000-0000-4000-8000-000000000007', 'income', 'Подарки', true),
('20000000-0000-4000-8000-000000000008', 'income', 'Пенсия и пособия', true),
('20000000-0000-4000-8000-000000000009', 'income', 'Возвраты и компенсации', true),
('20000000-0000-4000-8000-000000000010', 'income', 'Прочие доходы', true);

-- Seed children from each root in a deterministic order; user-created categories
-- always receive random UUIDs and cannot alter these shared templates.
INSERT INTO categories(id, kind, name, parent_id, is_default)
SELECT ('30000000-0000-4000-8000-' || lpad(child_no::text, 12, '0'))::uuid,
    'expense', child_name,
    ('10000000-0000-4000-8000-' || lpad(root_no::text, 12, '0'))::uuid, true
FROM (VALUES
    (1, 1, 'Продукты'), (1, 2, 'Кафе и рестораны'), (1, 3, 'Кофе'), (1, 4, 'Доставка еды'),
    (2, 5, 'Аренда жилья'), (2, 6, 'Коммунальные услуги'), (2, 7, 'Ремонт и обслуживание'),
    (3, 8, 'Общественный транспорт'), (3, 9, 'Такси'), (3, 10, 'Топливо'), (3, 11, 'Обслуживание автомобиля'),
    (4, 12, 'Одежда и обувь'), (4, 13, 'Техника'), (4, 14, 'Товары для дома'),
    (5, 15, 'Врачи и обследования'), (5, 16, 'Лекарства'), (5, 17, 'Стоматология'),
    (6, 18, 'Косметика'), (6, 19, 'Парикмахерская'), (6, 20, 'Уход и процедуры'),
    (7, 21, 'Кино и мероприятия'), (7, 22, 'Игры и подписки'), (7, 23, 'Спорт и хобби'),
    (8, 24, 'Курсы и обучение'), (8, 25, 'Книги'), (8, 26, 'Учебные материалы'),
    (9, 27, 'Мобильная связь'), (9, 28, 'Домашний интернет'),
    (10, 29, 'Билеты'), (10, 30, 'Проживание'), (10, 31, 'Экскурсии'),
    (11, 32, 'Детские товары'), (11, 33, 'Детский сад и занятия'), (11, 34, 'Помощь семье'),
    (12, 35, 'Подарки'), (12, 36, 'Благотворительность'),
    (13, 37, 'Банковские комиссии'), (13, 38, 'Проценты по кредитам'), (13, 39, 'Налоги и сборы'),
    (14, 40, 'Домашние животные'), (14, 41, 'Прочее')
) AS seeds(root_no, child_no, child_name);

ALTER TABLE transactions ADD COLUMN category_id uuid REFERENCES categories(id) ON DELETE RESTRICT;
CREATE INDEX transactions_monthly_analytics_idx ON transactions(user_id, currency, occurred_at) INCLUDE (kind, category_id, amount_minor);

CREATE FUNCTION validate_transaction_category() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE assigned categories%ROWTYPE;
BEGIN
    IF NEW.category_id IS NOT NULL THEN
        SELECT * INTO assigned FROM categories WHERE id = NEW.category_id;
        IF NOT FOUND OR assigned.kind <> NEW.kind OR
            (assigned.user_id IS NOT NULL AND assigned.user_id <> NEW.user_id) THEN
            RAISE EXCEPTION 'category does not belong to transaction user or kind' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER transactions_category_guard BEFORE INSERT OR UPDATE OF category_id, kind, user_id ON transactions
    FOR EACH ROW EXECUTE FUNCTION validate_transaction_category();

package service

import (
	"strings"
	"testing"
)

func TestImportCategoryRulesAreConservativeAndKindSpecific(t *testing.T) {
	tests := []struct {
		note, kind, category string
		key                  bool
	}{
		{"Покупка: MAGNUM", "expense", expenseSubcategory(1), true},
		{"Покупка: coffeeBOOM", "expense", expenseSubcategory(3), true},
		{"Оплата: Qazaq Oil", "expense", expenseSubcategory(10), true},
		{"Оплата: KASPI МАГАЗИН", "expense", expenseCategory(4), true},
		{"Оплата: дәріхана Example", "expense", expenseSubcategory(16), true},
		{"Покупка: Тестовая кофейня", "expense", expenseSubcategory(3), true},
		{"Пополнение: Возврат средств", "income", incomeCategory(9), false},
		{"Пополнение: Вознаграждение по депозиту", "income", incomeCategory(4), false},
		{"Төлем: кофе", "expense", expenseSubcategory(3), false},
		{"Комиссия: Перевод MAGNUM", "expense", expenseSubcategory(37), false},
		{"Банк комиссиясы", "expense", expenseSubcategory(37), false},
		{"Заработная плата: ТОО Example", "income", incomeCategory(1), true},
		{"Жалақы", "income", incomeCategory(1), false},
		{"Вознаграждение по депозиту", "income", incomeCategory(4), false},
		{"Депозит бойынша сыйақы", "income", incomeCategory(4), false},
		{"Возврат покупки: MAGNUM", "income", incomeCategory(9), true},
		{"Пенсия", "income", incomeCategory(8), false},
		{"Перевод: MAGNUM", "expense", "", false},
		{"Аударым: ТОО Example", "income", "", false},
		{"Пополнение счета", "income", "", false},
		{"MAGNUM", "income", "", false},
		{"Возврат перевода", "income", "", false},
		{"Возврат покупки: MAGNUM", "expense", "", false},
		{"Покупка: MAGNUM и KFC", "expense", "", false},
		{"Покупка: аптека и кафе", "expense", "", false},
		{"Покупка", "expense", "", false},
		{"Оплата", "expense", "", false},
		{"Покупка: Самал Б.", "expense", "", false},
		{"Самал Серикова", "income", "", false},
		{"Google Pay", "expense", "", false},
		{"Yandex Go", "expense", "", false},
		{"Неизвестный продавец", "expense", "", false},
	}
	for _, tc := range tests {
		t.Run(tc.note+"/"+tc.kind, func(t *testing.T) {
			got := classifyImportCategory(tc.note, tc.kind)
			if got.ruleCategoryID != tc.category || (got.identity != "") != tc.key {
				t.Fatalf("category=%q identity=%q; wantcategory=%q key=%v", got.ruleCategoryID, got.identity, tc.category, tc.key)
			}
		})
	}
}

func TestImportMerchantNormalizationIgnoresVolatileData(t *testing.T) {
	a := classifyImportCategory("Покупка: ТОО Example Store REF 123456789012 ****1234 чек ABC123 15.10.2026 12:30:40 1 250,00 KZT", "expense")
	b := classifyImportCategory("ПОКУПКА: ТОО EXAMPLE STORE ref 987654321987 ****5678 чек XYZ987 01.01.2024 09:15:20 750.00 KZT", "expense")
	if a.identity == "" || a.identity != b.identity {
		t.Fatalf("volatile bank details changed merchant identity: %q vs %q", a.identity, b.identity)
	}
	merchant := classifyImportCategory("Покупка: ＭＡＧＮＵＭ", "expense")
	if merchant.identity != "merchant:magnum" {
		t.Fatal("Unicode compatibility normalization failed")
	}
	for _, note := range []string{"Оплата: ТОО", "Покупка: магазин", "Покупка: IP", "Перевод: ИП Example Store"} {
		if classifyImportCategory(note, "expense").identity != "" {
			t.Fatal("generic/personal note got a batch key")
		}
	}
}

func TestImportCategoryMatchKeysAreScopedAndOpaque(t *testing.T) {
	identity := "merchant:magnum"
	user := "64cc4296-13ec-4a74-a8b0-fba0909ecc16"
	key := importCategoryMatchKey(user, "kaspi", "expense", identity)
	if key == nil || len(*key) != 67 || !strings.HasPrefix(*key, "v1:") || strings.Contains(*key, "magnum") {
		t.Fatal("invalid opaque key")
	}
	for _, other := range []*string{importCategoryMatchKey("other-owner", "kaspi", "expense", identity), importCategoryMatchKey(user, "halyk", "expense", identity), importCategoryMatchKey(user, "kaspi", "income", identity), importCategoryMatchKey(user, "kaspi", "income", "refund:"+identity)} {
		if *other == *key {
			t.Fatal("merchant key crossed owner/bank/kind/semantic scope")
		}
	}
	if importCategoryMatchKey(user, "kaspi", "expense", "") != nil {
		t.Fatal("unknown note got a grouping key")
	}
}

func TestImportDefaultTimezoneUsesFixedKazakhstanUTC5(t *testing.T) {
	input := ImportPreviewInput{AccountID: "8094b783-7d58-4c50-9b04-922f56fcc21d", Bank: "kaspi", Currency: "KZT", FileSHA256: strings.Repeat("a", 64), Rows: []ImportRowInput{{RowID: 1, OccurredAt: "2023-01-01T18:30:00Z", Kind: "expense", AmountMinor: "1", Note: "Synthetic"}}}
	draft, rows, location, err := validateImportPreview(input)
	if err != nil || draft.Timezone != "Etc/GMT-5" || location == nil || rows[0].LocalDay != "2023-01-01" {
		t.Fatal("empty timezone did not use fixed UTC+5")
	}
	input.Timezone = "Asia/Almaty"
	draft, rows, _, err = validateImportPreview(input)
	if err != nil || draft.Timezone != "Asia/Almaty" || rows[0].LocalDay != "2023-01-02" {
		t.Fatal("explicit legacy timezone rejected")
	}
}

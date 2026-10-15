package httpapi

import (
	"strings"
	"testing"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

func categoryPreviewInput(accountID, hash, bank, note, kind string) service.ImportPreviewInput {
	input := importInput(accountID, hash, importRow(1, kind, "100"))
	input.Bank = bank
	input.Rows[0].Note = note
	return input
}
func seedCategorizedImport(t *testing.T, f fixture, token string, input service.ImportPreviewInput, categoryID string) {
	t.Helper()
	draft := preview(t, f, token, input)
	expectStatus(t, commitImport(t, f, token, draft.ID, uuid.NewString(), service.ImportSelection{RowID: 1, CategoryID: &categoryID, AllowPossibleDuplicate: true}), 201)
}
func expectCategorySuggestion(t *testing.T, row service.ImportPreviewRowView, category, source string, wantKey bool) {
	t.Helper()
	if category == "" {
		if row.SuggestedCategoryID != nil {
			t.Fatal("unexpected suggested category")
		}
	} else if row.SuggestedCategoryID == nil || *row.SuggestedCategoryID != category {
		t.Fatalf("suggested=%v want%s", row.SuggestedCategoryID, category)
	}
	if source == "" {
		if row.CategorySuggestionSource != nil {
			t.Fatal("unexpected suggestion source")
		}
	} else if row.CategorySuggestionSource == nil || *row.CategorySuggestionSource != source {
		t.Fatal("wrong suggestion source")
	}
	if wantKey {
		if row.CategoryMatchKey == nil || len(*row.CategoryMatchKey) != 67 || !strings.HasPrefix(*row.CategoryMatchKey, "v1:") {
			t.Fatal("missing opaque batch key")
		}
	} else if row.CategoryMatchKey != nil {
		t.Fatal("unsafe generic/personal batch key")
	}
}

func TestIntegrationImportCategoryRulesAndExplicitCommitChoices(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "10000")
	input := importInput(account.ID, "a", importRow(1, "expense", "100"), importRow(2, "expense", "200"), importRow(3, "expense", "300"), importRow(4, "income", "400"), importRow(5, "income", "500"), importRow(6, "expense", "600"))
	notes := []string{"Покупка: MAGNUM", "Покупка: Тестовая кофейня", "Комиссия: Перевод средств", "Жалақы", "Перевод: Тестовый получатель", "Неизвестная покупка"}
	for i, note := range notes {
		input.Rows[i].Note = note
	}
	draft := preview(t, f, user.AccessToken, input)
	expectCategorySuggestion(t, draft.Rows[0], "30000000-0000-4000-8000-000000000001", "rule", true)
	expectCategorySuggestion(t, draft.Rows[1], "30000000-0000-4000-8000-000000000003", "rule", true)
	expectCategorySuggestion(t, draft.Rows[2], "30000000-0000-4000-8000-000000000037", "rule", false)
	expectCategorySuggestion(t, draft.Rows[3], "20000000-0000-4000-8000-000000000001", "rule", false)
	expectCategorySuggestion(t, draft.Rows[4], "", "", false)
	expectCategorySuggestion(t, draft.Rows[5], "", "", false)
	for i, row := range draft.Rows {
		if row.Note != input.Rows[i].Note || row.Kind != input.Rows[i].Kind || row.AmountMinor != input.Rows[i].AmountMinor {
			t.Fatal("suggestion modified imported financial source")
		}
	}
	if importCount(t, f, account.ID) != 0 {
		t.Fatal("preview wrote ledger operations")
	}
	// Suggestions are not silently committed. An explicit null is a user choice,
	// and a different valid category must override the proposed one.
	shopping := "10000000-0000-4000-8000-000000000004"
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), service.ImportSelection{RowID: 1, CategoryID: &shopping}, service.ImportSelection{RowID: 2}), 201)
	var rows []model.Transaction
	if err := f.db.Where("user_id=?", user.User.ID).Order("amount_minor").Find(&rows).Error; err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 || rows[0].CategoryID == nil || *rows[0].CategoryID != shopping || rows[1].CategoryID != nil {
		t.Fatal("commit replaced explicit user choices with suggestions")
	}
}

func TestIntegrationImportCategoryHistoryConsensusIsolationAndConflict(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "10000")
	otherAccount := createAccount(t, f, other.AccessToken, "10000")
	w := request(f.router, "POST", "/api/v1/categories", user.AccessToken, uuid.NewString(), map[string]string{"kind": "expense", "name": "My merchant category"})
	expectStatus(t, w, 201)
	custom := decodeResponse[service.CategoryView](t, w)
	shopping := "10000000-0000-4000-8000-000000000004"
	groceries := "30000000-0000-4000-8000-000000000001"
	seedCategorizedImport(t, f, user.AccessToken, categoryPreviewInput(account.ID, "b", "kaspi", "Покупка: MAGNUM REF 123456789012 ****1234", "expense"), custom.ID)
	seedCategorizedImport(t, f, other.AccessToken, categoryPreviewInput(otherAccount.ID, "c", "kaspi", "Покупка: MAGNUM", "expense"), shopping)
	same := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "d", "kaspi", "ПОКУПКА: МАГНУМ REF 987654321987 ****5678", "expense"))
	expectCategorySuggestion(t, same.Rows[0], custom.ID, "history", true)
	foreignBank := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "e", "halyk", "Покупка: MAGNUM", "expense"))
	expectCategorySuggestion(t, foreignBank.Rows[0], groceries, "rule", true)
	if *foreignBank.Rows[0].CategoryMatchKey == *same.Rows[0].CategoryMatchKey {
		t.Fatal("batch keys crossed banks")
	}
	incoming := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "f", "kaspi", "MAGNUM", "income"))
	expectCategorySuggestion(t, incoming.Rows[0], "", "", false)
	// Conflicting valid historical user categories suppress the dictionary too.
	seedCategorizedImport(t, f, user.AccessToken, categoryPreviewInput(account.ID, "0", "kaspi", "Покупка: MAGNUM", "expense"), shopping)
	conflict := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "1", "kaspi", "Покупка: MAGNUM", "expense"))
	expectCategorySuggestion(t, conflict.Rows[0], "", "", true)
	// Archived assignments no longer participate; another active unanimous
	// assignment still wins, independently of the default merchant rule.
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+custom.ID, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: 1, Name: custom.Name, Archived: true, SortOrder: 0}), 200)
	afterArchive := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "2", "kaspi", "Покупка: MAGNUM", "expense"))
	expectCategorySuggestion(t, afterArchive.Rows[0], shopping, "history", true)
	otherPreview := preview(t, f, other.AccessToken, categoryPreviewInput(otherAccount.ID, "3", "kaspi", "Покупка: MAGNUM", "expense"))
	expectCategorySuggestion(t, otherPreview.Rows[0], shopping, "history", true)
	if *otherPreview.Rows[0].CategoryMatchKey == *afterArchive.Rows[0].CategoryMatchKey {
		t.Fatal("batch key leaked owner correlation")
	}
}

func TestIntegrationImportCategoryParentArchiveAndDefaultRename(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	coffee := "30000000-0000-4000-8000-000000000003"
	food := "10000000-0000-4000-8000-000000000001"
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+coffee, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: 1, Name: "Custom display coffee", Archived: false, SortOrder: 0}), 200)
	before := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "4", "halyk", "Покупка: CoffeeBoom", "expense"))
	expectCategorySuggestion(t, before.Rows[0], coffee, "rule", true)
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+food, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: 1, Name: "Food", Archived: true, SortOrder: 0}), 200)
	after := preview(t, f, user.AccessToken, categoryPreviewInput(account.ID, "5", "halyk", "Покупка: CoffeeBoom", "expense"))
	expectCategorySuggestion(t, after.Rows[0], "", "", true)
	// Guard commit as well: category may be archived after the preview was shown.
	importErrorCode(t, commitImport(t, f, user.AccessToken, before.ID, uuid.NewString(), service.ImportSelection{RowID: 1, CategoryID: &coffee}), 409, "archived_category")
	if importCount(t, f, account.ID) != 0 {
		t.Fatal("category archive race partially imported rows")
	}
}

func TestIntegrationImportCategoryFixedTimezoneAndRefundDirection(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	input := categoryPreviewInput(account.ID, "6", "halyk", "Возврат покупки: MAGNUM", "income")
	input.Timezone = ""
	input.Rows[0].OccurredAt = "2023-01-01T18:30:00Z"
	draft := preview(t, f, user.AccessToken, input)
	expectCategorySuggestion(t, draft.Rows[0], "20000000-0000-4000-8000-000000000009", "rule", true)
	var stored model.StatementImport
	if err := f.db.First(&stored, "id=?", draft.ID).Error; err != nil || stored.Timezone != "Etc/GMT-5" {
		t.Fatal("default fixed timezone not persisted")
	}
	var day string
	if err := f.db.Raw("SELECT local_day::text FROM statement_import_rows WHERE import_id=?", draft.ID).Scan(&day).Error; err != nil || day != "2023-01-01" {
		t.Fatal("fixed UTC+5 local statement day drifted")
	}
	input.Timezone = "Asia/Almaty"
	input.FileSHA256 = strings.Repeat("7", 64)
	legacy := preview(t, f, user.AccessToken, input)
	if err := f.db.Raw("SELECT local_day::text FROM statement_import_rows WHERE import_id=?", legacy.ID).Scan(&day).Error; err != nil || day != "2023-01-02" {
		t.Fatal("explicit legacy timezone no longer preserved")
	}
	// Refunds remain bank-source income, never an expense-category assignment.
	input.Rows[0].Kind = "expense"
	input.FileSHA256 = strings.Repeat("8", 64)
	reverse := preview(t, f, user.AccessToken, input)
	expectCategorySuggestion(t, reverse.Rows[0], "", "", false)
}

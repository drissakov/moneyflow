package httpapi

import (
	"bytes"
	"encoding/json"
	"math"
	"math/big"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

func importInput(accountID, hash string, rows ...service.ImportRowInput) service.ImportPreviewInput {
	return service.ImportPreviewInput{AccountID: accountID, Bank: "kaspi", Currency: "KZT", FileSHA256: strings.Repeat(hash, 64), Timezone: "Asia/Qyzylorda", Rows: rows}
}

func importRow(id int, kind, amount string) service.ImportRowInput {
	return service.ImportRowInput{RowID: id, OccurredAt: "2026-01-01T00:00:00+05:00", Kind: kind, AmountMinor: amount, Note: "Synthetic bank entry"}
}

func preview(t *testing.T, f fixture, token string, input service.ImportPreviewInput) service.ImportPreviewView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/imports/preview", token, "", input)
	expectStatus(t, w, 201)
	return decodeResponse[service.ImportPreviewView](t, w)
}

func commitImport(t *testing.T, f fixture, token, id, key string, rows ...service.ImportSelection) *httptest.ResponseRecorder {
	t.Helper()
	return request(f.router, "POST", "/api/v1/imports/"+id+"/commit", token, key, service.ImportCommitInput{Rows: rows})
}

func importCount(t *testing.T, f fixture, accountID string) int64 {
	t.Helper()
	var count int64
	if err := f.db.Model(&model.Transaction{}).Where("account_id=?", accountID).Count(&count).Error; err != nil {
		t.Fatal(err)
	}
	return count
}

func importErrorCode(t *testing.T, w *httptest.ResponseRecorder, status int, code string) {
	t.Helper()
	expectStatus(t, w, status)
	var payload struct{ Error struct{ Code string } }
	if err := json.Unmarshal(w.Body.Bytes(), &payload); err != nil || payload.Error.Code != code {
		t.Fatalf("error code=%s; want%s", payload.Error.Code, code)
	}
}

func TestIntegrationImportLifecycleExactRowsUndoAndReimport(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "10000")
	input := importInput(account.ID, "a", importRow(1, "expense", "100"), importRow(2, "expense", "100"), importRow(3, "income", "200"))
	opening, closing := "999000", "999000"
	input.OpeningBalanceMinor = &opening
	input.ClosingBalanceMinor = &closing
	draft := preview(t, f, user.AccessToken, input)
	for _, row := range draft.Rows {
		if row.Duplicate != "new" || row.MatchingCount != 0 {
			t.Fatal("identical legitimate rows in one draft were collapsed")
		}
	}
	key := uuid.NewString()
	w := commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 2}, service.ImportSelection{RowID: 1, ExcludeFromAnalytics: true})
	expectStatus(t, w, 201)
	receipt := decodeResponse[service.ImportReceipt](t, w)
	if receipt.ImportedCount != 2 || receipt.SkippedCount != 1 || receipt.BalanceMinor != "9800" || receipt.Status != "committed" || receipt.CommittedAt == nil {
		t.Fatal("atomic receipt or multiplicity is wrong")
	}
	var stored model.Account
	f.db.First(&stored, "id=?", account.ID)
	if stored.OpeningBalanceMinor != 10000 {
		t.Fatal("informational bank balances changed the ledger opening")
	}
	var marked int64
	f.db.Model(&model.Transaction{}).Where("account_id=? AND exclude_from_analytics=true", account.ID).Count(&marked)
	if marked != 1 {
		t.Fatal("explicit analytics exclusion not preserved")
	}
	// Reorder same selection: canonical fingerprint must replay exact bytes.
	replay := commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1, ExcludeFromAnalytics: true}, service.ImportSelection{RowID: 2})
	expectStatus(t, replay, 201)
	if !bytes.Equal(w.Body.Bytes(), replay.Body.Bytes()) {
		t.Fatal("commit replay response changed")
	}
	second := preview(t, f, user.AccessToken, input)
	if second.Rows[0].Duplicate != "exact" || second.Rows[1].Duplicate != "exact" || second.Rows[2].Duplicate != "new" {
		t.Fatal("repeat-file row source detection wrong")
	}
	importErrorCode(t, commitImport(t, f, user.AccessToken, second.ID, uuid.NewString(), service.ImportSelection{RowID: 1}, service.ImportSelection{RowID: 3}), 409, "import_duplicate_exact")
	if importCount(t, f, account.ID) != 2 {
		t.Fatal("exact conflict silently partially imported")
	}
	expectStatus(t, commitImport(t, f, user.AccessToken, second.ID, uuid.NewString(), service.ImportSelection{RowID: 3}), 201)
	manual := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(account.ID, "expense", "17"))
	expectStatus(t, manual, 201)
	undoKey := uuid.NewString()
	undo := request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", user.AccessToken, undoKey, struct{}{})
	expectStatus(t, undo, 200)
	undone := decodeResponse[service.ImportReceipt](t, undo)
	if undone.Status != "undone" || undone.BalanceMinor != "10183" || undone.ImportedCount != 2 || undone.UndoneAt == nil || importCount(t, f, account.ID) != 2 {
		t.Fatal("undo removed unrelated entries or lost history")
	}
	replayUndo := request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", user.AccessToken, undoKey, struct{}{})
	expectStatus(t, replayUndo, 200)
	if !bytes.Equal(undo.Body.Bytes(), replayUndo.Body.Bytes()) {
		t.Fatal("undo replay changed")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", user.AccessToken, uuid.NewString(), struct{}{}), 200)
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1, ExcludeFromAnalytics: true}, service.ImportSelection{RowID: 2}), 409, "import_undone")
	third := preview(t, f, user.AccessToken, input)
	if third.Rows[0].Duplicate != "new" || third.Rows[1].Duplicate != "new" || third.Rows[2].Duplicate != "exact" {
		t.Fatal("undo did not free exact source associations")
	}
	expectStatus(t, commitImport(t, f, user.AccessToken, third.ID, uuid.NewString(), service.ImportSelection{RowID: 1}, service.ImportSelection{RowID: 2}), 201)
	if importCount(t, f, account.ID) != 4 {
		t.Fatal("fresh intentional reimport failed")
	}
	w = request(f.router, "GET", "/api/v1/imports?limit=20", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	history := decodeResponse[struct{ Imports []service.ImportReceipt }](t, w)
	if len(history.Imports) != 3 {
		t.Fatal("import history was not preserved")
	}
	for _, item := range history.Imports {
		if item.ID == draft.ID && item.Status != "undone" {
			t.Fatal("history resurrected stale committed status")
		}
	}
	if strings.Contains(w.Body.String(), "Synthetic bank entry") || strings.Contains(w.Body.String(), "file_sha256") || strings.Contains(w.Body.String(), "rows") {
		t.Fatal("metadata history disclosed row payload")
	}
}

func TestIntegrationImportFullDatabasePossibleDuplicatesAndChangedPreview(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	when, _ := time.Parse(time.RFC3339, "2026-01-01T21:00:00+05:00")
	matching := model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Kind: "expense", AmountMinor: 100, Currency: "KZT", Note: "Different manual description", OccurredAt: when, CreatedAt: when}
	if err := f.db.Create(&matching).Error; err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 120; i++ {
		at := when.AddDate(0, 0, 1+i)
		if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Kind: "income", AmountMinor: 1, Currency: "KZT", OccurredAt: at, CreatedAt: at}).Error; err != nil {
			t.Fatal(err)
		}
	}
	draft := preview(t, f, user.AccessToken, importInput(account.ID, "b", importRow(1, "expense", "100"), importRow(2, "income", "50")))
	if draft.Rows[0].Duplicate != "possible" || draft.Rows[0].MatchingCount != 1 {
		t.Fatal("manual duplicate outside latest100 or changed description was missed")
	}
	key := uuid.NewString()
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1}, service.ImportSelection{RowID: 2}), 409, "import_duplicate_possible")
	if importCount(t, f, account.ID) != 121 {
		t.Fatal("possible conflict caused partial mutation")
	}
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1, AllowPossibleDuplicate: true}, service.ImportSelection{RowID: 2}), 201)
	// A different file never becomes exact merely because its financial rows match.
	overlap := preview(t, f, user.AccessToken, importInput(account.ID, "c", importRow(1, "expense", "100")))
	if overlap.Rows[0].Duplicate != "possible" || overlap.Rows[0].MatchingCount != 2 {
		t.Fatal("overlap duplicate classification or multiplicity wrong")
	}
	// Replace one candidate with a new ID while count stays identical. Opt-in to
	// the old set must not authorize a newly appeared candidate unnoticed.
	if err := f.db.Delete(&matching).Error; err != nil {
		t.Fatal(err)
	}
	matching.ID = uuid.NewString()
	if err := f.db.Create(&matching).Error; err != nil {
		t.Fatal(err)
	}
	importErrorCode(t, commitImport(t, f, user.AccessToken, overlap.ID, uuid.NewString(), service.ImportSelection{RowID: 1, AllowPossibleDuplicate: true}), 409, "import_preview_changed")
	fresh := preview(t, f, user.AccessToken, importInput(account.ID, "d", importRow(1, "income", "99")))
	newMatch := model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Kind: "income", AmountMinor: 99, Currency: "KZT", OccurredAt: when, CreatedAt: when}
	f.db.Create(&newMatch)
	importErrorCode(t, commitImport(t, f, user.AccessToken, fresh.ID, uuid.NewString(), service.ImportSelection{RowID: 1, AllowPossibleDuplicate: true}), 409, "import_preview_changed")
}

func TestIntegrationImportCommitConcurrencyOwnershipAndSelectionFingerprint(t *testing.T) {
	f := integrationFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	input := importInput(account.ID, "e", importRow(1, "income", "50"), importRow(2, "expense", "10"))
	importErrorCode(t, request(f.router, "POST", "/api/v1/imports/preview", other.AccessToken, "", input), 404, "not_found")
	draft := preview(t, f, user.AccessToken, input)
	key := uuid.NewString()
	selection := []service.ImportSelection{{RowID: 1}, {RowID: 2}}
	importErrorCode(t, commitImport(t, f, other.AccessToken, draft.ID, uuid.NewString(), selection...), 404, "import_not_found")
	importErrorCode(t, request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", other.AccessToken, uuid.NewString(), struct{}{}), 404, "import_not_found")
	results := make(chan *httptest.ResponseRecorder, 8)
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			results <- commitImport(t, f, user.AccessToken, draft.ID, key, selection...)
		}()
	}
	group.Wait()
	close(results)
	var original []byte
	for result := range results {
		expectStatus(t, result, 201)
		if original == nil {
			original = append([]byte(nil), result.Body.Bytes()...)
		} else if !bytes.Equal(original, result.Body.Bytes()) {
			t.Fatal("concurrent retry response differs")
		}
	}
	if importCount(t, f, account.ID) != 2 {
		t.Fatal("concurrent retries duplicated financial rows")
	}
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), selection...), 409, "import_already_committed")
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1, ExcludeFromAnalytics: true}, service.ImportSelection{RowID: 2}), 409, "idempotency_conflict")
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1}), 409, "idempotency_conflict")
	history := request(f.router, "GET", "/api/v1/imports", other.AccessToken, "", nil)
	expectStatus(t, history, 200)
	if !strings.Contains(history.Body.String(), `"imports":[]`) {
		t.Fatal("history leaked another owner")
	}
}

func TestIntegrationImportCategoryExpiryAndStrictPayloadRollback(t *testing.T) {
	f := integrationFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	draft := preview(t, f, user.AccessToken, importInput(account.ID, "f", importRow(1, "expense", "100"), importRow(2, "income", "200")))
	categories := listCategories(t, f, user.AccessToken)
	var income, expense string
	for _, c := range categories {
		if c.Kind == "income" && income == "" {
			income = c.ID
		}
		if c.Kind == "expense" && expense == "" {
			expense = c.ID
		}
	}
	private, _ := f.service.CreateCategory(t.Context(), other.User.ID, "expense", "Private synthetic category", nil)
	for _, category := range []struct {
		id     string
		status int
	}{{income, 400}, {private.ID, 404}, {uuid.NewString(), 404}} {
		key := uuid.NewString()
		expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1, CategoryID: &category.id}, service.ImportSelection{RowID: 2}), category.status)
		if importCount(t, f, account.ID) != 0 {
			t.Fatal("invalid category produced partial import")
		}
	}
	custom, err := f.service.CreateCategory(t.Context(), user.User.ID, "expense", "Deleted before confirm", nil)
	if err != nil {
		t.Fatal(err)
	}
	f.db.Delete(&model.Category{}, "id=?", custom.ID)
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), service.ImportSelection{RowID: 1, CategoryID: &custom.ID}), 404)
	f.db.Model(&model.StatementImport{}).Where("id=?", draft.ID).Updates(map[string]any{"created_at": time.Now().Add(-48 * time.Hour), "expires_at": time.Now().Add(-time.Hour)})
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), service.ImportSelection{RowID: 1, CategoryID: &expense}), 410, "import_expired")
	importErrorCode(t, request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", user.AccessToken, uuid.NewString(), struct{}{}), 409, "import_not_committed")
	valid := importInput(account.ID, "a", importRow(1, "expense", "1"))
	for _, mutate := range []func(*service.ImportPreviewInput){
		func(i *service.ImportPreviewInput) { i.Bank = "other" }, func(i *service.ImportPreviewInput) { i.Currency = "USD" }, func(i *service.ImportPreviewInput) { i.Timezone = "Local" }, func(i *service.ImportPreviewInput) { i.Timezone = "Invalid/Zone" }, func(i *service.ImportPreviewInput) { i.FileSHA256 = strings.Repeat("A", 64) }, func(i *service.ImportPreviewInput) { i.Rows = nil }, func(i *service.ImportPreviewInput) { i.Rows = []service.ImportRowInput{importRow(1, "expense", "0")} }, func(i *service.ImportPreviewInput) {
			i.Rows = []service.ImportRowInput{importRow(1, "expense", "1"), importRow(1, "income", "1")}
		},
	} {
		candidate := valid
		mutate(&candidate)
		expectStatus(t, request(f.router, "POST", "/api/v1/imports/preview", user.AccessToken, "", candidate), 400)
	}
	for _, body := range []string{`null`, `[]`, `{"extra":"undocumented"}`, `{"account_id":`} {
		expectStatus(t, request(f.router, "POST", "/api/v1/imports/preview", user.AccessToken, "", body), 400)
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/imports/preview", user.AccessToken, "", `{"note":"`+strings.Repeat("x", 2*1024*1024)+`"}`), 413)
	expectStatus(t, request(f.router, "POST", "/api/v1/imports/preview", user.AccessToken, "", `{"bank":"`+strings.Repeat("x", 2*1024*1024)+`"}`), 413)
	expectStatus(t, request(f.router, "POST", "/api/v1/accounts", user.AccessToken, "", `{"name":"`+strings.Repeat("x", 17000)+`"}`), 413)
	fresh := preview(t, f, user.AccessToken, valid)
	for _, rows := range [][]service.ImportSelection{nil, {{RowID: 0}}, {{RowID: 99}}, {{RowID: 1}, {RowID: 1}}} {
		expectStatus(t, commitImport(t, f, user.AccessToken, fresh.ID, uuid.NewString(), rows...), 400)
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/imports/"+fresh.ID+"/undo", user.AccessToken, uuid.NewString(), `{"unexpected":true}`), 400)
	if importCount(t, f, account.ID) != 0 {
		t.Fatal("failed validations created financial rows")
	}
}

func seedImportBalance(t *testing.T, f fixture, userID, accountID string, target int64) {
	t.Helper()
	accounts, err := f.service.Accounts(t.Context(), userID)
	if err != nil {
		t.Fatal(err)
	}
	var current int64
	for _, a := range accounts {
		if a.ID == accountID {
			current, _ = strconv.ParseInt(a.BalanceMinor, 10, 64)
		}
	}
	delta := new(big.Int).Sub(big.NewInt(target), big.NewInt(current))
	kind := "income"
	if delta.Sign() < 0 {
		kind = "expense"
		delta.Abs(delta)
	}
	whole, remainder := new(big.Int), new(big.Int)
	whole.QuoRem(delta, big.NewInt(service.MaxMinor), remainder)
	if whole.Sign() > 0 {
		if err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at)
		SELECT gen_random_uuid(),?::uuid,?::uuid,?,?, 'KZT','',now(),now() FROM generate_series(1,?)`, userID, accountID, kind, service.MaxMinor, whole.Int64()).Error; err != nil {
			t.Fatal(err)
		}
	}
	if remainder.Sign() > 0 {
		if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: userID, AccountID: accountID, Kind: kind, AmountMinor: remainder.Int64(), Currency: "KZT", OccurredAt: time.Now(), CreatedAt: time.Now()}).Error; err != nil {
			t.Fatal(err)
		}
	}
}

func TestIntegrationImportBalanceOverflowAndUndoRollback(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	seedImportBalance(t, f, user.User.ID, account.ID, math.MaxInt64-1)
	draft := preview(t, f, user.AccessToken, importInput(account.ID, "b", importRow(1, "income", "2"), importRow(2, "income", "1")))
	key := uuid.NewString()
	before := importCount(t, f, account.ID)
	importErrorCode(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 1}), 409, "balance_overflow")
	if importCount(t, f, account.ID) != before {
		t.Fatal("overflow import partially changed ledger")
	}
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, key, service.ImportSelection{RowID: 2}), 201)
	// Removing an earlier expense can itself overflow after later unrelated income.
	secondAccount := createAccount(t, f, user.AccessToken, "0")
	second := preview(t, f, user.AccessToken, importInput(secondAccount.ID, "c", importRow(1, "expense", "10")))
	expectStatus(t, commitImport(t, f, user.AccessToken, second.ID, uuid.NewString(), service.ImportSelection{RowID: 1}), 201)
	seedImportBalance(t, f, user.User.ID, secondAccount.ID, math.MaxInt64-1)
	before = importCount(t, f, secondAccount.ID)
	importErrorCode(t, request(f.router, "POST", "/api/v1/imports/"+second.ID+"/undo", user.AccessToken, uuid.NewString(), struct{}{}), 409, "balance_overflow")
	if importCount(t, f, secondAccount.ID) != before {
		t.Fatal("overflow undo deleted financial rows")
	}
	var stored model.StatementImport
	f.db.First(&stored, "id=?", second.ID)
	if stored.Status != "committed" {
		t.Fatal("rejected undo changed import state")
	}
}

func TestIntegrationImportMaximumBatchPreservesMultiplicity(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	rows := make([]service.ImportRowInput, 2000)
	selection := make([]service.ImportSelection, 2000)
	for i := range rows {
		rows[i] = importRow(i+1, "expense", "1")
		rows[i].Note = strings.Repeat("x", 500)
		selection[i] = service.ImportSelection{RowID: i + 1}
	}
	draft := preview(t, f, user.AccessToken, importInput(account.ID, "d", rows...))
	w := commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), selection...)
	expectStatus(t, w, 201)
	receipt := decodeResponse[service.ImportReceipt](t, w)
	if receipt.ImportedCount != 2000 || receipt.SkippedCount != 0 || receipt.BalanceMinor != "-2000" || importCount(t, f, account.ID) != 2000 {
		t.Fatal("maximum batch lost identical legitimate rows")
	}
	rows = append(rows, importRow(2001, "expense", "1"))
	expectStatus(t, request(f.router, "POST", "/api/v1/imports/preview", user.AccessToken, "", importInput(account.ID, "e", rows...)), 400)
}

func TestIntegrationImportWriteFailureRollsBackEverything(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "25")
	draft := preview(t, f, user.AccessToken, importInput(account.ID, "e", importRow(1, "expense", "10"), importRow(2, "income", "100")))
	if err := f.db.Exec("ALTER TABLE transaction_import_sources ADD CONSTRAINT synthetic_mid_write_failure CHECK(row_id <> 2)").Error; err != nil {
		t.Fatal(err)
	}
	key := uuid.NewString()
	selection := []service.ImportSelection{{RowID: 1}, {RowID: 2}}
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, key, selection...), 500)
	if importCount(t, f, account.ID) != 0 {
		t.Fatal("source-write failure left financial rows")
	}
	var sources, records, linked int64
	f.db.Model(&model.TransactionImportSource{}).Where("account_id=?", account.ID).Count(&sources)
	f.db.Model(&model.IdempotencyRecord{}).Where("user_id=? AND key=?", user.User.ID, key).Count(&records)
	f.db.Model(&model.StatementImportRow{}).Where("import_id=? AND created_transaction_id IS NOT NULL", draft.ID).Count(&linked)
	var stored model.StatementImport
	f.db.First(&stored, "id=?", draft.ID)
	if sources != 0 || records != 0 || linked != 0 || stored.Status != "draft" || stored.BalanceMinor != 25 {
		t.Fatal("source-write failure leaked partial metadata or response")
	}
	if err := f.db.Exec("ALTER TABLE transaction_import_sources DROP CONSTRAINT synthetic_mid_write_failure").Error; err != nil {
		t.Fatal(err)
	}
	w := commitImport(t, f, user.AccessToken, draft.ID, key, selection...)
	expectStatus(t, w, 201)
	if decodeResponse[service.ImportReceipt](t, w).BalanceMinor != "115" {
		t.Fatal("rolled-back key could not be retried safely")
	}
}

func TestIntegrationImportDifferentDraftRaceProtectsExactSources(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	input := importInput(account.ID, "f", importRow(1, "expense", "100"))
	first, second := preview(t, f, user.AccessToken, input), preview(t, f, user.AccessToken, input)
	results := make(chan *httptest.ResponseRecorder, 2)
	var group sync.WaitGroup
	for _, draft := range []service.ImportPreviewView{first, second} {
		group.Add(1)
		go func(id string) {
			defer group.Done()
			results <- commitImport(t, f, user.AccessToken, id, uuid.NewString(), service.ImportSelection{RowID: 1, AllowPossibleDuplicate: true})
		}(draft.ID)
	}
	group.Wait()
	close(results)
	committed, conflict := 0, 0
	for result := range results {
		switch result.Code {
		case 201:
			committed++
		case 409:
			importErrorCode(t, result, 409, "import_duplicate_exact")
			conflict++
		default:
			expectStatus(t, result, 201)
		}
	}
	if committed != 1 || conflict != 1 || importCount(t, f, account.ID) != 1 {
		t.Fatal("simultaneous duplicate source imports were not serialized")
	}
}

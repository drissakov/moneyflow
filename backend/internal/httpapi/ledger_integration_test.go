package httpapi

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"net/url"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

func newLedgerAccount(t *testing.T, f fixture, user service.AuthView, currency string) service.AccountView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/accounts", user.AccessToken, uuid.NewString(), map[string]string{"name": "Account " + currency, "currency": currency, "opening_balance_minor": "100000", "type": "cash"})
	expectStatus(t, w, 201)
	return decodeResponse[service.AccountView](t, w)
}
func updateAccountBody(a service.AccountView) service.UpdateAccountInput {
	return service.UpdateAccountInput{ExpectedVersion: a.Version, Name: a.Name, Type: a.Type, OpeningBalanceMinor: a.OpeningBalanceMinor, Archived: a.Archived, IncludeInBalance: a.IncludeInBalance, SortOrder: a.SortOrder}
}
func updateTransactionBody(t service.TransactionView) service.UpdateTransactionInput {
	return service.UpdateTransactionInput{ExpectedVersion: t.Version, AccountID: t.AccountID, Kind: t.Kind, AmountMinor: t.AmountMinor, Note: t.Note, OccurredAt: t.OccurredAt.Format(time.RFC3339Nano), CategoryID: t.CategoryID, ExcludeFromAnalytics: t.ExcludeFromAnalytics}
}
func ledgerHistory(t *testing.T, f fixture, token, query string) service.HistoryView {
	t.Helper()
	w := request(f.router, "GET", "/api/v1/transactions"+query, token, "", nil)
	expectStatus(t, w, 200)
	return decodeResponse[service.HistoryView](t, w)
}
func transferBody(from, to service.AccountView) service.TransferInput {
	return service.TransferInput{FromAccountID: from.ID, ToAccountID: to.ID, FromAmountMinor: "5000", ToAmountMinor: "100", FeeAccountID: &from.ID, FeeAmountMinor: "50", Note: "Exchange", OccurredAt: "2026-10-15T10:00:00+05:00"}
}
func accountBalance(t *testing.T, f fixture, userID, id string) string {
	t.Helper()
	accounts, err := f.service.Accounts(context.Background(), userID)
	if err != nil {
		t.Fatal(err)
	}
	for _, a := range accounts {
		if a.ID == id {
			return a.BalanceMinor
		}
	}
	t.Fatal("missing account")
	return ""
}

func TestIntegrationP0HistoryEditDeleteRestoreAndPagination(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	// Tied timestamps exercise the final UUID ordering key and cross-account pages.
	var rows []model.Transaction
	when := time.Date(2026, 10, 15, 7, 0, 0, 0, time.UTC)
	for i := 0; i < 137; i++ {
		account := a
		if i%2 == 1 {
			account = b
		}
		rows = append(rows, model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Kind: "expense", AmountMinor: int64(i + 1), Currency: account.Currency, Note: fmt.Sprintf("purchase %03d", i), OccurredAt: when, CreatedAt: when, Version: 1})
	}
	if err := f.db.Create(&rows).Error; err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	query := "?limit=17"
	for {
		page := ledgerHistory(t, f, user.AccessToken, query)
		for _, row := range page.Transactions {
			if seen[row.ID] {
				t.Fatal("cursor repeated transaction")
			}
			seen[row.ID] = true
		}
		if page.NextCursor == nil {
			break
		}
		query = "?limit=17&cursor=" + url.QueryEscape(*page.NextCursor)
	}
	if len(seen) != 137 {
		t.Fatalf("full history lost rows: %d", len(seen))
	}
	if len(ledgerHistory(t, f, other.AccessToken, "").Transactions) != 0 {
		t.Fatal("owner history leaked")
	}
	first := ledgerHistory(t, f, user.AccessToken, "?limit=1")
	expectStatus(t, request(f.router, "GET", "/api/v1/transactions?limit=1&kind=income&cursor="+url.QueryEscape(*first.NextCursor), user.AccessToken, "", nil), 400)
	if len(ledgerHistory(t, f, user.AccessToken, "?q=purchase%20007&kind=expense").Transactions) != 1 {
		t.Fatal("search/filter failed")
	}
	if len(ledgerHistory(t, f, user.AccessToken, "?from=2026-10-16T00:00:00Z").Transactions) != 0 {
		t.Fatal("date filter ignored")
	}
	row := first.Transactions[0]
	input := updateTransactionBody(row)
	input.AmountMinor = "999"
	input.Note = "corrected"
	input.AccountID = b.ID
	key := uuid.NewString()
	w := request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, key, input)
	expectStatus(t, w, 200)
	edited := decodeResponse[service.TransactionView](t, w)
	if edited.Version != 2 || edited.Currency != "USD" || edited.AccountID != b.ID {
		t.Fatal("edit lost currency or version")
	}
	importErrorCode(t, request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, uuid.NewString(), input), 409, "version_conflict")
	expectStatus(t, request(f.router, "PUT", "/api/v1/transactions/"+row.ID, other.AccessToken, uuid.NewString(), input), 404)
	deleted := request(f.router, "DELETE", "/api/v1/transactions/"+row.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2})
	expectStatus(t, deleted, 200)
	trash := decodeResponse[service.TransactionView](t, deleted)
	if trash.DeletedAt == nil || trash.Version != 3 {
		t.Fatal("softdelete not versioned")
	}
	replay := request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, key, input)
	expectStatus(t, replay, 200)
	if !bytes.Equal(w.Body.Bytes(), replay.Body.Bytes()) {
		t.Fatal("edit replay changed")
	}
	if len(ledgerHistory(t, f, user.AccessToken, "?q=corrected").Transactions) != 0 {
		t.Fatal("cached edit replay resurrected deleted row")
	}
	if len(ledgerHistory(t, f, user.AccessToken, "?q=corrected&include_deleted=true").Transactions) != 1 {
		t.Fatal("trash cannot be discovered")
	}
	restored := request(f.router, "POST", "/api/v1/transactions/"+row.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 3})
	expectStatus(t, restored, 200)
	active := decodeResponse[service.TransactionView](t, restored)
	if active.DeletedAt != nil || active.Version != 4 {
		t.Fatal("restore failed")
	}
	expectStatus(t, request(f.router, "DELETE", "/api/v1/transactions/"+row.ID, user.AccessToken, "", service.ExpectedVersion{ExpectedVersion: 4}), 400)
}

func TestIntegrationP0AccountManagementAndPerUserCategoryOverrides(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	if a.Type != "cash" || !a.IncludeInBalance || a.Version != 1 {
		t.Fatal("account create defaults wrong")
	}
	input := updateAccountBody(a)
	input.Name = "Hidden savings"
	input.Type = "savings"
	input.SortOrder = -7
	input.Archived = true
	input.OpeningBalanceMinor = "12345"
	input.IncludeInBalance = false
	w := request(f.router, "PUT", "/api/v1/accounts/"+a.ID, user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 200)
	a = decodeResponse[service.AccountView](t, w)
	if !a.Archived || a.IncludeInBalance || a.BalanceMinor != "12345" || a.Version != 2 {
		t.Fatal("account state not persisted")
	}
	importErrorCode(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "income", "1")), 409, "archived_account")
	portfolio, err := f.service.Portfolio(context.Background(), user.User.ID, "KZT")
	if err != nil || *portfolio.TotalBalanceMinor != "0" {
		t.Fatal("excluded account counted")
	}
	input = updateAccountBody(a)
	input.IncludeInBalance = true
	w = request(f.router, "PUT", "/api/v1/accounts/"+a.ID, user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 200)
	portfolio, err = f.service.Portfolio(context.Background(), user.User.ID, "KZT")
	if err != nil || *portfolio.TotalBalanceMinor != "12345" {
		t.Fatal("archive incorrectly excluded included capital")
	}
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+a.ID, other.AccessToken, uuid.NewString(), input), 404)
	root := "10000000-0000-4000-8000-000000000001"
	child := "30000000-0000-4000-8000-000000000003"
	w = request(f.router, "PUT", "/api/v1/categories/"+root, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: 1, Name: "Meals", Archived: true, SortOrder: 4})
	expectStatus(t, w, 200)
	userCats, _ := f.service.Categories(context.Background(), user.User.ID)
	otherCats, _ := f.service.Categories(context.Background(), other.User.ID)
	for _, c := range userCats {
		if c.ID == root && (c.Name != "Meals" || !c.Archived || c.Version != 2) {
			t.Fatal("user default override missing")
		}
		if c.ID == child && !c.Archived {
			t.Fatal("root archive did not cascade")
		}
	}
	for _, c := range otherCats {
		if c.ID == root && (c.Name == "Meals" || c.Archived || c.Version != 1) {
			t.Fatal("shared default mutated for another user")
		}
	}
	b := newLedgerAccount(t, f, user, "KZT")
	body := transactionBody(b.ID, "expense", "1")
	body["category_id"] = child
	importErrorCode(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), body), 409, "archived_category")
	// Optional idempotency on legacy creates is atomic and durable.
	create := map[string]string{"kind": "income", "name": "Custom income"}
	key := uuid.NewString()
	one := request(f.router, "POST", "/api/v1/categories", user.AccessToken, key, create)
	expectStatus(t, one, 201)
	two := request(f.router, "POST", "/api/v1/categories", user.AccessToken, key, create)
	expectStatus(t, two, 201)
	if !bytes.Equal(one.Body.Bytes(), two.Body.Bytes()) {
		t.Fatal("category create retry duplicated")
	}
}

func TestIntegrationP0CategoryMergeIncludesChildrenAndDeletedHistory(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, other, "KZT")
	source := "10000000-0000-4000-8000-000000000001"
	child := "30000000-0000-4000-8000-000000000003"
	target := "10000000-0000-4000-8000-000000000014"
	var own service.TransactionView
	for i, token := range []string{user.AccessToken, other.AccessToken} {
		account := a
		if i == 1 {
			account = b
		}
		body := transactionBody(account.ID, "expense", "100")
		body["category_id"] = child
		w := request(f.router, "POST", "/api/v1/transactions", token, uuid.NewString(), body)
		expectStatus(t, w, 201)
		if i == 0 {
			own = decodeResponse[service.TransactionView](t, w)
		}
	}
	w := request(f.router, "DELETE", "/api/v1/transactions/"+own.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1})
	expectStatus(t, w, 200)
	w = request(f.router, "POST", "/api/v1/categories/"+source+"/merge", user.AccessToken, uuid.NewString(), service.MergeCategoryInput{ExpectedVersion: 1, TargetCategoryID: target})
	expectStatus(t, w, 200)
	history := ledgerHistory(t, f, user.AccessToken, "?include_deleted=true")
	if len(history.Transactions) != 1 || *history.Transactions[0].CategoryID != target || history.Transactions[0].Version != 3 {
		t.Fatal("merge lost deleted child history")
	}
	otherHistory := ledgerHistory(t, f, other.AccessToken, "")
	if len(otherHistory.Transactions) != 1 || *otherHistory.Transactions[0].CategoryID != child {
		t.Fatal("merge changed another user history")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions/"+own.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 3}), 200)
	// A root cannot be merged into its own child.
	importErrorCode(t, request(f.router, "POST", "/api/v1/categories/"+target+"/merge", user.AccessToken, uuid.NewString(), service.MergeCategoryInput{ExpectedVersion: 1, TargetCategoryID: "30000000-0000-4000-8000-000000000040"}), 400, "invalid_request")
}

func TestIntegrationP0MulticurrencyTransferLifecycleAndPrincipalAnalytics(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	fee := newLedgerAccount(t, f, user, "JPY")
	input := transferBody(a, b)
	input.FeeAccountID = &fee.ID
	input.FeeAmountMinor = "7"
	key := uuid.NewString()
	w := request(f.router, "POST", "/api/v1/transfers", user.AccessToken, key, input)
	expectStatus(t, w, 201)
	transfer := decodeResponse[service.TransferView](t, w)
	if len(transfer.Transactions) != 3 || transfer.FromCurrency != "KZT" || transfer.ToCurrency != "USD" || *transfer.FeeCurrency != "JPY" || transfer.Version != 1 {
		t.Fatal("transfer currency/leg metadata wrong")
	}
	if accountBalance(t, f, user.User.ID, a.ID) != "95000" || accountBalance(t, f, user.User.ID, b.ID) != "100100" || accountBalance(t, f, user.User.ID, fee.ID) != "99993" {
		t.Fatal("transfer balance incorrect")
	}
	for _, currency := range []string{"KZT", "USD", "JPY"} {
		analytics, err := f.service.Analytics(context.Background(), user.User.ID, "2026-10", currency, "Asia/Qyzylorda")
		if err != nil {
			t.Fatal(err)
		}
		want := "0"
		if currency == "JPY" {
			want = "7"
		}
		if analytics.IncomeMinor != "0" || analytics.ExpenseMinor != want {
			t.Fatal("principal leaked into analytics")
		}
	}
	fromLeg := transfer.Transactions[0]
	importErrorCode(t, request(f.router, "DELETE", "/api/v1/transactions/"+fromLeg.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1}), 409, "transfer_managed")
	expectStatus(t, request(f.router, "GET", "/api/v1/transfers/"+transfer.ID, other.AccessToken, "", nil), 404)
	input.ExpectedVersion = 1
	input.FromAmountMinor = "6000"
	input.ToAmountMinor = "120"
	input.FeeAmountMinor = "0"
	input.FeeAccountID = nil
	w = request(f.router, "PUT", "/api/v1/transfers/"+transfer.ID, user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 200)
	transfer = decodeResponse[service.TransferView](t, w)
	if len(transfer.Transactions) != 2 || transfer.Version != 2 || accountBalance(t, f, user.User.ID, fee.ID) != "100000" {
		t.Fatal("transfer update did not reverse removed fee")
	}
	importErrorCode(t, request(f.router, "PUT", "/api/v1/transfers/"+transfer.ID, user.AccessToken, uuid.NewString(), input), 409, "version_conflict")
	w = request(f.router, "DELETE", "/api/v1/transfers/"+transfer.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2})
	expectStatus(t, w, 200)
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" || accountBalance(t, f, user.User.ID, b.ID) != "100000" {
		t.Fatal("group delete was partial")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers", user.AccessToken, key, transferBody(a, b)), 409) // changed fee account = idempotency conflict
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers/"+transfer.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 3}), 200)
	if accountBalance(t, f, user.User.ID, a.ID) != "94000" || accountBalance(t, f, user.User.ID, b.ID) != "100120" {
		t.Fatal("group restore incorrect")
	}
	same := transferBody(a, newLedgerAccount(t, f, user, "KZT"))
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), same), 400)
}

func TestIntegrationP0ConcurrentEditsAndTransferRetries(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	w := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "expense", "1"))
	expectStatus(t, w, 201)
	row := decodeResponse[service.TransactionView](t, w)
	var wg sync.WaitGroup
	statuses := make(chan int, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			input := updateTransactionBody(row)
			input.Note = uuid.NewString()
			statuses <- request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, uuid.NewString(), input).Code
		}()
	}
	wg.Wait()
	close(statuses)
	results := map[int]int{}
	for status := range statuses {
		results[status]++
	}
	if results[200] != 1 || results[409] != 1 {
		t.Fatalf("concurrent expected_version results:%v", results)
	}
	input := transferBody(a, b)
	key := uuid.NewString()
	statuses = make(chan int, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			statuses <- request(f.router, "POST", "/api/v1/transfers", user.AccessToken, key, input).Code
		}()
	}
	wg.Wait()
	close(statuses)
	for status := range statuses {
		if status != 201 {
			t.Fatalf("transfer retry status %d", status)
		}
	}
	var count int64
	f.db.Model(&model.Transfer{}).Where("user_id=?", user.User.ID).Count(&count)
	if count != 1 {
		t.Fatal("concurrent transfer retries duplicated group")
	}
	// Opposite account directions share sorted lock order; imports use same ledger lock.
	statuses = make(chan int, 2)
	for _, body := range []service.TransferInput{transferBody(a, b), transferBody(b, a)} {
		wg.Add(1)
		go func(input service.TransferInput) {
			defer wg.Done()
			statuses <- request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), input).Code
		}(body)
	}
	wg.Wait()
	close(statuses)
	for status := range statuses {
		if status != 201 {
			t.Fatalf("opposite transfer failed: %d", status)
		}
	}
}

func TestIntegrationP0ImportEditsDeleteUndoAndNoResurrection(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	input := importInput(a.ID, "c", importRow(1, "expense", "100"), importRow(2, "expense", "200"))
	draft := preview(t, f, user.AccessToken, input)
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), service.ImportSelection{RowID: 1}, service.ImportSelection{RowID: 2}), 201)
	history := ledgerHistory(t, f, user.AccessToken, "")
	row := history.Transactions[0]
	if row.Source != "import" || row.ImportID == nil || *row.ImportID != draft.ID {
		t.Fatal("import provenance not exposed")
	}
	edit := updateTransactionBody(row)
	edit.AccountID = b.ID
	importErrorCode(t, request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, uuid.NewString(), edit), 409, "imported_account_immutable")
	edit.AccountID = a.ID
	edit.AmountMinor = "900"
	edit.Kind = "income"
	editKey := uuid.NewString()
	expectStatus(t, request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, editKey, edit), 200)
	second := history.Transactions[1]
	expectStatus(t, request(f.router, "DELETE", "/api/v1/transactions/"+second.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1}), 200)
	again := preview(t, f, user.AccessToken, input)
	for _, item := range again.Rows {
		if item.Duplicate != "exact" {
			t.Fatal("editing or trashing import freed original PDF provenance")
		}
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", user.AccessToken, uuid.NewString(), struct{}{}), 200)
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" {
		t.Fatal("undo subtracted old imported amount or double-reversed trash")
	}
	expectStatus(t, request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, editKey, edit), 200) // saved response only
	if len(ledgerHistory(t, f, user.AccessToken, "?include_deleted=true").Transactions) != 0 {
		t.Fatal("old edit replay resurrected undone import")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions/"+second.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2}), 404)
	fresh := preview(t, f, user.AccessToken, input)
	for _, item := range fresh.Rows {
		if item.Duplicate != "new" {
			t.Fatal("undo did not release source for intentional reimport")
		}
	}
}

func TestIntegrationP0TransferOverflowRollsBackAllAccounts(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	whole := int64(math.MaxInt64-100000-5) / service.MaxMinor
	rem := int64(math.MaxInt64-100000-5) % service.MaxMinor
	if err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at) SELECT gen_random_uuid(),?::uuid,?::uuid,'income',?,'USD','',now(),now() FROM generate_series(1,?)`, user.User.ID, b.ID, service.MaxMinor, whole).Error; err != nil {
		t.Fatal(err)
	}
	if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: b.ID, Kind: "income", AmountMinor: rem, Currency: "USD", OccurredAt: time.Now(), CreatedAt: time.Now()}).Error; err != nil {
		t.Fatal(err)
	}
	input := transferBody(a, b)
	input.ToAmountMinor = "6"
	key := uuid.NewString()
	importErrorCode(t, request(f.router, "POST", "/api/v1/transfers", user.AccessToken, key, input), 409, "balance_overflow")
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" || accountBalance(t, f, user.User.ID, b.ID) != strconv.FormatInt(math.MaxInt64-5, 10) {
		t.Fatal("overflow applied one transfer leg or fee")
	}
	var groups int64
	f.db.Model(&model.Transfer{}).Where("user_id=?", user.User.ID).Count(&groups)
	if groups != 0 {
		t.Fatal("overflow left transfer entity")
	}
	input.ToAmountMinor = "5"
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers", user.AccessToken, key, input), 201)
}
func TestIntegrationP0MutationOverflowRollbackAndTrashRestore(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "0")
	whole := int64(math.MaxInt64) / service.MaxMinor
	rem := int64(math.MaxInt64) % service.MaxMinor
	if err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at) SELECT gen_random_uuid(),?::uuid,?::uuid,'income',?,'KZT','',now(),now() FROM generate_series(1,?)`, user.User.ID, a.ID, service.MaxMinor, whole).Error; err != nil {
		t.Fatal(err)
	}
	if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: a.ID, Kind: "income", AmountMinor: rem, Currency: "KZT", OccurredAt: time.Now(), CreatedAt: time.Now()}).Error; err != nil {
		t.Fatal(err)
	}
	w := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "expense", "10"))
	expectStatus(t, w, 201)
	expense := decodeResponse[service.TransactionView](t, w)
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "income", "10")), 201)
	importErrorCode(t, request(f.router, "DELETE", "/api/v1/transactions/"+expense.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1}), 409, "balance_overflow")
	edit := updateTransactionBody(expense)
	edit.AmountMinor = "1"
	importErrorCode(t, request(f.router, "PUT", "/api/v1/transactions/"+expense.ID, user.AccessToken, uuid.NewString(), edit), 409, "balance_overflow")
	accountInput := updateAccountBody(a)
	accountInput.OpeningBalanceMinor = "1"
	importErrorCode(t, request(f.router, "PUT", "/api/v1/accounts/"+a.ID, user.AccessToken, uuid.NewString(), accountInput), 409, "balance_overflow")
	now := time.Now().UTC()
	trash := model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: a.ID, Kind: "income", AmountMinor: 1, Currency: "KZT", OccurredAt: now, CreatedAt: now, DeletedAt: &now, Version: 2}
	if err := f.db.Create(&trash).Error; err != nil {
		t.Fatal(err)
	}
	importErrorCode(t, request(f.router, "POST", "/api/v1/transactions/"+trash.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2}), 409, "balance_overflow")
	if accountBalance(t, f, user.User.ID, a.ID) != strconv.FormatInt(math.MaxInt64, 10) {
		t.Fatal("rejected mutations changed account balance")
	}
	var stored model.Transaction
	f.db.First(&stored, "id=?", expense.ID)
	if stored.Version != 1 || stored.DeletedAt != nil || stored.AmountMinor != 10 {
		t.Fatal("rejected mutation changed transaction")
	}
}

func TestIntegrationP0CategoryAnalyticsOverrideAndTransferFeeMerge(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	c := newLedgerAccount(t, f, other, "KZT")
	bankFee := "30000000-0000-4000-8000-000000000037"
	root := "10000000-0000-4000-8000-000000000013"
	target := "10000000-0000-4000-8000-000000000014"
	input := transferBody(a, b)
	w := request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 201)
	transfer := decodeResponse[service.TransferView](t, w)
	otherBody := transactionBody(c.ID, "expense", "50")
	otherBody["category_id"] = bankFee
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions", other.AccessToken, uuid.NewString(), otherBody), 201)
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+bankFee, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: 1, Name: "My commissions"}), 200)
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+root, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: 1, Name: "My finance"}), 200)
	ownAnalytics, err := f.service.Analytics(context.Background(), user.User.ID, "2026-10", "KZT", "Asia/Qyzylorda")
	if err != nil {
		t.Fatal(err)
	}
	otherAnalytics, err := f.service.Analytics(context.Background(), other.User.ID, "2026-10", "KZT", "Asia/Qyzylorda")
	if err != nil {
		t.Fatal(err)
	}
	if ownAnalytics.Expenses[0].Name != "My finance" || ownAnalytics.Expenses[0].Children[0].Name != "My commissions" {
		t.Fatal("analytics ignored effective default names")
	}
	if otherAnalytics.Expenses[0].Name == "My finance" || otherAnalytics.Expenses[0].Children[0].Name == "My commissions" {
		t.Fatal("analytics default rename leaked across users")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/categories/"+bankFee+"/merge", user.AccessToken, uuid.NewString(), service.MergeCategoryInput{ExpectedVersion: 2, TargetCategoryID: target}), 200)
	input.ExpectedVersion = 1
	input.Note = "Changed note"
	w = request(f.router, "PUT", "/api/v1/transfers/"+transfer.ID, user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 200)
	edited := decodeResponse[service.TransferView](t, w)
	for _, leg := range edited.Transactions {
		if leg.TransferRole != nil && *leg.TransferRole == "fee" {
			if leg.CategoryID == nil || *leg.CategoryID != target || leg.Version < 3 {
				t.Fatal("editing transfer lost merged fee category or decreased version")
			}
		}
	}
	// Archived fee template causes a new system fee to remain an ordinary uncategorized expense.
	w = request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), transferBody(a, b))
	expectStatus(t, w, 201)
	created := decodeResponse[service.TransferView](t, w)
	for _, leg := range created.Transactions {
		if *leg.TransferRole == "fee" && (leg.CategoryID != nil || leg.ExcludeFromAnalytics) {
			t.Fatal("archived fee template blocked fee or excluded it from expense analytics")
		}
	}
}

func TestIntegrationP0ConcurrentImportUndoVersusEdit(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	draft := preview(t, f, user.AccessToken, importInput(a.ID, "e", importRow(1, "expense", "100")))
	expectStatus(t, commitImport(t, f, user.AccessToken, draft.ID, uuid.NewString(), service.ImportSelection{RowID: 1}), 201)
	row := ledgerHistory(t, f, user.AccessToken, "").Transactions[0]
	edit := updateTransactionBody(row)
	edit.AmountMinor = "999"
	var wg sync.WaitGroup
	statuses := make(chan int, 2)
	wg.Add(2)
	go func() {
		defer wg.Done()
		statuses <- request(f.router, "PUT", "/api/v1/transactions/"+row.ID, user.AccessToken, uuid.NewString(), edit).Code
	}()
	go func() {
		defer wg.Done()
		status := request(f.router, "POST", "/api/v1/imports/"+draft.ID+"/undo", user.AccessToken, uuid.NewString(), struct{}{}).Code
		if status != 200 {
			statuses <- status
		} else {
			statuses <- 299
		}
	}()
	wg.Wait()
	close(statuses)
	for status := range statuses {
		if status != 200 && status != 404 && status != 299 {
			t.Fatalf("concurrent import/edit failed: %d", status)
		}
	}
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" || len(ledgerHistory(t, f, user.AccessToken, "?include_deleted=true").Transactions) != 0 {
		t.Fatal("concurrent undo/edit left funds or source row behind")
	}
}
func TestIntegrationP0DatabaseGuardsRejectPartialTransfers(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "USD")
	w := request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), transferBody(a, b))
	expectStatus(t, w, 201)
	transfer := decodeResponse[service.TransferView](t, w)
	for _, sql := range []string{
		"DELETE FROM transactions WHERE transfer_id='" + transfer.ID + "' AND transfer_role='from'",
		"UPDATE transactions SET exclude_from_analytics=false WHERE transfer_id='" + transfer.ID + "' AND transfer_role='from'",
		"UPDATE transfers SET from_amount_minor=from_amount_minor+1 WHERE id='" + transfer.ID + "'",
		"UPDATE accounts SET currency='EUR' WHERE id='" + a.ID + "'",
	} {
		if err := f.db.Transaction(func(tx *gorm.DB) error { return tx.Exec(sql).Error }); err == nil {
			t.Fatal("database accepted partial or mismatched transfer")
		}
	}
	view, err := f.service.GetTransfer(context.Background(), user.User.ID, transfer.ID)
	if err != nil || len(view.Transactions) != 3 || view.FromAmountMinor != "5000" || accountBalance(t, f, user.User.ID, a.ID) != "94950" {
		t.Fatal("rejected direct mutations altered ledger")
	}
}

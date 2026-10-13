package httpapi

import (
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
	"gorm.io/gorm/clause"
)

func expireTestDraft(t *testing.T, f fixture, id string) {
	t.Helper()
	if err := f.db.Model(&model.StatementImport{}).Where("id=?", id).Updates(map[string]any{"created_at": time.Now().Add(-48 * time.Hour), "expires_at": time.Now().Add(-time.Hour)}).Error; err != nil {
		t.Fatal(err)
	}
}

func TestIntegrationImportExpiryCleanupIsOwnerScopedAndBounded(t *testing.T) {
	f := integrationFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	account, otherAccount := createAccount(t, f, user.AccessToken, "0"), createAccount(t, f, other.AccessToken, "0")
	committed := preview(t, f, user.AccessToken, importInput(account.ID, "a", importRow(1, "income", "10")))
	expectStatus(t, commitImport(t, f, user.AccessToken, committed.ID, uuid.NewString(), service.ImportSelection{RowID: 1}), 201)
	otherDraft := preview(t, f, other.AccessToken, importInput(otherAccount.ID, "b", importRow(1, "expense", "1")))
	expired := make([]service.ImportPreviewView, 25)
	for i := range expired {
		expired[i] = preview(t, f, user.AccessToken, importInput(account.ID, "c", importRow(i+1, "expense", "1")))
	}
	for _, draft := range expired {
		expireTestDraft(t, f, draft.ID)
	}
	expireTestDraft(t, f, committed.ID)
	expireTestDraft(t, f, otherDraft.ID)
	expectStatus(t, request(f.router, "GET", "/api/v1/imports", user.AccessToken, "", nil), 200)
	var remaining int64
	f.db.Raw("SELECT count(*) FROM statement_import_rows r JOIN statement_imports i ON i.id=r.import_id WHERE i.user_id=? AND i.status='draft'", user.User.ID).Scan(&remaining)
	if remaining != 5 {
		t.Fatalf("cleanup processed%d drafts instead of maximum20", 25-remaining)
	}
	var preserved int64
	f.db.Model(&model.StatementImportRow{}).Where("import_id IN ?", []string{committed.ID, otherDraft.ID}).Count(&preserved)
	if preserved != 2 {
		t.Fatal("cleanup removed committed rows or another owner's rows")
	}
	importErrorCode(t, commitImport(t, f, user.AccessToken, expired[0].ID, uuid.NewString(), service.ImportSelection{RowID: 1}), 410, "import_expired")
	f.db.Raw("SELECT count(*) FROM statement_import_rows r JOIN statement_imports i ON i.id=r.import_id WHERE i.user_id=? AND i.status='draft'", user.User.ID).Scan(&remaining)
	if remaining != 0 {
		t.Fatal("subsequent access did not clean remaining expired payloads")
	}
	var metadata int64
	f.db.Model(&model.StatementImport{}).Where("user_id=?", user.User.ID).Count(&metadata)
	if metadata != 26 || importCount(t, f, account.ID) != 1 {
		t.Fatal("cleanup deleted metadata or changed financial entries")
	}
}

func TestIntegrationImportCleanupSkipsLockedParents(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "0")
	draft := preview(t, f, user.AccessToken, importInput(account.ID, "d", importRow(1, "expense", "1")))
	expireTestDraft(t, f, draft.ID)
	tx := f.db.Begin()
	if tx.Error != nil {
		t.Fatal(tx.Error)
	}
	defer tx.Rollback()
	var locked model.StatementImport
	if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).First(&locked, "id=?", draft.ID).Error; err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	expectStatus(t, request(f.router, "GET", "/api/v1/imports", user.AccessToken, "", nil), 200)
	if time.Since(started) > 2*time.Second {
		t.Fatal("cleanup waited for a locked parent instead of skipping")
	}
	var count int64
	f.db.Model(&model.StatementImportRow{}).Where("import_id=?", draft.ID).Count(&count)
	if count != 1 {
		t.Fatal("cleanup deleted a locked draft")
	}
	tx.Rollback()
	expectStatus(t, request(f.router, "GET", "/api/v1/imports", user.AccessToken, "", nil), 200)
	f.db.Model(&model.StatementImportRow{}).Where("import_id=?", draft.ID).Count(&count)
	if count != 0 {
		t.Fatal("unlocked expired draft was not cleaned")
	}
}

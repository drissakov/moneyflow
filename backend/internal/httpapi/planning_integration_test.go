package httpapi

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

func planningFixture(t *testing.T) fixture {
	t.Helper()
	f := integrationFixture(t)
	now := func() time.Time { return time.Date(2026, 10, 17, 10, 0, 0, 0, time.UTC) }
	s, err := service.New(f.db, time.Hour, service.Options{Now: now})
	if err != nil {
		t.Fatal(err)
	}
	router, err := New(s, Options{Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	f.service, f.router = s, router
	return f
}
func planningRead(t *testing.T, f fixture, token string) service.PlanningView {
	t.Helper()
	w := request(f.router, "GET", "/api/v1/planning", token, "", nil)
	expectStatus(t, w, 200)
	return decodeResponse[service.PlanningView](t, w)
}
func planningCategory(t *testing.T, f fixture, user service.AuthView, kind, name string, parent *string) service.CategoryView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/categories", user.AccessToken, uuid.NewString(), map[string]any{"kind": kind, "name": name, "parent_id": parent})
	expectStatus(t, w, 201)
	return decodeResponse[service.CategoryView](t, w)
}
func planningScheduleBody(a service.AccountView) service.ScheduleInput {
	return service.ScheduleInput{Name: "Rent", AccountID: a.ID, Kind: "expense", AmountMinor: "30000", Note: "planned", StartDate: "2026-10-20", Frequency: "monthly", Interval: 1, Mandatory: true}
}
func planningSaveSchedule(t *testing.T, f fixture, user service.AuthView, input service.ScheduleInput) service.ScheduleView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/schedules", user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 201)
	return decodeResponse[service.ScheduleView](t, w)
}
func forecastRead(t *testing.T, f fixture, token, query string) service.ForecastView {
	t.Helper()
	w := request(f.router, "GET", "/api/v1/planning/forecast"+query, token, "", nil)
	expectStatus(t, w, 200)
	return decodeResponse[service.ForecastView](t, w)
}

func TestIntegrationP1BudgetsUseOriginalCurrencySubtreesAndKZBoundaries(t *testing.T) {
	f := planningFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	account := newLedgerAccount(t, f, user, "KZT")
	usd := newLedgerAccount(t, f, user, "USD")
	root := planningCategory(t, f, user, "expense", "Food P1", nil)
	child := planningCategory(t, f, user, "expense", "Coffee P1", &root.ID)
	body := service.BudgetInput{Name: "Food monthly", CategoryID: root.ID, Currency: "KZT", LimitMinor: "10000", PeriodStartDay: 1}
	w := request(f.router, "POST", "/api/v1/budgets", user.AccessToken, uuid.NewString(), body)
	expectStatus(t, w, 201)
	budget := decodeResponse[service.BudgetView](t, w)
	when := time.Date(2026, 10, 1, 0, 0, 0, 0, time.FixedZone("KZ", 18000))
	rows := []model.Transaction{
		{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Currency: "KZT", Kind: "expense", AmountMinor: 100, CategoryID: &child.ID, OccurredAt: when, CreatedAt: when, Version: 1},
		{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Currency: "KZT", Kind: "expense", AmountMinor: 200, CategoryID: &root.ID, OccurredAt: when.Add(-time.Microsecond), CreatedAt: when, Version: 1},
		{ID: uuid.NewString(), UserID: user.User.ID, AccountID: usd.ID, Currency: "USD", Kind: "expense", AmountMinor: 500, CategoryID: &root.ID, OccurredAt: when, CreatedAt: when, Version: 1},
		{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Currency: "KZT", Kind: "expense", AmountMinor: 900, CategoryID: &child.ID, OccurredAt: when, CreatedAt: when, Version: 1, ExcludeFromAnalytics: true},
		{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Currency: "KZT", Kind: "expense", AmountMinor: 700, CategoryID: &child.ID, OccurredAt: when, CreatedAt: when, Version: 1, DeletedAt: &when},
	}
	if err := f.db.Create(&rows).Error; err != nil {
		t.Fatal(err)
	}
	got := planningRead(t, f, user.AccessToken).Budgets[0]
	if got.SpentMinor != "100" || got.RemainingMinor != "9900" || got.PeriodFrom != "2026-10-01" || got.PeriodTo != "2026-11-01" {
		t.Fatalf("budget got%+v", got)
	}
	overlap := body
	overlap.CategoryID = child.ID
	importErrorCode(t, request(f.router, "POST", "/api/v1/budgets", user.AccessToken, uuid.NewString(), overlap), 409, "budget_overlap")
	overlap.Currency = "USD"
	expectStatus(t, request(f.router, "POST", "/api/v1/budgets", user.AccessToken, uuid.NewString(), overlap), 201)
	body.ExpectedVersion = budget.Version
	body.Archived = true
	expectStatus(t, request(f.router, "PUT", "/api/v1/budgets/"+budget.ID, other.AccessToken, uuid.NewString(), body), 404)
	expectStatus(t, request(f.router, "PUT", "/api/v1/budgets/"+budget.ID, user.AccessToken, uuid.NewString(), body), 200)
	importErrorCode(t, request(f.router, "PUT", "/api/v1/budgets/"+budget.ID, user.AccessToken, uuid.NewString(), body), 409, "version_conflict")
	if len(planningRead(t, f, other.AccessToken).Budgets) != 0 {
		t.Fatal("budget owner leak")
	}
}

func TestIntegrationP1ExplicitPostConcurrentKeysReplayAndDeletedLink(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	input := planningScheduleBody(a)
	schedule := planningSaveSchedule(t, f, user, input)
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" {
		t.Fatal("saving schedule wrote money")
	}
	path := "/api/v1/schedules/" + schedule.ID + "/occurrences/2026-10-20/post"
	body := service.PostOccurrenceInput{ExpectedTemplateVersion: 1, ExpectedOccurrenceVersion: 0, AmountMinor: "32000", OccurredAt: "2026-10-20T13:00:00+05:00", Note: "actual"}
	var wg sync.WaitGroup
	codes := make(chan int, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes <- request(f.router, "POST", path, user.AccessToken, uuid.NewString(), body).Code
		}()
	}
	wg.Wait()
	close(codes)
	success, conflict := 0, 0
	for code := range codes {
		if code == 200 {
			success++
		} else if code == 409 {
			conflict++
		} else {
			t.Fatal("unexpected concurrent status", code)
		}
	}
	if success != 1 || conflict != 1 || accountBalance(t, f, user.User.ID, a.ID) != "68000" {
		t.Fatal("duplicate posting")
	}
	view := planningRead(t, f, user.AccessToken)
	var posted service.OccurrenceView
	for _, o := range view.Occurrences {
		if o.ScheduleID == schedule.ID && o.Status == "posted" {
			posted = o
		}
	}
	if posted.TransactionID == nil || posted.AmountMinor != "32000" {
		t.Fatal("posted snapshot missing actualamount")
	}
	deleted := request(f.router, "DELETE", "/api/v1/transactions/"+*posted.TransactionID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1})
	expectStatus(t, deleted, 200)
	body.ExpectedOccurrenceVersion = posted.Version
	importErrorCode(t, request(f.router, "POST", path, user.AccessToken, uuid.NewString(), body), 409, "occurrence_posted")
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" {
		t.Fatal("deleted occurrence reposted")
	}
	view = planningRead(t, f, user.AccessToken)
	for _, o := range view.Occurrences {
		if o.ScheduleID == schedule.ID && o.Status == "posted" && !o.TransactionDeleted {
			t.Fatal("deleted link hidden")
		}
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions/"+*posted.TransactionID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2}), 200)
	// Different due date uses durable exact saved receipt across later edits.
	path = "/api/v1/schedules/" + schedule.ID + "/occurrences/2026-11-20/post"
	key := uuid.NewString()
	body.ExpectedOccurrenceVersion = 0
	first := request(f.router, "POST", path, user.AccessToken, key, body)
	expectStatus(t, first, 200)
	occurrence := decodeResponse[service.OccurrenceView](t, first)
	expectStatus(t, request(f.router, "DELETE", "/api/v1/transactions/"+*occurrence.TransactionID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1}), 200)
	replay := request(f.router, "POST", path, user.AccessToken, key, body)
	expectStatus(t, replay, 200)
	if !bytes.Equal(first.Body.Bytes(), replay.Body.Bytes()) || accountBalance(t, f, user.User.ID, a.ID) != "68000" {
		t.Fatal("receipt replay reapplied money")
	}
	input.ExpectedVersion = 1
	input.AmountMinor = "123"
	expectStatus(t, request(f.router, "PUT", "/api/v1/schedules/"+schedule.ID, user.AccessToken, uuid.NewString(), input), 200)
	input.ExpectedVersion = 2
	input.StartDate = "2026-10-21"
	importErrorCode(t, request(f.router, "PUT", "/api/v1/schedules/"+schedule.ID, user.AccessToken, uuid.NewString(), input), 409, "schedule_anchor_locked")
	view = planningRead(t, f, user.AccessToken)
	for _, o := range view.Occurrences {
		if o.ScheduleID == schedule.ID && o.Status == "posted" && o.AmountMinor != "32000" {
			t.Fatal("template edit altered posted snapshot")
		}
	}
}

func TestIntegrationP1SkippedPausedArchivedAndOwnerChecks(t *testing.T) {
	f := planningFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	cat := planningCategory(t, f, user, "expense", "Recurring utilities", nil)
	input := planningScheduleBody(a)
	input.CategoryID = &cat.ID
	schedule := planningSaveSchedule(t, f, user, input)
	base := "/api/v1/schedules/" + schedule.ID + "/occurrences/2026-10-20"
	state := service.OccurrenceInput{ExpectedTemplateVersion: 1}
	expectStatus(t, request(f.router, "POST", base+"/skip", other.AccessToken, uuid.NewString(), state), 404)
	expectStatus(t, request(f.router, "POST", base+"/skip", user.AccessToken, uuid.NewString(), state), 200)
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" {
		t.Fatal("skip wrote ledger")
	}
	post := service.PostOccurrenceInput{ExpectedTemplateVersion: 1, ExpectedOccurrenceVersion: 1, AmountMinor: "30000", OccurredAt: "2026-10-20T12:00:00+05:00", CategoryID: &cat.ID}
	importErrorCode(t, request(f.router, "POST", base+"/post", user.AccessToken, uuid.NewString(), post), 409, "occurrence_state")
	update := updateAccountBody(a)
	update.Archived = true
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+a.ID, user.AccessToken, uuid.NewString(), update), 200)
	state.ExpectedOccurrenceVersion = 1
	expectStatus(t, request(f.router, "POST", base+"/unskip", user.AccessToken, uuid.NewString(), state), 200)
	post.ExpectedOccurrenceVersion = 2
	importErrorCode(t, request(f.router, "POST", base+"/post", user.AccessToken, uuid.NewString(), post), 409, "archived_account")
	input.ExpectedVersion = 1
	input.Paused = true
	w := request(f.router, "PUT", "/api/v1/schedules/"+schedule.ID, user.AccessToken, uuid.NewString(), input)
	expectStatus(t, w, 200)
	if v := decodeResponse[service.ScheduleView](t, w); !v.ScheduleLocked || v.BlockedReason == nil || *v.BlockedReason != "paused" {
		t.Fatal("pause metadata wrong")
	}
	state.ExpectedTemplateVersion = 2
	state.ExpectedOccurrenceVersion = 2
	expectStatus(t, request(f.router, "POST", base+"/skip", user.AccessToken, uuid.NewString(), state), 200)
	input.ExpectedVersion = 2
	input.Name = "Paused renamed"
	expectStatus(t, request(f.router, "PUT", "/api/v1/schedules/"+schedule.ID, user.AccessToken, uuid.NewString(), input), 200)
	for _, o := range planningRead(t, f, user.AccessToken).Occurrences {
		if o.ScheduleID == schedule.ID && o.Status == "skipped" {
			if o.ScheduleVersion != 3 {
				t.Fatal("skipped occurrence cannot recover after template edit")
			}
			expectStatus(t, request(f.router, "POST", base+"/unskip", user.AccessToken, uuid.NewString(), service.OccurrenceInput{ExpectedTemplateVersion: o.ScheduleVersion, ExpectedOccurrenceVersion: o.Version}), 200)
		}
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/schedules", user.AccessToken, uuid.NewString(), planningScheduleBody(newLedgerAccount(t, f, other, "KZT"))), 404)
}

func TestIntegrationP1ForecastCashReserveAndMandatoryWithoutFutureIncomeOrBudgetDoubleCount(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "KZT")
	savings := newLedgerAccount(t, f, user, "KZT")
	update := updateAccountBody(savings)
	update.Type = "savings"
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+savings.ID, user.AccessToken, uuid.NewString(), update), 200)
	expense := planningScheduleBody(a)
	planningSaveSchedule(t, f, user, expense)
	overdue := planningScheduleBody(b)
	overdue.StartDate = "2026-10-10"
	overdue.Frequency = "once"
	overdue.AmountMinor = "9000"
	planningSaveSchedule(t, f, user, overdue)
	income := planningScheduleBody(a)
	income.Name = "Salary"
	income.Kind = "income"
	income.Mandatory = false
	income.PrimaryIncome = true
	income.StartDate = "2026-10-25"
	income.AmountMinor = "1000000"
	planningSaveSchedule(t, f, user, income)
	cat := planningCategory(t, f, user, "expense", "Food budget guidance", nil)
	expectStatus(t, request(f.router, "POST", "/api/v1/budgets", user.AccessToken, uuid.NewString(), service.BudgetInput{Name: "Guidance", CategoryID: cat.ID, Currency: "KZT", LimitMinor: "1000000", PeriodStartDay: 1}), 201)
	settings := service.PlanningSettingsInput{BaseCurrency: "KZT", ReserveMinor: "5000", AccountIDs: []string{}}
	expectStatus(t, request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, uuid.NewString(), settings), 200)
	forecast := forecastRead(t, f, user.AccessToken, "")
	if !forecast.Complete || forecast.Until != "2026-10-25" || forecast.Days != 9 || forecast.HorizonReason != "primary_income" || *forecast.BalanceMinor != "200000" || *forecast.MandatoryMinor != "39000" || *forecast.FreeMinor != "156000" || *forecast.PerDayMinor != "17333" || len(forecast.Accounts) != 2 {
		t.Fatalf("forecast calculation %+v", forecast)
	}
	expectStatus(t, request(f.router, "PUT", "/api/v1/preferences", user.AccessToken, "", map[string]string{"base_currency": "USD"}), 200)
	if after := forecastRead(t, f, user.AccessToken, ""); after.BaseCurrency != "KZT" || *after.FreeMinor != "156000" {
		t.Fatal("app preference reinterpreted stored reserve")
	}
	settings.ExpectedVersion = 1
	settings.ReserveMinor = "300001"
	expectStatus(t, request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, uuid.NewString(), settings), 200)
	negative := forecastRead(t, f, user.AccessToken, "?until=2026-10-18")
	if *negative.FreeMinor != "-109001" || *negative.PerDayMinor != "-54501" {
		t.Fatal("negative floor/deficit wrong", *negative.FreeMinor, *negative.PerDayMinor)
	}
	settings.ExpectedVersion = 2
	settings.AccountIDs = []string{savings.ID}
	expectStatus(t, request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, uuid.NewString(), settings), 400)
}

func TestIntegrationP1MissingFXAndOlderPendingNeverClaimSpendable(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "USD")
	forecast := forecastRead(t, f, user.AccessToken, "")
	if forecast.Complete || forecast.FreeMinor != nil || forecast.BalanceMinor != nil || forecast.MandatoryMinor != nil || forecast.PerDayMinor != nil || len(forecast.MissingCurrencies) != 1 || forecast.MissingCurrencies[0] != "USD" {
		t.Fatal("missingFX invented zero/total")
	}
	settings := service.PlanningSettingsInput{BaseCurrency: "USD", ReserveMinor: "0", AccountIDs: []string{a.ID}}
	expectStatus(t, request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, uuid.NewString(), settings), 200)
	old := planningScheduleBody(a)
	old.Frequency = "once"
	old.StartDate = "2020-01-01"
	schedule := planningSaveSchedule(t, f, user, old)
	view := planningRead(t, f, user.AccessToken)
	if !view.TimelineTruncated || view.OverdueCount != 1 {
		t.Fatal("oldpending silentlydropped")
	}
	forecast = forecastRead(t, f, user.AccessToken, "")
	if forecast.Complete || !forecast.TimelineTruncated || forecast.FreeMinor != nil {
		t.Fatal("olddebt countedassafe")
	}
	state := service.OccurrenceInput{ExpectedTemplateVersion: schedule.Version}
	expectStatus(t, request(f.router, "POST", fmt.Sprintf("/api/v1/schedules/%s/occurrences/2020-01-01/skip", schedule.ID), user.AccessToken, uuid.NewString(), state), 200)
	forecast = forecastRead(t, f, user.AccessToken, "")
	if !forecast.Complete || forecast.TimelineTruncated || forecast.FreeMinor == nil || *forecast.FreeMinor != "100000" {
		t.Fatal("resolvedolddebt stillblocked")
	}
}

func TestIntegrationP1CategoryMergeUpdatesPendingTemplatesArchivesBudgetAndFreezesSnapshots(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	source := planningCategory(t, f, user, "expense", "Merge old plan", nil)
	target := planningCategory(t, f, user, "expense", "Merge new plan", nil)
	input := planningScheduleBody(a)
	input.CategoryID = &source.ID
	schedule := planningSaveSchedule(t, f, user, input)
	expectStatus(t, request(f.router, "POST", "/api/v1/budgets", user.AccessToken, uuid.NewString(), service.BudgetInput{Name: "Old budget", CategoryID: source.ID, Currency: "KZT", LimitMinor: "10000", PeriodStartDay: 1}), 201)
	post := service.PostOccurrenceInput{ExpectedTemplateVersion: 1, AmountMinor: "10", OccurredAt: "2026-10-20T12:00:00+05:00", CategoryID: &source.ID}
	expectStatus(t, request(f.router, "POST", "/api/v1/schedules/"+schedule.ID+"/occurrences/2026-10-20/post", user.AccessToken, uuid.NewString(), post), 200)
	expectStatus(t, request(f.router, "POST", "/api/v1/categories/"+source.ID+"/merge", user.AccessToken, uuid.NewString(), service.MergeCategoryInput{ExpectedVersion: 1, TargetCategoryID: target.ID}), 200)
	view := planningRead(t, f, user.AccessToken)
	if len(view.Budgets) != 1 || !view.Budgets[0].Archived || view.Budgets[0].Version != 2 || view.Schedules[0].Version != 2 || view.Schedules[0].CategoryID == nil || *view.Schedules[0].CategoryID != target.ID {
		t.Fatalf("merge planningpolicy failed budgets=%+v schedules=%+v target=%s actual=%s", view.Budgets, view.Schedules, target.ID, *view.Schedules[0].CategoryID)
	}
	for _, o := range view.Occurrences {
		if o.ScheduleID == schedule.ID && o.Status == "posted" && (o.CategoryID == nil || *o.CategoryID != source.ID) {
			t.Fatal("merge changed posted snapshot")
		}
	}
	var count int64
	f.db.WithContext(context.Background()).Model(&model.Transaction{}).Where("user_id=? AND category_id=?", user.User.ID, target.ID).Count(&count)
	if count != 1 {
		t.Fatal("ledger merge failed")
	}
}

func TestIntegrationP1ForecastFXActualScaleAndExplicitAccountBecomingSavings(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	upstream.clock.Advance(6 * 24 * time.Hour)
	upstream.body.Store(`[{"base":"USD","quote":"KZT","date":"2026-10-17","rate":450},{"base":"USD","quote":"JPY","date":"2026-10-17","rate":150}]`)
	f = withFX(t, f, upstream)
	user, other := registerUser(t, f), registerUser(t, f)
	kzt := fxAccount(t, f, user.AccessToken, "KZT", "10000")
	jpy := fxAccount(t, f, user.AccessToken, "JPY", "150")
	usd := fxAccount(t, f, user.AccessToken, "USD", "0")
	expense := planningScheduleBody(usd)
	expense.AmountMinor = "100"
	planningSaveSchedule(t, f, user, expense)
	settings := service.PlanningSettingsInput{BaseCurrency: "KZT", ReserveMinor: "0", AccountIDs: []string{kzt.ID, jpy.ID, usd.ID}}
	key := uuid.NewString()
	first := request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, key, settings)
	expectStatus(t, first, 200)
	forecast := forecastRead(t, f, user.AccessToken, "?until=2026-10-20")
	if !forecast.Complete || *forecast.BalanceMinor != "55000" || *forecast.MandatoryMinor != "45000" || *forecast.FreeMinor != "10000" || *forecast.PerDayMinor != "2500" || forecast.FetchedAt == nil || len(forecast.Accounts) != 3 {
		t.Fatalf("FXforecast %+v", forecast)
	}
	update := updateAccountBody(jpy)
	update.Type = "savings"
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+jpy.ID, user.AccessToken, uuid.NewString(), update), 200)
	after := forecastRead(t, f, user.AccessToken, "?until=2026-10-20")
	if len(after.Accounts) != 2 || *after.BalanceMinor != "10000" || *after.FreeMinor != "-35000" {
		t.Fatal("account changed to savings still counted as spendable")
	}
	replay := request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, key, settings)
	expectStatus(t, replay, 200)
	if !bytes.Equal(first.Body.Bytes(), replay.Body.Bytes()) {
		t.Fatal("settings receipt unstable")
	}
	settings.ExpectedVersion = 1
	settings.AccountIDs = []string{newLedgerAccount(t, f, other, "KZT").ID}
	expectStatus(t, request(f.router, "PUT", "/api/v1/planning/settings", user.AccessToken, uuid.NewString(), settings), 404)
}

func TestIntegrationP1PostFailureRollsBackMoneyAndResponseCapIsExplicit(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	schedule := planningSaveSchedule(t, f, user, planningScheduleBody(a))
	if err := f.db.Exec("ALTER TABLE planned_occurrences ADD CONSTRAINT synthetic_planning_failure CHECK(status<>'posted')").Error; err != nil {
		t.Fatal(err)
	}
	post := service.PostOccurrenceInput{ExpectedTemplateVersion: 1, AmountMinor: "10", OccurredAt: "2026-10-20T12:00:00+05:00"}
	path := "/api/v1/schedules/" + schedule.ID + "/occurrences/2026-10-20/post"
	key := uuid.NewString()
	expectStatus(t, request(f.router, "POST", path, user.AccessToken, key, post), 500)
	if accountBalance(t, f, user.User.ID, a.ID) != "100000" {
		t.Fatal("midpost failure leaked ledger mutation")
	}
	if err := f.db.Exec("ALTER TABLE planned_occurrences DROP CONSTRAINT synthetic_planning_failure").Error; err != nil {
		t.Fatal(err)
	}
	expectStatus(t, request(f.router, "POST", path, user.AccessToken, key, post), 200)
	start := time.Date(2025, 11, 1, 0, 0, 0, 0, time.UTC)
	var rows []model.Schedule
	for i := 0; i < 40; i++ {
		rows = append(rows, model.Schedule{ID: uuid.NewString(), UserID: user.User.ID, Name: fmt.Sprintf("Weekly%02d", i), AccountID: a.ID, Kind: "expense", Currency: "KZT", AmountMinor: 1, StartDate: start, Frequency: "weekly", Interval: 1, Mandatory: true, Version: 1, CreatedAt: start})
	}
	if err := f.db.Create(&rows).Error; err != nil {
		t.Fatal(err)
	}
	view := planningRead(t, f, user.AccessToken)
	if !view.TimelineTruncated || len(view.Occurrences) != 1000 || view.OverdueCount < 1000 {
		t.Fatal("response cap silently lost obligations")
	}
	forecast := forecastRead(t, f, user.AccessToken, "")
	if forecast.Complete || forecast.FreeMinor != nil || !forecast.TimelineTruncated {
		t.Fatal("truncated large calendar pretended safe")
	}
}

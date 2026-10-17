package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"sort"
	"time"
	"unicode/utf8"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type BudgetInput struct {
	ExpectedVersion int64  `json:"expected_version,omitempty"`
	Name            string `json:"name"`
	CategoryID      string `json:"category_id"`
	Currency        string `json:"currency"`
	LimitMinor      string `json:"limit_minor"`
	PeriodStartDay  int    `json:"period_start_day"`
	Archived        bool   `json:"archived"`
}
type BudgetView struct {
	ID               string `json:"id"`
	Name             string `json:"name"`
	CategoryID       string `json:"category_id"`
	CategoryName     string `json:"category_name"`
	CategoryArchived bool   `json:"category_archived"`
	Currency         string `json:"currency"`
	LimitMinor       string `json:"limit_minor"`
	PeriodStartDay   int    `json:"period_start_day"`
	Archived         bool   `json:"archived"`
	Version          int64  `json:"version"`
	PeriodFrom       string `json:"period_from"`
	PeriodTo         string `json:"period_to"`
	SpentMinor       string `json:"spent_minor"`
	RemainingMinor   string `json:"remaining_minor"`
}
type ScheduleInput struct {
	ExpectedVersion int64   `json:"expected_version,omitempty"`
	Name            string  `json:"name"`
	AccountID       string  `json:"account_id"`
	CategoryID      *string `json:"category_id"`
	Kind            string  `json:"kind"`
	AmountMinor     string  `json:"amount_minor"`
	Note            string  `json:"note"`
	StartDate       string  `json:"start_date"`
	EndDate         *string `json:"end_date"`
	Frequency       string  `json:"frequency"`
	Interval        int     `json:"interval"`
	Mandatory       bool    `json:"mandatory"`
	PrimaryIncome   bool    `json:"primary_income"`
	Paused          bool    `json:"paused"`
	Archived        bool    `json:"archived"`
}
type ScheduleView struct {
	ID             string  `json:"id"`
	Name           string  `json:"name"`
	AccountID      string  `json:"account_id"`
	CategoryID     *string `json:"category_id"`
	Kind           string  `json:"kind"`
	Currency       string  `json:"currency"`
	AmountMinor    string  `json:"amount_minor"`
	Note           string  `json:"note"`
	StartDate      string  `json:"start_date"`
	EndDate        *string `json:"end_date"`
	Frequency      string  `json:"frequency"`
	Interval       int     `json:"interval"`
	Mandatory      bool    `json:"mandatory"`
	PrimaryIncome  bool    `json:"primary_income"`
	Paused         bool    `json:"paused"`
	Archived       bool    `json:"archived"`
	Version        int64   `json:"version"`
	BlockedReason  *string `json:"blocked_reason"`
	ScheduleLocked bool    `json:"schedule_locked"`
}
type OccurrenceView struct {
	ScheduleID         string  `json:"schedule_id"`
	ScheduleVersion    int64   `json:"schedule_version"`
	DueDate            string  `json:"due_date"`
	Version            int64   `json:"version"`
	Name               string  `json:"name"`
	AccountID          string  `json:"account_id"`
	CategoryID         *string `json:"category_id"`
	Kind               string  `json:"kind"`
	Currency           string  `json:"currency"`
	AmountMinor        string  `json:"amount_minor"`
	Note               string  `json:"note"`
	Mandatory          bool    `json:"mandatory"`
	PrimaryIncome      bool    `json:"primary_income"`
	Status             string  `json:"status"`
	TransactionID      *string `json:"transaction_id"`
	TransactionDeleted bool    `json:"transaction_deleted"`
	BlockedReason      *string `json:"blocked_reason"`
}
type OccurrenceInput struct {
	ExpectedTemplateVersion   int64 `json:"expected_template_version"`
	ExpectedOccurrenceVersion int64 `json:"expected_occurrence_version"`
}
type PostOccurrenceInput struct {
	ExpectedTemplateVersion   int64   `json:"expected_template_version"`
	ExpectedOccurrenceVersion int64   `json:"expected_occurrence_version"`
	AmountMinor               string  `json:"amount_minor"`
	OccurredAt                string  `json:"occurred_at"`
	Note                      string  `json:"note"`
	CategoryID                *string `json:"category_id"`
}
type PlanningSettingsInput struct {
	ExpectedVersion int64    `json:"expected_version"`
	BaseCurrency    string   `json:"base_currency"`
	ReserveMinor    string   `json:"reserve_minor"`
	AccountIDs      []string `json:"account_ids"`
}
type PlanningSettingsView struct {
	Version      int64    `json:"version"`
	BaseCurrency string   `json:"base_currency"`
	ReserveMinor string   `json:"reserve_minor"`
	AccountIDs   []string `json:"account_ids"`
}
type PlanningView struct {
	Today             string               `json:"today"`
	Timezone          string               `json:"timezone"`
	From              string               `json:"from"`
	To                string               `json:"to"`
	OverdueFrom       string               `json:"overdue_from"`
	TimelineTruncated bool                 `json:"timeline_truncated"`
	OverdueCount      int64                `json:"overdue_count"`
	Budgets           []BudgetView         `json:"budgets"`
	Schedules         []ScheduleView       `json:"schedules"`
	Occurrences       []OccurrenceView     `json:"occurrences"`
	Settings          PlanningSettingsView `json:"settings"`
}

func (s *Service) planningNow() time.Time { return s.now() }
func planningName(raw string) (string, error) {
	name, _, err := ValidateCategory("expense", raw, nil)
	return name, err
}

func (s *Service) SaveBudget(ctx context.Context, userID, id, key string, input BudgetInput) ([]byte, error) {
	name, err := planningName(input.Name)
	if err != nil {
		return nil, err
	}
	categoryID, err := ParseID(input.CategoryID)
	if err != nil {
		return nil, err
	}
	value, err := ParseMinor(input.LimitMinor, true)
	if err != nil {
		return nil, err
	}
	if err = ValidateCurrency(input.Currency); err != nil {
		return nil, err
	}
	if input.PeriodStartDay < 1 || input.PeriodStartDay > 31 {
		return nil, Invalid("Period_start_day must be between 1 and 31.")
	}
	return s.mutate(ctx, userID, key, "SAVE /api/v1/budgets/"+id, input, func(tx *gorm.DB) (any, error) {
		b := model.Budget{ID: uuid.NewString(), UserID: userID, Version: 1, CreatedAt: s.planningNow().UTC()}
		if id != "" {
			b.ID = id
			if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id=? AND user_id=?", id, userID).First(&b).Error; err != nil {
				return nil, planningNotFound(err)
			}
			if err := checkVersion(b.Version, input.ExpectedVersion); err != nil {
				return nil, err
			}
			b.Version++
		} else if input.ExpectedVersion != 0 {
			return nil, Invalid("New budgets omit expected_version.")
		}
		c, err := findVisibleCategory(tx, userID, categoryID)
		if err != nil {
			return nil, err
		}
		if c.Kind != "expense" {
			return nil, Invalid("Budget category must be an expense category.")
		}
		if !input.Archived {
			if err := categoryActive(tx, userID, c); err != nil {
				return nil, err
			}
			var existing []model.Budget
			if err := tx.Where("user_id=? AND NOT archived AND currency=? AND id<>?", userID, input.Currency, b.ID).Find(&existing).Error; err != nil {
				return nil, err
			}
			for _, other := range existing {
				oc, err := findVisibleCategory(tx, userID, other.CategoryID)
				if err != nil {
					return nil, err
				}
				if c.ID == oc.ID || (c.ParentID != nil && *c.ParentID == oc.ID) || (oc.ParentID != nil && *oc.ParentID == c.ID) {
					return nil, &Error{409, "budget_overlap", "Active budgets in one currency cannot overlap a category subtree."}
				}
			}
		}
		b.Name, b.CategoryID, b.Currency, b.LimitMinor, b.PeriodStartDay, b.Archived = name, categoryID, input.Currency, value, input.PeriodStartDay, input.Archived
		if id == "" {
			var count int64
			if err := tx.Model(&model.Budget{}).Where("user_id=?", userID).Count(&count).Error; err != nil {
				return nil, err
			}
			if count >= 200 {
				return nil, &Error{409, "planning_limit", "At most 200 budgets are supported."}
			}
		}
		if err := tx.Save(&b).Error; err != nil {
			return nil, err
		}
		return budgetView(tx, userID, b, planningToday(s.planningNow()))
	})
}

func planningNotFound(err error) error {
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return &Error{404, "planning_not_found", "Planning item was not found."}
	}
	return err
}

func budgetView(tx *gorm.DB, userID string, b model.Budget, today time.Time) (BudgetView, error) {
	c, err := findVisibleCategory(tx, userID, b.CategoryID)
	if err != nil {
		return BudgetView{}, err
	}
	from, to := budgetPeriod(today, b.PeriodStartDay)
	fromKZ := time.Date(from.Year(), from.Month(), from.Day(), 0, 0, 0, 0, kazakhstan)
	toKZ := time.Date(to.Year(), to.Month(), to.Day(), 0, 0, 0, 0, kazakhstan)
	ids := []string{c.ID}
	if c.ParentID == nil {
		var children []string
		if err := tx.Model(&model.Category{}).Where("parent_id=? AND (user_id IS NULL OR user_id=?)", c.ID, userID).Pluck("id", &children).Error; err != nil {
			return BudgetView{}, err
		}
		ids = append(ids, children...)
	}
	var spent string
	if err := tx.Raw("SELECT COALESCE(SUM(amount_minor),0)::text FROM transactions WHERE user_id=? AND kind='expense' AND NOT exclude_from_analytics AND deleted_at IS NULL AND currency=? AND category_id IN ? AND occurred_at>=? AND occurred_at<?", userID, b.Currency, ids, fromKZ, toKZ).Scan(&spent).Error; err != nil {
		return BudgetView{}, err
	}
	sum, ok := new(big.Int).SetString(spent, 10)
	if !ok || !sum.IsInt64() {
		return BudgetView{}, conversionOverflow
	}
	remaining := new(big.Int).Sub(big.NewInt(b.LimitMinor), sum)
	if !remaining.IsInt64() {
		return BudgetView{}, conversionOverflow
	}
	archived := categoryActive(tx, userID, c) != nil
	return BudgetView{ID: b.ID, Name: b.Name, CategoryID: b.CategoryID, CategoryName: c.Name, CategoryArchived: archived, Currency: b.Currency, LimitMinor: fmt.Sprint(b.LimitMinor), PeriodStartDay: b.PeriodStartDay, Archived: b.Archived, Version: b.Version, PeriodFrom: from.Format(time.DateOnly), PeriodTo: to.Format(time.DateOnly), SpentMinor: spent, RemainingMinor: remaining.String()}, nil
}

func (s *Service) SaveSchedule(ctx context.Context, userID, id, key string, input ScheduleInput) ([]byte, error) {
	name, err := planningName(input.Name)
	if err != nil {
		return nil, err
	}
	accountID, err := ParseID(input.AccountID)
	if err != nil {
		return nil, err
	}
	categoryID, err := ParseOptionalID(input.CategoryID)
	if err != nil {
		return nil, err
	}
	amount, err := ParseMinor(input.AmountMinor, true)
	if err != nil {
		return nil, err
	}
	start, err := planningDate(input.StartDate)
	if err != nil {
		return nil, err
	}
	var end *time.Time
	if input.EndDate != nil {
		d, err := planningDate(*input.EndDate)
		if err != nil {
			return nil, err
		}
		if d.Before(start) {
			return nil, Invalid("End_date cannot precede start_date.")
		}
		end = &d
	}
	switch input.Frequency {
	case "once", "weekly", "monthly", "yearly":
	default:
		return nil, Invalid("Frequency must be once, weekly, monthly or yearly.")
	}
	if input.Interval < 1 || input.Interval > 12 {
		return nil, Invalid("Interval must be between 1 and 12.")
	}
	if input.Kind != "expense" && input.Kind != "income" {
		return nil, Invalid("Kind must be expense or income.")
	}
	if input.Mandatory && input.Kind != "expense" || input.PrimaryIncome && input.Kind != "income" {
		return nil, Invalid("Mandatory is for expenses, primary_income is for income.")
	}
	if !utf8.ValidString(input.Note) || utf8.RuneCountInString(input.Note) > 500 {
		return nil, Invalid("Note must contain at most 500 characters.")
	}
	return s.mutate(ctx, userID, key, "SAVE /api/v1/schedules/"+id, input, func(tx *gorm.DB) (any, error) {
		schedule := model.Schedule{ID: uuid.NewString(), UserID: userID, Version: 1, CreatedAt: s.planningNow().UTC()}
		if id != "" {
			schedule.ID = id
			if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id=? AND user_id=?", id, userID).First(&schedule).Error; err != nil {
				return nil, planningNotFound(err)
			}
			if err := checkVersion(schedule.Version, input.ExpectedVersion); err != nil {
				return nil, err
			}
			if !schedule.StartDate.Equal(start) || schedule.Frequency != input.Frequency || schedule.Interval != input.Interval {
				var count int64
				if err := tx.Model(&model.PlannedOccurrence{}).Where("schedule_id=?", schedule.ID).Count(&count).Error; err != nil {
					return nil, err
				}
				if count > 0 {
					return nil, &Error{409, "schedule_anchor_locked", "Create a new schedule to change recurrence after confirming or skipping an occurrence."}
				}
			}
			schedule.Version++
		} else if input.ExpectedVersion != 0 {
			return nil, Invalid("New schedules omit expected_version.")
		}
		active := !input.Paused && !input.Archived
		accounts, err := lockAccounts(tx, userID, []string{accountID}, active)
		if err != nil {
			return nil, err
		}
		if active {
			if err := validateCategoryAssignment(tx, userID, input.Kind, categoryID); err != nil {
				return nil, err
			}
		} else if categoryID != nil {
			c, err := findVisibleCategory(tx, userID, *categoryID)
			if err != nil {
				return nil, err
			}
			if c.Kind != input.Kind {
				return nil, Invalid("Category kind does not match schedule.")
			}
		}
		if id == "" {
			var count int64
			if err := tx.Model(&model.Schedule{}).Where("user_id=?", userID).Count(&count).Error; err != nil {
				return nil, err
			}
			if count >= 200 {
				return nil, &Error{409, "planning_limit", "At most 200 schedules are supported."}
			}
		}
		schedule.Name, schedule.AccountID, schedule.CategoryID, schedule.Kind, schedule.Currency, schedule.AmountMinor, schedule.Note = name, accountID, categoryID, input.Kind, accounts[accountID].Currency, amount, input.Note
		schedule.StartDate, schedule.EndDate, schedule.Frequency, schedule.Interval, schedule.Mandatory, schedule.PrimaryIncome, schedule.Paused, schedule.Archived = start, end, input.Frequency, input.Interval, input.Mandatory, input.PrimaryIncome, input.Paused, input.Archived
		if err := tx.Save(&schedule).Error; err != nil {
			return nil, err
		}
		return scheduleView(tx, userID, schedule)
	})
}

func scheduleBlocked(tx *gorm.DB, userID string, s model.Schedule) (*string, error) {
	reason := ""
	if s.Archived {
		reason = "archived_schedule"
	} else if s.Paused {
		reason = "paused"
	} else {
		var a model.Account
		if err := tx.Where("id=? AND user_id=?", s.AccountID, userID).First(&a).Error; err != nil {
			return nil, err
		}
		if a.Archived {
			reason = "archived_account"
		}
		if reason == "" && s.CategoryID != nil {
			c, err := findVisibleCategory(tx, userID, *s.CategoryID)
			if err != nil {
				return nil, err
			}
			if err := categoryActive(tx, userID, c); err != nil {
				reason = "archived_category"
			}
		}
	}
	if reason == "" {
		return nil, nil
	}
	return &reason, nil
}
func scheduleView(tx *gorm.DB, userID string, s model.Schedule) (ScheduleView, error) {
	var end *string
	if s.EndDate != nil {
		value := s.EndDate.Format(time.DateOnly)
		end = &value
	}
	blocked, err := scheduleBlocked(tx, userID, s)
	if err != nil {
		return ScheduleView{}, err
	}
	var count int64
	if err := tx.Model(&model.PlannedOccurrence{}).Where("schedule_id=? AND user_id=?", s.ID, userID).Count(&count).Error; err != nil {
		return ScheduleView{}, err
	}
	return ScheduleView{ID: s.ID, Name: s.Name, AccountID: s.AccountID, CategoryID: s.CategoryID, Kind: s.Kind, Currency: s.Currency, AmountMinor: fmt.Sprint(s.AmountMinor), Note: s.Note, StartDate: s.StartDate.Format(time.DateOnly), EndDate: end, Frequency: s.Frequency, Interval: s.Interval, Mandatory: s.Mandatory, PrimaryIncome: s.PrimaryIncome, Paused: s.Paused, Archived: s.Archived, Version: s.Version, BlockedReason: blocked, ScheduleLocked: count > 0}, nil
}
func pendingOccurrence(s model.Schedule, due time.Time, blocked *string) OccurrenceView {
	return OccurrenceView{ScheduleID: s.ID, ScheduleVersion: s.Version, DueDate: due.Format(time.DateOnly), Name: s.Name, AccountID: s.AccountID, CategoryID: s.CategoryID, Kind: s.Kind, Currency: s.Currency, AmountMinor: fmt.Sprint(s.AmountMinor), Note: s.Note, Mandatory: s.Mandatory, PrimaryIncome: s.PrimaryIncome, Status: "pending", BlockedReason: blocked}
}

func planningSettings(tx *gorm.DB, userID string) (PlanningSettingsView, error) {
	out := PlanningSettingsView{ReserveMinor: "0", AccountIDs: make([]string, 0)}
	var record model.PlanningSetting
	err := tx.Where("user_id=?", userID).First(&record).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		err = tx.Model(&model.User{}).Select("base_currency").Where("id=?", userID).Scan(&out.BaseCurrency).Error
		return out, err
	}
	if err != nil {
		return out, err
	}
	out.Version, out.ReserveMinor, out.BaseCurrency = record.Version, fmt.Sprint(record.ReserveMinor), record.ReserveCurrency
	err = json.Unmarshal([]byte(record.AccountIDs), &out.AccountIDs)
	return out, err
}
func (s *Service) SavePlanningSettings(ctx context.Context, userID, key string, input PlanningSettingsInput) ([]byte, error) {
	if err := ValidateCurrency(input.BaseCurrency); err != nil {
		return nil, err
	}
	reserve, err := ParseMinor(input.ReserveMinor, false)
	if err != nil {
		return nil, err
	}
	if reserve < 0 {
		return nil, Invalid("Reserve must be nonnegative.")
	}
	if len(input.AccountIDs) > 200 {
		return nil, Invalid("Select At most 200 spending accounts.")
	}
	ids := make([]string, 0, len(input.AccountIDs))
	seen := map[string]bool{}
	for _, raw := range input.AccountIDs {
		id, err := ParseID(raw)
		if err != nil {
			return nil, err
		}
		if seen[id] {
			return nil, Invalid("Spending account IDs must be unique.")
		}
		seen[id] = true
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return s.mutate(ctx, userID, key, "PUT /api/v1/planning/settings", input, func(tx *gorm.DB) (any, error) {
		current, err := planningSettings(tx, userID)
		if err != nil {
			return nil, err
		}
		if current.Version != input.ExpectedVersion || input.ExpectedVersion < 0 {
			return nil, &Error{409, "version_conflict", "Planning settings changed. Refresh before retrying."}
		}
		if current.Version > 0 {
			if err := checkVersion(current.Version, input.ExpectedVersion); err != nil {
				return nil, err
			}
		}
		accounts, err := lockAccounts(tx, userID, ids, true)
		if err != nil {
			return nil, err
		}
		for _, a := range accounts {
			if a.Type != "card" && a.Type != "cash" {
				return nil, Invalid("Spending accounts must be card or cash; savings and credit are excluded.")
			}
		}
		encoded, _ := json.Marshal(ids)
		record := model.PlanningSetting{UserID: userID, ReserveMinor: reserve, ReserveCurrency: input.BaseCurrency, AccountIDs: string(encoded), Version: current.Version + 1}
		if err := tx.Save(&record).Error; err != nil {
			return nil, err
		}
		return PlanningSettingsView{Version: record.Version, BaseCurrency: input.BaseCurrency, ReserveMinor: input.ReserveMinor, AccountIDs: ids}, nil
	})
}

func parsePlanningRange(now time.Time, fromRaw, toRaw string) (time.Time, time.Time, error) {
	today := planningToday(now)
	from, to := today, today.AddDate(0, 0, 90)
	var err error
	if fromRaw != "" {
		from, err = planningDate(fromRaw)
		if err != nil {
			return from, to, err
		}
	}
	if toRaw != "" {
		to, err = planningDate(toRaw)
		if err != nil {
			return from, to, err
		}
	}
	if to.Before(from) || to.Sub(from) > 365*24*time.Hour {
		return from, to, Invalid("Planning range must contain1 to366 inclusive days.")
	}
	if from.Before(today.AddDate(0, 0, -366)) || to.After(today.AddDate(0, 0, 366)) {
		return from, to, Invalid("Planning dates must be within366 days of today.")
	}
	return from, to, nil
}

func (s *Service) Planning(ctx context.Context, userID, fromRaw, toRaw string) (PlanningView, error) {
	from, to, err := parsePlanningRange(s.planningNow(), fromRaw, toRaw)
	if err != nil {
		return PlanningView{}, err
	}
	var view PlanningView
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		var err error
		view, err = s.planningView(tx, userID, from, to)
		return err
	})
	return view, err
}

func (s *Service) planningView(tx *gorm.DB, userID string, from, to time.Time) (PlanningView, error) {
	today := planningToday(s.planningNow())
	overdueFrom := today.AddDate(0, 0, -366)
	view := PlanningView{Today: today.Format(time.DateOnly), Timezone: "UTC+05:00", From: from.Format(time.DateOnly), To: to.Format(time.DateOnly), OverdueFrom: overdueFrom.Format(time.DateOnly), Budgets: make([]BudgetView, 0), Schedules: make([]ScheduleView, 0), Occurrences: make([]OccurrenceView, 0)}
	var err error
	view.Settings, err = planningSettings(tx, userID)
	if err != nil {
		return view, err
	}
	var budgets []model.Budget
	if err := tx.Where("user_id=?", userID).Order("created_at,id").Find(&budgets).Error; err != nil {
		return view, err
	}
	for _, b := range budgets {
		v, err := budgetView(tx, userID, b, today)
		if err != nil {
			return view, err
		}
		view.Budgets = append(view.Budgets, v)
	}
	var schedules []model.Schedule
	if err := tx.Where("user_id=?", userID).Order("created_at,id").Find(&schedules).Error; err != nil {
		return view, err
	}
	var saved []model.PlannedOccurrence
	if err := tx.Where("user_id=? AND due_date>=? AND due_date<=?", userID, overdueFrom, to).Find(&saved).Error; err != nil {
		return view, err
	}
	states := map[string]model.PlannedOccurrence{}
	closedBefore := map[string]int64{}
	closedOverdue := map[string]int64{}
	var closed []struct {
		ScheduleID   string
		CountOlder   int64
		CountOverdue int64
	}
	if err := tx.Raw(`SELECT o.schedule_id,count(*) FILTER(WHERE o.due_date<?) AS count_older,count(*) FILTER(WHERE o.due_date<?) AS count_overdue
 FROM planned_occurrences o JOIN schedules s ON s.id=o.schedule_id AND s.user_id=o.user_id
 WHERE o.user_id=? AND o.status<>'pending' AND (s.end_date IS NULL OR o.due_date<=s.end_date)
 GROUP BY o.schedule_id`, overdueFrom, today, userID).Scan(&closed).Error; err != nil {
		return view, err
	}
	for _, row := range closed {
		closedBefore[row.ScheduleID] = row.CountOlder
		closedOverdue[row.ScheduleID] = row.CountOverdue
	}
	for _, o := range saved {
		states[o.ScheduleID+"/"+o.DueDate.Format(time.DateOnly)] = o
	}
	transactionDeleted := map[string]bool{}
	var links []model.Transaction
	if err := tx.Where("user_id=? AND id IN (SELECT transaction_id FROM planned_occurrences WHERE user_id=? AND transaction_id IS NOT NULL AND due_date>=? AND due_date<=?)", userID, userID, overdueFrom, to).Find(&links).Error; err != nil {
		return view, err
	}
	for _, t := range links {
		transactionDeleted[t.ID] = t.DeletedAt != nil
	}
	emitted := map[string]bool{}
	currentTemplates := map[string]ScheduleView{}
	for _, schedule := range schedules {
		v, err := scheduleView(tx, userID, schedule)
		if err != nil {
			return view, err
		}
		view.Schedules = append(view.Schedules, v)
		currentTemplates[schedule.ID] = v
		if schedule.Paused || schedule.Archived {
			continue
		}
		// Count older unresolved dates arithmetically; never omit them while claiming
		// that an incomplete forecast is safe to spend.
		before := occurrenceIndex(schedule, overdueFrom)
		if schedule.EndDate != nil && schedule.EndDate.Before(overdueFrom) {
			before = occurrenceIndex(schedule, schedule.EndDate.AddDate(0, 0, 1))
		}
		older := int64(before) - closedBefore[schedule.ID]
		if older > 0 {
			view.TimelineTruncated = true
		}
		allBeforeToday := occurrenceIndex(schedule, today)
		if schedule.EndDate != nil && schedule.EndDate.Before(today) {
			allBeforeToday = occurrenceIndex(schedule, schedule.EndDate.AddDate(0, 0, 1))
		}
		if overdue := int64(allBeforeToday) - closedOverdue[schedule.ID]; overdue > 0 {
			view.OverdueCount += overdue
		}
		lower := from
		if overdueFrom.Before(lower) {
			lower = overdueFrom
		}
		for _, date := range scheduleDates(schedule, lower, to) {
			key := schedule.ID + "/" + date.Format(time.DateOnly)
			occurrence := pendingOccurrence(schedule, date, v.BlockedReason)
			if record, exists := states[key]; exists {
				occurrence.Version = record.Version
				if record.Status != "pending" {
					// Decode into a fresh value. The pending view shares category pointers
					// with its template; reusing it would mutate the visible template.
					var restored OccurrenceView
					if err := json.Unmarshal([]byte(record.Snapshot), &restored); err != nil {
						return view, err
					}
					occurrence = restored
					occurrence.Version, occurrence.Status, occurrence.TransactionID = record.Version, record.Status, record.TransactionID
					if record.Status == "skipped" {
						occurrence.ScheduleVersion = schedule.Version
						occurrence.BlockedReason = v.BlockedReason
					}
					if record.TransactionID != nil {
						occurrence.TransactionDeleted = transactionDeleted[*record.TransactionID]
					}
				}
			}
			if date.Before(from) && (occurrence.Status != "pending" || !date.Before(today)) {
				continue
			}
			view.Occurrences = append(view.Occurrences, occurrence)
			emitted[key] = true
		}
	}
	// Posted/skipped snapshots remain discoverable after pausing, archiving,
	// shortening an end date or changing a template's amount/category/account.
	for _, record := range saved {
		key := record.ScheduleID + "/" + record.DueDate.Format(time.DateOnly)
		if emitted[key] || record.Status == "pending" || record.DueDate.Before(from) || record.DueDate.After(to) {
			continue
		}
		var occurrence OccurrenceView
		if err := json.Unmarshal([]byte(record.Snapshot), &occurrence); err != nil {
			return view, err
		}
		occurrence.Version, occurrence.Status, occurrence.TransactionID = record.Version, record.Status, record.TransactionID
		if record.Status == "skipped" {
			current := currentTemplates[record.ScheduleID]
			occurrence.ScheduleVersion = current.Version
			occurrence.BlockedReason = current.BlockedReason
		}
		if record.TransactionID != nil {
			occurrence.TransactionDeleted = transactionDeleted[*record.TransactionID]
		}
		view.Occurrences = append(view.Occurrences, occurrence)
	}
	sort.Slice(view.Occurrences, func(i, j int) bool {
		a, b := view.Occurrences[i], view.Occurrences[j]
		if a.DueDate == b.DueDate {
			return a.ScheduleID < b.ScheduleID
		}
		return a.DueDate < b.DueDate
	})
	// Bound the response below serverless transport limits even with long UTF-8
	// notes and200 recurring templates. The exact overdue count above stays full.
	if len(view.Occurrences) > 1000 {
		view.Occurrences = view.Occurrences[:1000]
		view.TimelineTruncated = true
	}
	return view, nil
}

// Category merges keep future templates useful. Budgets on the source subtree
// are archived rather than silently merged/doubled; historical snapshots remain.
func mergePlanningCategories(tx *gorm.DB, userID string, sourceIDs []string, targetID string) error {
	if err := tx.Model(&model.Schedule{}).Where("user_id=? AND category_id IN ?", userID, sourceIDs).Updates(map[string]any{"category_id": targetID, "version": gorm.Expr("version+1")}).Error; err != nil {
		return err
	}
	return tx.Model(&model.Budget{}).Where("user_id=? AND category_id IN ? AND NOT archived", userID, sourceIDs).Updates(map[string]any{"archived": true, "version": gorm.Expr("version+1")}).Error
}

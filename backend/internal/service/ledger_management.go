package service

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type UpdateAccountInput struct {
	ExpectedVersion     int64  `json:"expected_version"`
	Name                string `json:"name"`
	Type                string `json:"type"`
	OpeningBalanceMinor string `json:"opening_balance_minor"`
	Archived            bool   `json:"archived"`
	IncludeInBalance    bool   `json:"include_in_balance"`
	SortOrder           int    `json:"sort_order"`
}

func validAccountType(kind string) error {
	switch kind {
	case "card", "cash", "savings", "credit", "other":
		return nil
	}
	return Invalid("Type must be card, cash, savings, credit or other.")
}

func (s *Service) CreateAccountIdempotent(ctx context.Context, userID, key, name, currency, opening, kind string) ([]byte, error) {
	if kind == "" {
		kind = "card"
	}
	if err := validAccountType(kind); err != nil {
		return nil, err
	}
	name, value, err := ValidateAccount(name, currency, opening)
	if err != nil {
		return nil, err
	}
	input := struct {
		Name     string
		Currency string
		Opening  string
		Type     string
	}{name, currency, opening, kind}
	return s.mutate(ctx, userID, key, "POST /api/v1/accounts", input, func(tx *gorm.DB) (any, error) {
		a := model.Account{ID: uuid.NewString(), UserID: userID, Name: name, Currency: currency, OpeningBalanceMinor: value, Type: kind, IncludeInBalance: true, Version: 1, CreatedAt: time.Now().UTC().Truncate(time.Microsecond)}
		if err := tx.Create(&a).Error; err != nil {
			return nil, err
		}
		return accountView(a, fmt.Sprint(value)), nil
	})
}

func (s *Service) UpdateAccount(ctx context.Context, userID, id, key string, input UpdateAccountInput) ([]byte, error) {
	if int64(input.SortOrder) < math.MinInt32 || int64(input.SortOrder) > math.MaxInt32 {
		return nil, Invalid("Sort_order must fit a signed 32-bit integer.")
	}
	if err := validAccountType(input.Type); err != nil {
		return nil, err
	}
	return s.mutate(ctx, userID, key, "PUT /api/v1/accounts/"+id, input, func(tx *gorm.DB) (any, error) {
		accounts, err := lockAccounts(tx, userID, []string{id}, false)
		if err != nil {
			return nil, err
		}
		a := accounts[id]
		if err := checkVersion(a.Version, input.ExpectedVersion); err != nil {
			return nil, err
		}
		name, value, err := ValidateAccount(input.Name, a.Currency, input.OpeningBalanceMinor)
		if err != nil {
			return nil, err
		}
		a.Name, a.Type, a.OpeningBalanceMinor, a.Archived, a.IncludeInBalance, a.SortOrder, a.Version = name, input.Type, value, input.Archived, input.IncludeInBalance, input.SortOrder, a.Version+1
		// Use a map so false values are stored despite model create defaults.
		if err := tx.Model(&model.Account{}).Where("id=?", a.ID).Updates(map[string]any{"name": a.Name, "type": a.Type, "opening_balance_minor": a.OpeningBalanceMinor, "archived": a.Archived, "include_in_balance": a.IncludeInBalance, "sort_order": a.SortOrder, "version": a.Version}).Error; err != nil {
			return nil, err
		}
		return currentAccountView(tx, userID, id)
	})
}

type UpdateCategoryInput struct {
	ExpectedVersion int64  `json:"expected_version"`
	Name            string `json:"name"`
	Archived        bool   `json:"archived"`
	SortOrder       int    `json:"sort_order"`
}
type MergeCategoryInput struct {
	ExpectedVersion  int64  `json:"expected_version"`
	TargetCategoryID string `json:"target_category_id"`
}

func saveCategory(tx *gorm.DB, userID string, c model.Category) error {
	if c.IsDefault {
		return tx.Clauses(clause.OnConflict{Columns: []clause.Column{{Name: "user_id"}, {Name: "category_id"}}, DoUpdates: clause.AssignmentColumns([]string{"name", "archived", "sort_order", "version"})}).Create(&model.CategoryOverride{UserID: userID, CategoryID: c.ID, Name: c.Name, Archived: c.Archived, SortOrder: c.SortOrder, Version: c.Version}).Error
	}
	return tx.Model(&model.Category{}).Where("id=? AND user_id=?", c.ID, userID).Updates(map[string]any{"name": c.Name, "archived": c.Archived, "sort_order": c.SortOrder, "version": c.Version}).Error
}

func (s *Service) UpdateCategory(ctx context.Context, userID, id, key string, input UpdateCategoryInput) ([]byte, error) {
	if int64(input.SortOrder) < math.MinInt32 || int64(input.SortOrder) > math.MaxInt32 {
		return nil, Invalid("Sort_order must fit a signed 32-bit integer.")
	}
	return s.mutate(ctx, userID, key, "PUT /api/v1/categories/"+id, input, func(tx *gorm.DB) (any, error) {
		c, err := findVisibleCategory(tx, userID, id)
		if err != nil {
			return nil, err
		}
		if err := checkVersion(c.Version, input.ExpectedVersion); err != nil {
			return nil, err
		}
		name, _, err := ValidateCategory(c.Kind, input.Name, c.ParentID)
		if err != nil {
			return nil, err
		}
		c.Name, c.Archived, c.SortOrder, c.Version = name, input.Archived, input.SortOrder, c.Version+1
		if !c.Archived {
			if err := categoryActive(tx, userID, c); err != nil {
				return nil, err
			}
			if err := validateCategoryName(tx, userID, c); err != nil {
				return nil, err
			}
		}
		if err := saveCategory(tx, userID, c); err != nil {
			return nil, err
		}
		if c.Archived && c.ParentID == nil {
			records, err := visibleCategories(tx, userID)
			if err != nil {
				return nil, err
			}
			for _, child := range records {
				if child.ParentID != nil && *child.ParentID == c.ID && !child.Archived {
					child.Archived = true
					child.Version++
					if err := saveCategory(tx, userID, child); err != nil {
						return nil, err
					}
				}
			}
		}
		return categoryView(c), nil
	})
}

func (s *Service) MergeCategory(ctx context.Context, userID, id, key string, input MergeCategoryInput) ([]byte, error) {
	targetID, err := ParseID(input.TargetCategoryID)
	if err != nil {
		return nil, err
	}
	return s.mutate(ctx, userID, key, "MERGE /api/v1/categories/"+id, input, func(tx *gorm.DB) (any, error) {
		source, err := findVisibleCategory(tx, userID, id)
		if err != nil {
			return nil, err
		}
		if err := checkVersion(source.Version, input.ExpectedVersion); err != nil {
			return nil, err
		}
		target, err := findVisibleCategory(tx, userID, targetID)
		if err != nil {
			return nil, err
		}
		if source.ID == target.ID || source.Kind != target.Kind {
			return nil, Invalid("Merge target must be another category of the same kind.")
		}
		if target.ParentID != nil && *target.ParentID == source.ID {
			return nil, Invalid("Cannot merge a category into its own subtree.")
		}
		if err := categoryActive(tx, userID, target); err != nil {
			return nil, err
		}
		records, err := visibleCategories(tx, userID)
		if err != nil {
			return nil, err
		}
		ids := []string{source.ID}
		for _, c := range records {
			if c.ParentID != nil && *c.ParentID == source.ID {
				ids = append(ids, c.ID)
			}
		}
		// A merge includes deleted history so restoring an old transaction keeps the
		// user's chosen category. Principal transfer legs have no category.
		if err := tx.Model(&model.Transaction{}).Where("user_id=? AND category_id IN ?", userID, ids).Updates(map[string]any{"category_id": target.ID, "version": gorm.Expr("version+1")}).Error; err != nil {
			return nil, err
		}
		if err := mergePlanningCategories(tx, userID, ids, target.ID); err != nil {
			return nil, err
		}
		for _, c := range records {
			for _, sourceID := range ids {
				if c.ID == sourceID {
					c.Archived = true
					c.Version++
					if err := saveCategory(tx, userID, c); err != nil {
						return nil, err
					}
					break
				}
			}
		}
		updated, err := visibleCategories(tx, userID)
		if err != nil {
			return nil, err
		}
		views := make([]CategoryView, 0, len(updated))
		for _, c := range updated {
			views = append(views, categoryView(c))
		}
		return map[string]any{"categories": views}, nil
	})
}

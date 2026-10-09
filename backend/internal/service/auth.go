package service

import (
	"context"
	"errors"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/auth"
	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type UserView struct {
	ID    string `json:"id"`
	Email string `json:"email"`
}

type AuthView struct {
	AccessToken string    `json:"access_token"`
	ExpiresAt   time.Time `json:"expires_at"`
	User        UserView  `json:"user"`
}

func (s *Service) Register(ctx context.Context, email, password string) (AuthView, error) {
	var view AuthView
	email, err := ValidateCredentials(email, password)
	if err != nil {
		return view, err
	}
	release, err := s.acquireHashSlot(ctx)
	if err != nil {
		return view, err
	}
	hash, err := auth.HashPassword(password)
	release()
	if err != nil {
		return view, err
	}
	user := model.User{ID: uuid.NewString(), Email: email, PasswordHash: hash, CreatedAt: time.Now().UTC().Truncate(time.Microsecond)}
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Create(&user).Error; err != nil {
			if errors.Is(err, gorm.ErrDuplicatedKey) {
				return &Error{409, "email_taken", "Email is already registered."}
			}
			return err
		}
		var sessionErr error
		view, sessionErr = s.createSession(tx, user)
		return sessionErr
	})
	return view, err
}

func (s *Service) Login(ctx context.Context, email, password string) (AuthView, error) {
	var view AuthView
	email, err := ValidateCredentials(email, password)
	if err != nil {
		return view, err
	}
	var user model.User
	err = s.DB.WithContext(ctx).Where("email = ?", email).First(&user).Error
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return view, err
	}
	hash := user.PasswordHash
	if errors.Is(err, gorm.ErrRecordNotFound) {
		hash = s.dummyHash
	}
	release, err := s.acquireHashSlot(ctx)
	if err != nil {
		return view, err
	}
	valid := auth.VerifyPassword(password, hash)
	release()
	if !valid || user.ID == "" {
		return view, &Error{401, "invalid_credentials", "Email or password is incorrect."}
	}
	return s.createSession(s.DB.WithContext(ctx), user)
}

func (s *Service) createSession(db *gorm.DB, user model.User) (AuthView, error) {
	var view AuthView
	token, hash, err := auth.NewToken()
	if err != nil {
		return view, err
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	session := model.Session{ID: uuid.NewString(), UserID: user.ID, TokenHash: hash, CreatedAt: now, ExpiresAt: now.Add(s.sessionTTL)}
	if err := db.Create(&session).Error; err != nil {
		return view, err
	}
	return AuthView{AccessToken: token, ExpiresAt: session.ExpiresAt, User: UserView{ID: user.ID, Email: user.Email}}, nil
}

func (s *Service) Authenticate(ctx context.Context, token string) (UserView, error) {
	var user UserView
	hash, err := auth.TokenHash(token)
	if err != nil {
		return user, Unauthorized
	}
	result := s.DB.WithContext(ctx).Raw(`SELECT users.id, users.email FROM users
		JOIN sessions ON sessions.user_id = users.id
		WHERE sessions.token_hash = ? AND sessions.revoked_at IS NULL AND sessions.expires_at > ?`, hash, time.Now().UTC()).Scan(&user)
	if result.Error != nil {
		return user, result.Error
	}
	if user.ID == "" {
		return user, Unauthorized
	}
	return user, nil
}

func (s *Service) Logout(ctx context.Context, token string) error {
	hash, err := auth.TokenHash(token)
	if err != nil {
		return Unauthorized
	}
	return s.DB.WithContext(ctx).Model(&model.Session{}).Where("token_hash = ? AND revoked_at IS NULL", hash).Update("revoked_at", time.Now().UTC()).Error
}

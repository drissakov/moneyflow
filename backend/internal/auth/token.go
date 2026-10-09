package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
)

func NewToken() (string, []byte, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	hash := sha256.Sum256([]byte(token))
	return token, hash[:], nil
}

func TokenHash(token string) ([]byte, error) {
	if len(token) != 43 {
		return nil, fmt.Errorf("invalid token")
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(token)
	if err != nil || len(raw) != 32 {
		return nil, fmt.Errorf("invalid token")
	}
	hash := sha256.Sum256([]byte(token))
	return hash[:], nil
}

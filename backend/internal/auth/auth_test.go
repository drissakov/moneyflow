package auth

import (
	"bytes"
	"strings"
	"testing"
)

func TestPasswordHashRandomSaltAndVerification(t *testing.T) {
	first, err := HashPassword("correct horse battery staple")
	if err != nil {
		t.Fatal(err)
	}
	second, err := HashPassword("correct horse battery staple")
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("hashes reuse their salt")
	}
	if !VerifyPassword("correct horse battery staple", first) || VerifyPassword("incorrect horse battery staple", first) {
		t.Fatal("password verification failed")
	}
	if VerifyPassword("correct horse battery staple", strings.Replace(first, "m=65536", "m=4294967295", 1)) {
		t.Fatal("unbounded parameters accepted")
	}
	if VerifyPassword("x", "invalid hash") {
		t.Fatal("invalid hash accepted")
	}
}

func TestOpaqueTokenHash(t *testing.T) {
	token, hash, err := NewToken()
	if err != nil {
		t.Fatal(err)
	}
	got, err := TokenHash(token)
	if err != nil || !bytes.Equal(got, hash) || len(hash) != 32 || len(token) != 43 {
		t.Fatal("token hash mismatch")
	}
	for _, invalid := range []string{"", "short", strings.Repeat("!", 43), token + "="} {
		if _, err := TokenHash(invalid); err == nil {
			t.Fatal("malformed token accepted")
		}
	}
}

package httpapi

import (
	"fmt"
	"testing"
	"time"
)

func TestAuthRateLimitAndBoundedMap(t *testing.T) {
	l := newAuthLimiter()
	now := time.Now()
	for i := 0; i < 20; i++ {
		if !l.allow("one", now) {
			t.Fatal("request below quota rejected")
		}
	}
	if l.allow("one", now) {
		t.Fatal("quota was not enforced")
	}
	if !l.allow("one", now.Add(time.Minute)) {
		t.Fatal("new window rejected")
	}
	for i := 0; i < 5000; i++ {
		l.allow(fmt.Sprint(i), now)
	}
	if len(l.windows) > 4096 {
		t.Fatal("limiter memory is unbounded")
	}
	if !l.allow("fresh", now.Add(2*time.Minute)) {
		t.Fatal("expired keys were not pruned")
	}
}

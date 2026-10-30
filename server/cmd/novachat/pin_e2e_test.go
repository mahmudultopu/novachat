package main

import (
	"fmt"
	"testing"
	"time"
)

func TestTwoStepPin(t *testing.T) {
	base := testServer(t)
	n := time.Now().UnixNano() % 10_000_000
	phone := fmt.Sprintf("+1764%07d", n)
	ana := signUp(t, base, phone, "Ana")

	var status struct{ Enabled bool }
	if code := get(t, base+"/v1/me/pin", ana.token, &status); code != 200 || status.Enabled {
		t.Fatalf("pin status: %d %+v", code, status)
	}
	if code := do(t, "PUT", base+"/v1/me/pin", ana.token, map[string]string{"pin": "12a456"}, nil); code != 400 {
		t.Fatalf("bad pin accepted: %d", code)
	}
	if code := do(t, "PUT", base+"/v1/me/pin", ana.token, map[string]string{"pin": "123456"}, nil); code != 204 {
		t.Fatalf("set pin: %d", code)
	}

	// Signing in again: the SMS code is not enough on its own.
	type verifyResult struct {
		Error        string
		Challenge    string
		Token        string
		AttemptsLeft int `json:"attempts_left"`
		User         struct{ ID string }
	}
	verify := func() verifyResult {
		t.Helper()
		var req struct {
			DevCode string `json:"dev_code"`
		}
		post(t, base+"/v1/auth/request-code", "", map[string]string{"phone": phone}, &req)
		var res verifyResult
		if code := post(t, base+"/v1/auth/verify", "", map[string]string{"phone": phone, "code": req.DevCode, "device_name": "new phone"}, &res); code != 403 || res.Error != "pin_required" || res.Challenge == "" || res.Token != "" {
			t.Fatalf("verify with pin: %d %+v", code, res)
		}
		return res
	}
	ch := verify().Challenge

	var res verifyResult
	if code := post(t, base+"/v1/auth/verify-pin", "", map[string]any{"challenge": ch, "pin": "000000"}, &res); code != 401 || res.Error != "bad_pin" || res.AttemptsLeft != 4 {
		t.Fatalf("wrong pin: %d %+v", code, res)
	}
	if code := post(t, base+"/v1/auth/verify-pin", "", map[string]any{"challenge": ch, "pin": "123456"}, &res); code != 200 || res.Token == "" || res.User.ID != ana.id {
		t.Fatalf("right pin: %d %+v", code, res)
	}
	// A challenge works once.
	if code := post(t, base+"/v1/auth/verify-pin", "", map[string]any{"challenge": ch, "pin": "123456"}, nil); code != 401 {
		t.Fatalf("challenge reused: %d", code)
	}

	// Forgetting the PIN starts a 7-day wait instead of signing in.
	ch = verify().Challenge
	var pending struct {
		Error   string
		ResetAt time.Time `json:"reset_at"`
	}
	if code := post(t, base+"/v1/auth/verify-pin", "", map[string]any{"challenge": ch, "forgot": true}, &pending); code != 403 ||
		pending.Error != "pin_reset_pending" || pending.ResetAt.Before(time.Now().Add(6*24*time.Hour)) {
		t.Fatalf("forgot pin: %d %+v", code, pending)
	}

	// Five wrong PINs lock sign-in, even with the right one.
	for i := 0; i < 5; i++ {
		post(t, base+"/v1/auth/verify-pin", "", map[string]any{"challenge": ch, "pin": "999999"}, nil)
	}
	if code := post(t, base+"/v1/auth/verify-pin", "", map[string]any{"challenge": ch, "pin": "123456"}, &res); code != 429 || res.Error != "pin_locked" {
		t.Fatalf("locked: %d %+v", code, res)
	}

	// Turning the PIN off from a signed-in phone lets the SMS code work alone again.
	if code := do(t, "DELETE", base+"/v1/me/pin", ana.token, nil, nil); code != 204 {
		t.Fatalf("remove pin: %d", code)
	}
	var req struct {
		DevCode string `json:"dev_code"`
	}
	post(t, base+"/v1/auth/request-code", "", map[string]string{"phone": phone}, &req)
	if code := post(t, base+"/v1/auth/verify", "", map[string]string{"phone": phone, "code": req.DevCode, "device_name": "x"}, &res); code != 200 || res.Token == "" {
		t.Fatalf("verify without pin: %d %+v", code, res)
	}
}

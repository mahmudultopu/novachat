package main

import (
	"fmt"
	"testing"
	"time"
)

func TestCallICEServers(t *testing.T) {
	base := testServer(t)
	if code := get(t, base+"/v1/calls/ice", "", nil); code != 401 {
		t.Fatalf("without a token: status %d", code)
	}
	ana := signUp(t, base, fmt.Sprintf("+1750%07d", time.Now().UnixNano()%10_000_000), "Ana")
	var resp struct {
		ICEServers []struct{ URLs []string } `json:"ice_servers"`
	}
	if code := get(t, base+"/v1/calls/ice", ana.token, &resp); code != 200 {
		t.Fatalf("status %d", code)
	}
	if len(resp.ICEServers) != 1 || resp.ICEServers[0].URLs[0] != "stun:stun.example.com:3478" {
		t.Fatalf("ice servers %+v", resp.ICEServers)
	}
}

package auth

import "testing"

func TestNormalizePhone(t *testing.T) {
	ok := map[string]string{
		"+8801712345678":     "+8801712345678",
		" +880 1712-345678 ": "+8801712345678",
		"+1 (415) 555.0100":  "+14155550100",
	}
	for in, want := range ok {
		got, err := NormalizePhone(in)
		if err != nil || got != want {
			t.Errorf("NormalizePhone(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, in := range []string{"", "01712345678", "+0123456789", "+12", "+1234567890123456", "+1 415 abc", "++14155550100"} {
		if got, err := NormalizePhone(in); err == nil {
			t.Errorf("NormalizePhone(%q) = %q; want error", in, got)
		}
	}
}

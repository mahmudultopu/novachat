package auth

import (
	"errors"
	"strings"
)

var ErrInvalidPhone = errors.New("phone number must be in international format, like +8801712345678")

// NormalizePhone turns user input such as "+880 1712-345678" into E.164
// ("+8801712345678"). It does not check that the number really exists; the
// SMS code does that.
func NormalizePhone(in string) (string, error) {
	var b strings.Builder
	for i, r := range strings.TrimSpace(in) {
		switch {
		case r == '+' && i == 0:
			b.WriteRune(r)
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		case r == ' ' || r == '-' || r == '(' || r == ')' || r == '.':
			// formatting characters are dropped
		default:
			return "", ErrInvalidPhone
		}
	}
	out := b.String()
	if !strings.HasPrefix(out, "+") {
		return "", ErrInvalidPhone
	}
	digits := len(out) - 1
	if digits < 8 || digits > 15 || out[1] == '0' {
		return "", ErrInvalidPhone
	}
	return out, nil
}

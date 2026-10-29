package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	otpTTL           = 5 * time.Minute
	otpMaxAttempts   = 5
	otpRequestWindow = time.Hour
	otpMaxRequests   = 10
)

var (
	ErrTooManyRequests = errors.New("too many codes requested, try again later")
	ErrTooManyAttempts = errors.New("too many wrong codes, request a new one")
	ErrBadCode         = errors.New("wrong or expired code")
	ErrNoSMSProvider   = errors.New("no SMS provider is configured")
)

// SMSSender delivers a one-time code. Production plugs in a real provider
// (Twilio Verify, Vonage, Firebase Phone Auth...). Nil means none is set up.
type SMSSender interface {
	SendCode(ctx context.Context, phone, code string) error
}

// OTP issues and checks one-time sign-up codes, kept in Redis with a TTL.
type OTP struct {
	Redis *redis.Client
	SMS   SMSSender
	// DevCode, when set, replaces the random code. Dev mode only.
	DevCode string
}

// Request creates a code for phone and sends it. It returns the code only
// when DevCode is set, so the API can show it to developers.
func (o *OTP) Request(ctx context.Context, phone string) (devCode string, err error) {
	rlKey := "otp:req:" + phone
	n, err := o.Redis.Incr(ctx, rlKey).Result()
	if err != nil {
		return "", err
	}
	if n == 1 {
		o.Redis.Expire(ctx, rlKey, otpRequestWindow)
	}
	if n > otpMaxRequests {
		return "", ErrTooManyRequests
	}

	code := o.DevCode
	if code == "" {
		if o.SMS == nil {
			return "", ErrNoSMSProvider
		}
		if code, err = randomCode(); err != nil {
			return "", err
		}
	}

	pipe := o.Redis.TxPipeline()
	pipe.Set(ctx, "otp:code:"+phone, hashCode(phone, code), otpTTL)
	pipe.Del(ctx, "otp:tries:"+phone)
	if _, err := pipe.Exec(ctx); err != nil {
		return "", err
	}

	if o.DevCode != "" {
		slog.Info("dev OTP issued", "phone", phone, "code", code)
		return code, nil
	}
	return "", o.SMS.SendCode(ctx, phone, code)
}

// Verify checks a code. A correct code can be used only once.
func (o *OTP) Verify(ctx context.Context, phone, code string) error {
	triesKey := "otp:tries:" + phone
	tries, err := o.Redis.Incr(ctx, triesKey).Result()
	if err != nil {
		return err
	}
	if tries == 1 {
		o.Redis.Expire(ctx, triesKey, otpTTL)
	}
	if tries > otpMaxAttempts {
		o.Redis.Del(ctx, "otp:code:"+phone)
		return ErrTooManyAttempts
	}

	stored, err := o.Redis.Get(ctx, "otp:code:"+phone).Bytes()
	if errors.Is(err, redis.Nil) {
		return ErrBadCode
	}
	if err != nil {
		return err
	}
	if subtle.ConstantTimeCompare(stored, hashCode(phone, code)) != 1 {
		return ErrBadCode
	}
	o.Redis.Del(ctx, "otp:code:"+phone, triesKey)
	return nil
}

func randomCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

func hashCode(phone, code string) []byte {
	sum := sha256.Sum256([]byte(phone + ":" + code))
	return sum[:]
}

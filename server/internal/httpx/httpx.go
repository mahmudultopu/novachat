// Package httpx holds small JSON helpers shared by the HTTP handlers.
package httpx

import (
	"encoding/json"
	"errors"
	"net/http"
)

func JSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func Error(w http.ResponseWriter, status int, code, message string) {
	JSON(w, status, map[string]string{"error": code, "message": message})
}

// Decode reads a JSON body of at most 64 KB into v.
func Decode(w http.ResponseWriter, r *http.Request, v any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			Error(w, http.StatusRequestEntityTooLarge, "too_large", "request body too large")
		} else {
			Error(w, http.StatusBadRequest, "bad_json", "invalid JSON body")
		}
		return false
	}
	return true
}

FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /novachat ./cmd/novachat

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /novachat /novachat
EXPOSE 8080
ENTRYPOINT ["/novachat"]

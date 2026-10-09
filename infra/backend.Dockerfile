# syntax=docker/dockerfile:1
FROM golang:1.25.14-alpine3.23 AS build
WORKDIR /src
COPY backend/go.mod backend/go.sum ./
RUN go mod download
COPY backend/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/api ./cmd/api \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/migrate ./cmd/migrate

FROM alpine:3.24.1 AS runtime
RUN apk add --no-cache ca-certificates tzdata \
    && addgroup -S -g 10001 moneyflow \
    && adduser -S -D -H -u 10001 -G moneyflow moneyflow
WORKDIR /app
COPY --from=build /out/api /out/migrate /app/
USER moneyflow
EXPOSE 8080
ENTRYPOINT ["/app/api"]

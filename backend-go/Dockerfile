# Builds a minimal image containing only the Hauk Go backend. Build from the
# repository root:
#
#   docker build -f backend-go/Dockerfile -t hauk-go .
#   docker run -p 8080:8080 -v /etc/hauk:/etc/hauk:ro hauk-go

FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY web.go ./
COPY frontend/ frontend/
COPY backend-go/ backend-go/
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /hauk ./backend-go

FROM scratch
COPY --from=build /hauk /hauk
USER 65534:65534
EXPOSE 8080/tcp
VOLUME /etc/hauk
ENTRYPOINT ["/hauk"]

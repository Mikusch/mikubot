#!/usr/bin/env bash
set -euo pipefail

panel="${PANEL_URL%/}"
server="$SERVER_ID"
jar_path="build/libs/mikubot.jar"

api() {
    local method="$1"
    local path="$2"
    local payload="${3-}"
    local response status body

    if [ -n "$payload" ]; then
        response=$(curl -sS -X "$method" "$panel$path" \
            -H "Authorization: Bearer $API_KEY" \
            -H "Accept: application/vnd.pterodactyl.v1+json" \
            -H "Content-Type: application/json" \
            -d "$payload" \
            -w '\n%{http_code}')
    else
        response=$(curl -sS -X "$method" "$panel$path" \
            -H "Authorization: Bearer $API_KEY" \
            -H "Accept: application/vnd.pterodactyl.v1+json" \
            -w '\n%{http_code}')
    fi

    status=${response##*$'\n'}
    body=${response%$'\n'*}

    if [ "$status" -ge 400 ]; then
        echo "HTTP $status from $method $path" >&2
        echo "$body" >&2
        return 1
    fi

    printf '%s' "$body"
}

current_state() {
    api GET "/api/client/servers/$server/resources" | jq -r '.attributes.current_state'
}

wait_for_state() {
    local target="$1"
    local attempts="$2"
    local state

    for _ in $(seq 1 "$attempts"); do
        state=$(current_state || echo "unknown")
        echo "Server state: $state"
        if [ "$state" = "$target" ]; then
            return 0
        fi
        sleep 2
    done

    return 1
}

check() {
    case "$API_KEY" in
        ptlc_*) ;;
        ptla_*)
            echo "PTERODACTYL_API_KEY is an application key (ptla_)." >&2
            echo "The client API needs a client key (ptlc_) from Account -> API Credentials." >&2
            exit 1
            ;;
        "Bearer "*)
            echo "PTERODACTYL_API_KEY includes the 'Bearer ' prefix. Store only the key itself." >&2
            exit 1
            ;;
        *)
            echo "Warning: PTERODACTYL_API_KEY does not start with 'ptlc_'." >&2
            ;;
    esac

    case "$panel" in
        https://*) ;;
        http://*)
            echo "Warning: PTERODACTYL_URL is not HTTPS." >&2
            ;;
        *)
            echo "PTERODACTYL_URL must include the scheme, for example https://panel.example.com" >&2
            exit 1
            ;;
    esac

    local name
    name=$(api GET "/api/client/servers/$server" | jq -r '.attributes.name')
    echo "Authenticated against $panel, server $server ($name)"
}

stop_server() {
    echo "Stopping server"
    api POST "/api/client/servers/$server/power" '{"signal":"stop"}' >/dev/null

    if wait_for_state offline 30; then
        return 0
    fi

    echo "Server did not stop gracefully, killing it"
    api POST "/api/client/servers/$server/power" '{"signal":"kill"}' >/dev/null

    if wait_for_state offline 15; then
        return 0
    fi

    echo "Server is still not offline, aborting deploy" >&2
    exit 1
}

upload_jar() {
    if [ ! -f "$jar_path" ]; then
        echo "Jar not found at $jar_path" >&2
        exit 1
    fi

    local upload_url status log
    upload_url=$(api GET "/api/client/servers/$server/files/upload?directory=%2F" | jq -r '.attributes.url')

    if [ -z "$upload_url" ] || [ "$upload_url" = "null" ]; then
        echo "Failed to obtain a signed upload URL" >&2
        exit 1
    fi

    log="${RUNNER_TEMP:-/tmp}/pterodactyl-upload.log"
    echo "Uploading $jar_path as $JAR_NAME"
    status=$(curl -sS -o "$log" -w '%{http_code}' -X POST "$upload_url&directory=%2F" \
        -F "files=@$jar_path;filename=$JAR_NAME")

    if [ "$status" -ge 400 ]; then
        echo "HTTP $status while uploading the jar" >&2
        cat "$log" >&2
        exit 1
    fi

    echo "Uploaded $JAR_NAME"
}

start_server() {
    echo "Starting server"
    api POST "/api/client/servers/$server/power" '{"signal":"start"}' >/dev/null
}

case "${1:-deploy}" in
    check)
        check
        ;;
    deploy)
        check
        stop_server
        upload_jar
        start_server
        if ! wait_for_state running 30; then
            echo "Server did not reach the running state in time" >&2
            exit 1
        fi
        echo "Deploy complete"
        ;;
    start)
        start_server
        ;;
    *)
        echo "Usage: $0 [check|deploy|start]" >&2
        exit 1
        ;;
esac

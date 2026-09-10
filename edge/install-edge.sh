#!/usr/bin/env bash
#
# Australis edge installer (Layer 0/1 self-mode).
# Sets up the TCP forwarder (hides origin, PROXY protocol), the nftables
# blocklist, and the feedback agent on a fresh Linux box — e.g. an Oracle Cloud
# always-free VM. Run as root on the EDGE box, not the origin.
#
#   sudo ORIGIN=10.8.0.1:25565 ./install-edge.sh
#
# This script is idempotent: re-running it preserves the existing agent TOKEN
# (so you don't have to re-edit the plugin config) unless you pass TOKEN=... to
# force a new one.
#
# Environment variables:
#   ORIGIN            (required) hidden origin address host:port the edge forwards to
#   LISTEN            public listen address                 (default :25565)
#   AGENT_LISTEN      feedback agent bind address           (default 127.0.0.1:8787)
#   IFACE             NIC for the (optional) XDP filter     (default: auto-detected)
#   TOKEN             shared token for the agent            (default: reuse existing / generate)
#   MAX_CONNS         forwarder global concurrency cap      (default 8192)
#   MAX_CONNS_PER_IP  forwarder per-source-IP cap           (default 64)
#   IDLE_TIMEOUT      forwarder idle/slowloris timeout      (default 5m)
#   METRICS           forwarder metrics endpoint addr       (default: empty/disabled, e.g. 127.0.0.1:9100)
#
# After it runs, put the printed TOKEN + AGENT_LISTEN into the plugin's
# config.yml `edge:` section, and lock down the origin (see docs/DEPLOYMENT.md).

set -euo pipefail

LISTEN="${LISTEN:-:25565}"
AGENT_LISTEN="${AGENT_LISTEN:-127.0.0.1:8787}"
ORIGIN="${ORIGIN:-}"
IFACE="${IFACE:-$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}')}"
MAX_CONNS="${MAX_CONNS:-8192}"
MAX_CONNS_PER_IP="${MAX_CONNS_PER_IP:-64}"
IDLE_TIMEOUT="${IDLE_TIMEOUT:-5m}"
METRICS="${METRICS:-}"

# Idempotent token handling: reuse the token from a previous install unless the
# caller explicitly overrode TOKEN, otherwise generate a fresh one.
if [ -z "${TOKEN:-}" ] && [ -f /etc/australis/agent.env ]; then
    TOKEN="$(sed -n 's/^TOKEN=//p' /etc/australis/agent.env | head -n1)"
fi
TOKEN="${TOKEN:-$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 32)}"

PORT="${LISTEN##*:}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '\033[36m[australis]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[australis] %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo)."
[ -n "$ORIGIN" ] || die "ORIGIN is required, e.g. ORIGIN=10.8.0.1:25565 sudo ./install-edge.sh"
[ -n "$IFACE" ] || die "could not detect network interface; set IFACE=eth0"

log "installing dependencies..."
if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq nftables golang-go >/dev/null
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y -q nftables golang >/dev/null
else
    log "unknown package manager — ensure 'nftables' and 'go' are installed."
fi

log "building edge binaries..."
mkdir -p /opt/australis /etc/australis
if command -v go >/dev/null 2>&1; then
    ( cd "$SCRIPT_DIR" && GOFLAGS=-mod=mod GOPROXY=off \
        go build -o /opt/australis/forwarder ./cmd/forwarder \
        && GOFLAGS=-mod=mod GOPROXY=off go build -o /opt/australis/agent ./cmd/agent )
elif [ -f "$SCRIPT_DIR/bin/forwarder" ] && [ -f "$SCRIPT_DIR/bin/agent" ]; then
    cp "$SCRIPT_DIR/bin/forwarder" "$SCRIPT_DIR/bin/agent" /opt/australis/
else
    die "go not found and no prebuilt binaries in ./bin"
fi

log "writing config..."
# Scope the fallback SYN rate-limit to the actual game port.
sed "s/^define GAME_PORT = .*/define GAME_PORT = $PORT/" \
    "$SCRIPT_DIR/nftables/australis.nft" > /etc/australis/australis.nft
cat > /etc/australis/forwarder.env <<EOF
LISTEN=$LISTEN
ORIGIN=$ORIGIN
MAX_CONNS=$MAX_CONNS
MAX_CONNS_PER_IP=$MAX_CONNS_PER_IP
IDLE_TIMEOUT=$IDLE_TIMEOUT
METRICS=$METRICS
EOF
cat > /etc/australis/agent.env <<EOF
LISTEN=$AGENT_LISTEN
TOKEN=$TOKEN
EOF
# xdp.env is written for convenience if you later install an XDP loader; the
# australis-xdp.service is inert until /opt/australis/xdp-loader exists.
cat > /etc/australis/xdp.env <<EOF
IFACE=$IFACE
PORT=$PORT
EOF
# Lock down the config dir + secret-bearing env files. forwarder.env holds the
# hidden ORIGIN address (the whole point of the product is to keep it secret) and
# agent.env holds the shared token, so neither may be world-readable.
chmod 700 /etc/australis
chmod 600 /etc/australis/agent.env /etc/australis/forwarder.env /etc/australis/xdp.env

log "installing systemd units..."
# The xdp unit is installed so the file exists and is internally consistent, but
# it is NOT enabled or started: it has ConditionPathExists=/opt/australis/xdp-loader
# and there is no bundled loader yet, so it stays inert. See edge/xdp/README.md.
cp "$SCRIPT_DIR"/systemd/australis-nftables.service \
   "$SCRIPT_DIR"/systemd/australis-forwarder.service \
   "$SCRIPT_DIR"/systemd/australis-agent.service \
   "$SCRIPT_DIR"/systemd/australis-xdp.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now australis-nftables.service
systemctl enable --now australis-agent.service
systemctl enable --now australis-forwarder.service

# Players must be able to reach the game port on THIS edge box. Open it on the
# host firewall if ufw is active. NOTE: on cloud VMs (Oracle/AWS/GCP) you must
# ALSO open it in the provider's security list / NSG — the installer can't do that.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
    ufw allow "${PORT}/tcp" >/dev/null 2>&1 && log "opened ${PORT}/tcp in ufw"
fi

# Best-effort detect this edge's public IP for the origin-lockdown hint below.
EDGE_IP="$(ip -4 addr show "$IFACE" 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | head -n1)"
ORIGIN_PORT="${ORIGIN##*:}"

log "done."
echo
echo "  Edge is up. Players connect to this box on ${LISTEN}; it forwards to ${ORIGIN}."
echo
echo "  1) In the plugin's config.yml set:"
echo "       edge:"
echo "         enabled: true"
echo "         url: \"http://${AGENT_LISTEN}\"    # reach this over your private link (WireGuard)"
echo "         token: \"${TOKEN}\""
echo
echo "  2) On the ORIGIN Velocity, set (velocity.toml, [advanced]):  haproxy-protocol = true"
echo "  3) Lock the origin so its game port ONLY accepts this edge — otherwise anyone"
echo "     who finds the origin IP can forge any client IP via PROXY protocol. On the ORIGIN:"
echo "       sudo ufw allow from ${EDGE_IP:-<EDGE_IP>} to any port ${ORIGIN_PORT} proto tcp"
echo "       sudo ufw deny  ${ORIGIN_PORT}/tcp"
echo "  4) Open ${PORT}/tcp in your cloud provider's security list / NSG (not just ufw)."
echo
echo "  Active now: nftables blocklist, feedback agent, TCP forwarder"
echo "  (global cap ${MAX_CONNS}, per-IP cap ${MAX_CONNS_PER_IP}, idle timeout ${IDLE_TIMEOUT})."
if [ -n "$METRICS" ]; then
    echo "  Forwarder metrics: http://${METRICS}/metrics"
fi
echo
echo "  NOT active: XDP/eBPF line-rate filter. There is no bundled loader yet —"
echo "  edge/xdp/ is a design note. The australis-xdp.service unit is installed but"
echo "  inert (it only starts once /opt/australis/xdp-loader exists). To add it later,"
echo "  follow edge/xdp/README.md to build/install a loader, then:"
echo "       systemctl enable --now australis-xdp.service"

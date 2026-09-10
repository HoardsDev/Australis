#!/usr/bin/env bash
#
# Australis edge installer (Layer 0/1 self-mode).
# Sets up the TCP forwarder (hides origin, PROXY protocol), the nftables
# blocklist, and the feedback agent on a fresh Linux box — e.g. an Oracle Cloud
# always-free VM. Run as root on the EDGE box, not the origin.
#
#   sudo ORIGIN=10.8.0.1:25565 ./install-edge.sh
#
# Environment variables:
#   ORIGIN        (required) hidden origin address host:port the edge forwards to
#   LISTEN        public listen address           (default :25565)
#   AGENT_LISTEN  feedback agent bind address     (default 127.0.0.1:8787)
#   IFACE         NIC for the XDP filter          (default: auto-detected)
#   TOKEN         shared token for the agent      (default: generated)
#
# After it runs, put the printed TOKEN + AGENT_LISTEN into the plugin's
# config.yml `edge:` section, and lock down the origin (see docs/DEPLOYMENT.md).

set -euo pipefail

LISTEN="${LISTEN:-:25565}"
AGENT_LISTEN="${AGENT_LISTEN:-127.0.0.1:8787}"
ORIGIN="${ORIGIN:-}"
IFACE="${IFACE:-$(ip route show default 2>/dev/null | awk '/default/ {print $5; exit}')}"
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
cp "$SCRIPT_DIR/nftables/australis.nft" /etc/australis/australis.nft
cat > /etc/australis/forwarder.env <<EOF
LISTEN=$LISTEN
ORIGIN=$ORIGIN
EOF
cat > /etc/australis/agent.env <<EOF
LISTEN=$AGENT_LISTEN
TOKEN=$TOKEN
EOF
cat > /etc/australis/xdp.env <<EOF
IFACE=$IFACE
PORT=$PORT
EOF
chmod 600 /etc/australis/agent.env

log "installing systemd units..."
cp "$SCRIPT_DIR"/systemd/australis-nftables.service \
   "$SCRIPT_DIR"/systemd/australis-forwarder.service \
   "$SCRIPT_DIR"/systemd/australis-agent.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now australis-nftables.service
systemctl enable --now australis-agent.service
systemctl enable --now australis-forwarder.service

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
echo "  2) Enable proxy-protocol on the ORIGIN Velocity (velocity.toml: proxy-protocol = true)."
echo "  3) Lock the origin so its game port only accepts this edge (docs/DEPLOYMENT.md)."
echo "  4) (Recommended) Add the XDP filter for line-rate L3/L4: edge/xdp/README.md,"
echo "     then: systemctl enable --now australis-xdp.service"

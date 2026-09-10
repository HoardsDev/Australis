# Australis testkit — `floodtest`

A Minecraft-protocol load/attack harness for measuring Australis's effectiveness.
It reproduces the attack vectors Australis defends against so you can watch the
protection work (or find gaps) against a real proxy.

> ⚠️ **LEGAL / SAFETY.** Run this **only** against a target you own or are
> explicitly authorized to test (e.g. your own test network). Pointing
> it at anyone else's server is a denial-of-service attack and a crime. The tool
> refuses to run without the `-i-own-this-target` flag, by design. Don't remove
> that gate.

## Why run from your own machine
It generates real connection/flood traffic, so it must originate from a host you
control on a network that permits it — not from a shared/cloud sandbox. Build it
locally and run it against your test target.

## Build
```bash
cd testkit && go build -o floodtest ./cmd/floodtest
```

## Modes
| Mode   | Simulates | What it tells you |
|--------|-----------|-------------------|
| `ping` | status/MOTD ping flood | response rate + latency under ping pressure |
| `join` | bot join flood (handshake + login start) | how many joins the proxy **rejects** vs lets through |
| `conn` | raw TCP connect/close flood | connection-accept behaviour / edge SYN handling |
| `recon`| a real client's reconnect | whether the reconnect challenge lets a returning client in |

## Usage
```bash
# Ping flood: 200 workers, 2000/s, 30s
./floodtest -target play.example.net:25565 -mode ping -c 200 -rate 2000 -duration 30s -i-own-this-target

# Bot join flood, unthrottled (max load), 20s
./floodtest -target play.example.net:25565 -mode join -c 500 -duration 20s -i-own-this-target

# Reconnect-challenge check (should mostly get in on the 2nd try if verification is on)
./floodtest -target play.example.net:25565 -mode recon -c 50 -rate 100 -duration 20s -i-own-this-target
```

Flags: `-target host:port`, `-mode`, `-c` workers, `-rate` attempts/sec
(`0` = unthrottled), `-duration`, `-timeout`, `-protocol` (MC protocol number),
`-uuid` (send a UUID in login start, 1.20.2+ format).

## Reading the report
```
attempts / engaged / rejected / reset / errors + latency p50/p95/p99
```
- **rejected** = the proxy kicked the connection → Australis (or auth/rate limit)
  is doing its job. Under a `join` flood with Australis on and under attack, you
  want this high.
- **engaged** = the connection got accepted into login/status → traffic is
  getting through. High `engaged` under a flood means something isn't catching it.
- **reset** = dropped before responding (edge/kernel drop, or overload).
- **latency** staying low while rejecting is the healthy signature.

## How to run a real evaluation
1. Baseline: point `floodtest` at the proxy with Australis **disabled** — note
   how quickly `engaged` climbs and latency degrades.
2. Enable Australis, repeat the same run — `rejected`/`reset` should dominate and
   latency should stay flat.
3. Watch `/australis stats` on the proxy during the run to see blocks, the attack
   detector flipping on, and (in Mode B) edge blocklist pushes.

## Regression lab (`lab.sh`)
`lab.sh` runs a whole matrix of scenarios in one go and writes a timestamped
report + a markdown summary table, so you can re-run it after changes and compare.
```bash
go build -o floodtest ./cmd/floodtest
./lab.sh play.example.net:25565 --i-own-this-target
# custom matrix ("mode c rate duration", one per line):
LAB_SCENARIOS=$'ping 100 0 10s\njoin 200 0 10s' ./lab.sh play.example.net:25565 --i-own-this-target
```
Run it once with Australis off (baseline) and once on, then diff the two
`summary.md` files to see the protection's effect. Same `--i-own-this-target`
gate — your infra only.

## Tested
Protocol helpers (VarInt, framing, handshake, login start) are unit-tested
(`go test ./...`); the ping path was validated end-to-end against a mock status
server; and `lab.sh` is validated against a live proxy (see
`docs/FINDINGS-live-test.md`).

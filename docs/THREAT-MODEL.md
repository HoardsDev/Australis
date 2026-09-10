# Australis — Threat Model

A precise map of *what* attacks exist, *how* they hurt a Minecraft server, and
*exactly* which Australis layer neutralizes each. This is the reference that keeps
us honest: if an attack isn't on this list with a defense, we don't claim to
stop it.

## Legend
- **Bucket C** = exhausts compute (CPU / main thread / connection slots). Stoppable on-box, free.
- **Bucket B** = exhausts bandwidth (uplink). Needs upstream capacity.
- Layers: **L0** edge/tunnel · **L1** XDP/eBPF kernel filter · **L2** Velocity plugin.

---

## L7 — application-layer (the common, server-killing stuff)

### 1. Bot join flood
- **How:** Thousands of fake clients open valid TCP connections, complete a real
  handshake, and enter the login/join flow. Network sees "legit players." The
  cost lands on the main thread and connection slots.
- **Bucket:** C
- **Defense:**
  - **L2 verification (primary):** new connections are held in a lightweight
    limbo state and must prove they're a real client — respond to keep-alives,
    obey gravity/movement physics, answer a transaction/position challenge. Bots
    that don't implement the full client fail instantly and never reach backend.
  - **L1:** in-kernel connection tracking + per-IP SYN/connection rate limits
    cap how fast one source can even open connections.
  - **Feedback:** IPs that fail verification repeatedly get pushed to the L1
    blocklist → dropped at the NIC thereafter.

### 2. Status / MOTD ping flood
- **How:** Rapid server-list-ping requests; each triggers a status response.
- **Bucket:** C
- **Defense:**
  - **L2:** cache the MOTD/status response; rate-limit status handshakes per IP;
    never allocate full connection handling for a status ping.
  - **L1:** drop status packets exceeding a per-IP rate at kernel level.

### 3. Protocol-exploit / crash packets
- **How:** Malformed packets, bad VarInts, oversized/compressed payloads
  designed to crash Netty or spike CPU (decompression bombs, illegal states).
- **Bucket:** C
- **Defense:**
  - **L1:** validate VarInts and packet structure in the eBPF program; drop
    protocol violations before the kernel/Netty touches them.
  - **L2:** packet-size and decompression limits *before* decode; reject
    out-of-order protocol states (login before handshake, etc.).

### 4. Login/auth abuse & join-leave spam
- **How:** Rapid connect→disconnect churn, or repeated login attempts, to thrash
  the pipeline without ever "playing."
- **Bucket:** C
- **Defense:**
  - **L2:** per-IP login rate limiting; detect connect/disconnect churn; temp-ban
    offenders and forward to L1 blocklist.

---

## L3/L4 — network-layer (up to your line rate = free to stop)

### 5. SYN flood on the game port
- **How:** Flood of TCP SYNs that never complete, exhausting the connection table.
- **Bucket:** C (pps) until it exceeds bandwidth, then B
- **Defense:**
  - **L1:** SYN rate-limiting (default 10 SYN / 3s / IP) + `XDP_DROP`; SYN
    cookies. Kernel handles millions of pps.

### 6. Garbage / invalid-protocol UDP/TCP flood on the game port
- **How:** Random bytes or non-MC protocol aimed at the port to burn pps.
- **Bucket:** C until bandwidth, then B
- **Defense:**
  - **L1:** anything that isn't a valid MC (Java TCP) / RakNet (Bedrock UDP)
    packet is dropped in the driver. This is the biggest single CPU-saver.

### 7. Amplification / reflection (DNS/NTP/memcached → your port)
- **How:** Spoofed requests to third parties that reflect amplified replies at you.
- **Bucket:** B (but often identifiable by source port)
- **Defense:**
  - **L1:** drop known amplification source ports at the NIC.
  - **L0:** if volume exceeds uplink, the anycast/edge upstream absorbs it.

---

## Bucket B — true volumetric (the only "not fully free on a single box" case)

### 8. Uplink saturation
- **How:** Raw flood larger than your pipe (e.g. 100 Gbps at a 1 Gbps port).
  Congestion happens *upstream* of your NIC; on-box filtering is irrelevant
  because the packets never all arrive intact anyway.
- **Bucket:** B
- **Defense (free options, in order of practicality):**
  1. **L0 anycast upstream (playit.gg free):** traffic is distributed across many
     datacenters; no single IP eats the whole flood; origin hidden.
  2. **L0 free-tier scrubber (TCPShield free):** rides a large L4 network.
  3. **Big-backbone free VM (Oracle):** larger baseline capacity than a home
     line; survives more before saturating (not Tbps).
  - **Honest ceiling:** a determined, very large volumetric attack against a
     *single self-hosted edge IP* still requires paid Tbps scrubbing. We say so.

---

## Non-goals / out of scope (state these too)
- Application logic exploits inside gameplay plugins (that's the server owner's code).
- Account/auth compromise, credential attacks (use online-mode + 2FA on panels).
- Guaranteeing uptime against paid, sustained, multi-hundred-Gbps campaigns on a
  free single-edge deployment.

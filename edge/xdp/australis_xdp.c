//go:build ignore

// Australis XDP/eBPF filter — Layer 1 (L3/L4 line-rate drop).
//
// Runs in the NIC driver before the kernel allocates an skb, so junk is dropped
// for a handful of CPU cycles. Two jobs:
//   1. Drop packets from IPs the plugin has convicted (a blocklist map the
//      australis-agent writes — this is the feedback loop at the NIC instead of
//      nftables), with per-entry expiry so bans lapse on their own.
//   2. Per-source SYN rate-limit on the game port (SYN-flood fallback), far
//      cheaper and more precise than the nftables version.
// Everything else passes. IPv4/TCP only in v1 (IPv6/Bedrock are follow-ups).
//
// Compiled to a portable BPF object by bpf2go (see gen.go); no runtime libbpf.
#include <linux/bpf.h>
#include <linux/if_ether.h>
#include <linux/ip.h>
#include <linux/in.h>
#include <linux/tcp.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_endian.h>

#define ETH_P_IP_BE 0x0008 /* htons(0x0800) */

/* Stats indices (array map). */
enum {
    ST_PASSED = 0,
    ST_DROP_BLOCKLIST = 1,
    ST_DROP_SYNFLOOD = 2,
    ST_DROP_MALFORMED = 3,
    ST__MAX = 4,
};

/* One blocklist entry: absolute expiry in ktime ns (0 = never). */
struct block_entry {
    __u64 expiry_ns;
};

/* Per-source SYN accounting for the rate-limit window. */
struct syn_state {
    __u64 window_start_ns;
    __u64 count;
};

/* Runtime config, set once by the loader. */
struct config {
    __u16 game_port;      /* host byte order */
    __u16 _pad;
    __u32 syn_per_window; /* max new SYNs per window per IP */
    __u64 window_ns;      /* window length */
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __type(key, __u32);            /* ipv4 saddr, network byte order */
    __type(value, struct block_entry);
    __uint(max_entries, 1048576);  /* up to ~1M convicted IPs */
} australis_blocklist SEC(".maps");   /* pinned explicitly by the loader */

struct {
    __uint(type, BPF_MAP_TYPE_LRU_HASH);
    __type(key, __u32);            /* ipv4 saddr */
    __type(value, struct syn_state);
    __uint(max_entries, 262144);
} australis_syn SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __type(key, __u32);
    __type(value, __u64);
    __uint(max_entries, ST__MAX);
} australis_stats SEC(".maps");

struct {
    __uint(type, BPF_MAP_TYPE_ARRAY);
    __type(key, __u32);
    __type(value, struct config);
    __uint(max_entries, 1);
} australis_config SEC(".maps");

static __always_inline void bump(__u32 idx)
{
    __u64 *c = bpf_map_lookup_elem(&australis_stats, &idx);
    if (c)
        __sync_fetch_and_add(c, 1);
}

SEC("xdp")
int australis_filter(struct xdp_md *ctx)
{
    void *data = (void *)(long)ctx->data;
    void *data_end = (void *)(long)ctx->data_end;

    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > data_end)
        return XDP_PASS; /* runt frame — let the stack decide */
    if (eth->h_proto != ETH_P_IP_BE)
        return XDP_PASS; /* not IPv4 (v1 scope) */

    struct iphdr *ip = (void *)(eth + 1);
    if ((void *)(ip + 1) > data_end)
        return XDP_PASS;
    if (ip->ihl < 5)
        return XDP_PASS;

    __u32 saddr = ip->saddr;
    __u64 now = bpf_ktime_get_ns();

    /* 1) Convicted-IP blocklist (feedback loop). */
    struct block_entry *b = bpf_map_lookup_elem(&australis_blocklist, &saddr);
    if (b) {
        if (b->expiry_ns == 0 || b->expiry_ns > now) {
            bump(ST_DROP_BLOCKLIST);
            return XDP_DROP;
        }
    }

    if (ip->protocol != IPPROTO_TCP) {
        bump(ST_PASSED);
        return XDP_PASS;
    }

    /* Honour IPv4 options when locating the TCP header. */
    __u32 ihl_bytes = ip->ihl * 4;
    struct tcphdr *tcp = (void *)ip + ihl_bytes;
    if ((void *)(tcp + 1) > data_end) {
        bump(ST_DROP_MALFORMED);
        return XDP_DROP; /* truncated TCP header = junk */
    }

    __u32 key0 = 0;
    struct config *cfg = bpf_map_lookup_elem(&australis_config, &key0);
    if (!cfg || cfg->game_port == 0) {
        bump(ST_PASSED);
        return XDP_PASS; /* not configured yet — fail open */
    }

    int to_game = (tcp->dest == bpf_htons(cfg->game_port));

    /* 2) Malformed / crafted TCP aimed at the game port. These flag combinations
     *    and headers are never produced by a real Minecraft client's stack; they
     *    are scan/flood/spoof junk, so we drop them at the NIC. XDP is stateless,
     *    so this covers packet-level malformation only — stream-deep MC/VarInt
     *    validation stays in L2 (the proxy), where TCP reassembly exists. */
    if (to_game) {
        __u8 malformed =
            (tcp->doff < 5) ||                                    /* impossible data offset */
            (!tcp->syn && !tcp->ack && !tcp->rst && !tcp->fin) || /* NULL scan */
            (tcp->syn && tcp->fin) ||                             /* SYN+FIN */
            (tcp->syn && tcp->rst) ||                             /* SYN+RST */
            (tcp->fin && tcp->psh && tcp->urg && !tcp->ack);      /* XMAS scan */
        if (malformed) {
            bump(ST_DROP_MALFORMED);
            return XDP_DROP;
        }
        /* A brand-new connection whose source port is privileged (<1024) is
         * spoofed or hand-crafted — real clients always use ephemeral ports.
         * This also swats classic reflection source ports (53, 123, 389, ...). */
        if (tcp->syn && !tcp->ack && bpf_ntohs(tcp->source) < 1024) {
            bump(ST_DROP_MALFORMED);
            return XDP_DROP;
        }
    }

    /* 3) Per-source SYN rate-limit on the game port only. */
    if (to_game && tcp->syn && !tcp->ack) {
        struct syn_state *st = bpf_map_lookup_elem(&australis_syn, &saddr);
        if (!st) {
            struct syn_state init = { .window_start_ns = now, .count = 1 };
            bpf_map_update_elem(&australis_syn, &saddr, &init, BPF_ANY);
        } else {
            if (now - st->window_start_ns > cfg->window_ns) {
                st->window_start_ns = now;
                st->count = 1;
            } else {
                st->count++;
                if (st->count > cfg->syn_per_window) {
                    bump(ST_DROP_SYNFLOOD);
                    return XDP_DROP;
                }
            }
        }
    }

    bump(ST_PASSED);
    return XDP_PASS;
}

char _license[] SEC("license") = "GPL";

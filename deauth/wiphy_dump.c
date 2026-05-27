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
    struct nlmsghdr *gl = (struct nlmsghdr*)(hdr + 1);
    struct nlattr *attr = (struct nlattr*)(gl + 1);
    int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(struct genlmsghdr);
    while (remaining >= (int)sizeof(struct nlattr)) {
        if (attr->nla_type == CTRL_ATTR_FAMILY_ID && attr->nla_len >= sizeof(struct nlattr)+4)
            return *(int*)((char*)attr + sizeof(struct nlattr));
        int alen = ((attr->nla_len + 3) & ~3);
        remaining -= alen;
        attr = (struct nlattr*)((char*)attr + alen);
    }
    return -1;
}

static int send_nl_cmd(int sock, int cmd, int flags, int ifidx, int wiphy) {
    unsigned char buf[1024];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    hdr->nlmsg_seq = 2;
    hdr->nlmsg_pid = getpid();
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = cmd;
    unsigned char *ptr = (unsigned char*)(genl + 1);
    if (ifidx >= 0) {
        struct nlattr *a = (struct nlattr*)ptr;
        a->nla_type = NL80211_ATTR_IFINDEX;
        a->nla_len = sizeof(*a) + 4;
        *(int*)((char*)a + sizeof(*a)) = ifidx;
        ptr += a->nla_len;
    }
    if (wiphy >= 0) {
        struct nlattr *a = (struct nlattr*)ptr;
        a->nla_type = NL80211_ATTR_WIPHY;
        a->nla_len = sizeof(*a) + 4;
        *(int*)((char*)a + sizeof(*a)) = wiphy;
        ptr += a->nla_len;
    }
    hdr->nlmsg_len = ptr - buf;
    return send(sock, buf, hdr->nlmsg_len, 0);
}

int main() {
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK, .nl_pid = getpid()};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    
    nl80211_family = get_nl80211_family(sock);
    printf("nl80211 family: %d\n", nl80211_family);
    
    int ifidx = if_nametoindex("wlan0");
    int wiphy = -1;
    
    // First get wiphy index from interface
    send_nl_cmd(sock, NL80211_CMD_GET_INTERFACE, 0, ifidx, -1);
    unsigned char resp[16384];
    int total = 0;
    while (1) {
        struct pollfd pfd = {.fd = sock, .events = POLLIN};
        int pr = poll(&pfd, 1, 3000);
        if (pr <= 0) break;
        int len = recv(sock, resp + total, sizeof(resp) - total, 0);
        if (len <= 0) break;
        struct nlmsghdr *hdr = (struct nlmsghdr*)(resp + total);
        if (hdr->nlmsg_type == NLMSG_DONE) break;
        if (hdr->nlmsg_type == NLMSG_ERROR) { printf("GET_INTERFACE error\n"); break; }
        
        // Parse for NL80211_ATTR_WIPHY
        struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
        int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
        struct nlattr *attr = (struct nlattr*)(genl + 1);
        while (remaining >= (int)sizeof(struct nlattr)) {
            if (attr->nla_type == NL80211_ATTR_WIPHY) {
                wiphy = *(int*)((char*)attr + sizeof(struct nlattr));
                printf("wiphy: %d\n", wiphy);
            }
            // Also dump all attrs
            printf("  iface attr %d len %d", attr->nla_type, attr->nla_len);
            if (attr->nla_type == NL80211_ATTR_IFTYPE)
                printf(" = %d", *(int*)((char*)attr + sizeof(struct nlattr)));
            printf("\n");
            int alen = ((attr->nla_len + 3) & ~3);
            remaining -= alen;
            attr = (struct nlattr*)((char*)attr + alen);
        }
        total += len;
    }
    
    if (wiphy < 0) { printf("Could not get wiphy\n"); close(sock); return 1; }
    
    // Now query full wiphy info
    printf("\n=== WIPHY %d FULL INFO ===\n", wiphy);
    send_nl_cmd(sock, NL80211_CMD_GET_WIPHY, 0, -1, wiphy);
    
    total = 0;
    int msg_count = 0;
    while (msg_count < 5) {
        struct pollfd pfd = {.fd = sock, .events = POLLIN};
        int pr = poll(&pfd, 1, 3000);
        if (pr <= 0) { printf("poll timeout/done (pr=%d)\n", pr); break; }
        int len = recv(sock, resp + total, sizeof(resp) - total, 0);
        if (len <= 0) break;
        struct nlmsghdr *hdr = (struct nlmsghdr*)(resp + total);
        if (hdr->nlmsg_type == NLMSG_DONE) break;
        if (hdr->nlmsg_type == NLMSG_ERROR) { printf("GET_WIPHY error\n"); break; }
        
        struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
        int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
        struct nlattr *attr = (struct nlattr*)(genl + 1);
        
        printf("\n--- Message %d (len=%d) ---\n", ++msg_count, hdr->nlmsg_len);
        
        while (remaining >= (int)sizeof(struct nlattr)) {
            int type = attr->nla_type;
            int alen = ((attr->nla_len + 3) & ~3);
            int payload_len = attr->nla_len - sizeof(struct nlattr);
            
            // Key attributes we want
            if (type == NL80211_ATTR_SUPPORTED_COMMANDS) {
                printf("  SUPPORTED_COMMANDS (%d bytes):", payload_len);
                for (int i = 0; i < payload_len && i < 200; i++)
                    printf(" %02x", ((unsigned char*)(attr+1))[i]);
                printf("\n");
            } else if (type == NL80211_ATTR_SUPPORTED_IFTYPES) {
                printf("  SUPPORTED_IFTYPES (%d bytes)\n", payload_len);
            } else if (type == NL80211_ATTR_FRAME_TYPE) {
                printf("  FRAME_TYPE (%d bytes)\n", payload_len);
            } else if (type == NL80211_ATTR_EXT_FEATURES) {
                printf("  EXT_FEATURES (%d bytes):", payload_len);
                for (int i = 0; i < payload_len && i < 100; i++)
                    printf(" %02x", ((unsigned char*)(attr+1))[i]);
                printf("\n");
            } else if (type == NL80211_ATTR_TX_FRAME_TYPES) {
                printf("  TX_FRAME_TYPES (nested) — THIS IS THE KEY!\n");
                // Dump nested
                int nr = payload_len;
                struct nlattr *na = (struct nlattr*)(attr + 1);
                while (nr >= (int)sizeof(struct nlattr)) {
                    int nat = na->nla_type & 0xFF;
                    int nal = ((na->nla_len + 3) & ~3);
                    printf("    iftype=%d, frame data: %d bytes\n", nat, na->nla_len - sizeof(struct nlattr));
                    nr -= nal;
                    na = (struct nlattr*)((char*)na + nal);
                }
            } else {
                if (payload_len > 0 && payload_len <= 16) {
                    printf("  attr %d (%d bytes):", type, payload_len);
                    for (int i = 0; i < payload_len; i++)
                        printf(" %02x", ((unsigned char*)(attr+1))[i]);
                    printf("\n");
                } else {
                    printf("  attr %d (%d bytes)\n", type, payload_len);
                }
            }
            
            remaining -= alen;
            attr = (struct nlattr*)((char*)attr + alen);
        }
        total += len;
    }
    
    close(sock);
    return 0;
}

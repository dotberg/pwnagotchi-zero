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

int main() {
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK, .nl_pid = getpid()};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    
    nl80211_family = get_nl80211_family(sock);
    printf("nl80211 family: %d\n", nl80211_family);
    
    int ifidx = if_nametoindex("wlan0");
    
    // Method 1: GET_INTERFACE to get wiphy
    unsigned char buf[512];
    memset(buf, 0, sizeof(buf));
    struct nlmsghdr *hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST;
    hdr->nlmsg_seq = 2;
    hdr->nlmsg_pid = getpid();
    struct genlmsghdr *genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = NL80211_CMD_GET_INTERFACE;
    unsigned char *ptr = (unsigned char*)(genl + 1);
    struct nlattr *a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_IFINDEX;
    a->nla_len = sizeof(*a) + 4;
    *(int*)((char*)a + sizeof(*a)) = ifidx;
    ptr += a->nla_len;
    hdr->nlmsg_len = ptr - buf;
    send(sock, buf, hdr->nlmsg_len, 0);
    
    unsigned char resp[4096];
    int len = recv(sock, resp, sizeof(resp), 0);
    printf("GET_INTERFACE recv: %d bytes\n", len);
    
    hdr = (struct nlmsghdr*)resp;
    if (hdr->nlmsg_type == NLMSG_ERROR) {
        printf("  error: %d\n", ((struct nlmsgerr*)(hdr+1))->error);
    }
    
    // Parse wiphy from response
    genl = (struct genlmsghdr*)(hdr + 1);
    int remaining = hdr->nlmsg_len - sizeof(*hdr) - sizeof(*genl);
    struct nlattr *attr = (struct nlattr*)(genl + 1);
    int wiphy = -1;
    
    while (remaining >= (int)sizeof(struct nlattr)) {
        int type = attr->nla_type;
        int alen = ((attr->nla_len + 3) & ~3);
        if (type == NL80211_ATTR_WIPHY) {
            wiphy = *(int*)((char*)attr + sizeof(struct nlattr));
        }
        printf("  attr %d len %d", type, attr->nla_len);
        if (type == NL80211_ATTR_IFTYPE)
            printf(" = %d", *(int*)((char*)attr + sizeof(struct nlattr)));
        printf("\n");
        remaining -= alen;
        attr = (struct nlattr*)((char*)attr + alen);
    }
    
    printf("wiphy: %d\n", wiphy);
    
    // Now GET_WIPHY
    memset(buf, 0, sizeof(buf));
    hdr = (struct nlmsghdr*)buf;
    hdr->nlmsg_type = nl80211_family;
    hdr->nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
    hdr->nlmsg_seq = 3;
    hdr->nlmsg_pid = getpid();
    genl = (struct genlmsghdr*)(hdr + 1);
    genl->cmd = NL80211_CMD_GET_WIPHY;
    ptr = (unsigned char*)(genl + 1);
    a = (struct nlattr*)ptr;
    a->nla_type = NL80211_ATTR_WIPHY;
    a->nla_len = sizeof(*a) + 4;
    *(int*)((char*)a + sizeof(*a)) = wiphy;
    ptr += a->nla_len;
    hdr->nlmsg_len = ptr - buf;
    send(sock, buf, hdr->nlmsg_len, 0);
    
    printf("\n=== GET_WIPHY response (raw hex) ===\n");
    int total = 0;
    unsigned char bigbuf[65536];
    while (total < 60000) {
        struct pollfd pfd = {.fd = sock, .events = POLLIN};
        int pr = poll(&pfd, 1, 2000);
        if (pr <= 0) { printf("poll done (pr=%d)\n", pr); break; }
        len = recv(sock, bigbuf + total, sizeof(bigbuf) - total, 0);
        if (len <= 0) { printf("recv done (len=%d)\n", len); break; }
        printf("recv'd %d bytes at offset %d\n", len, total);
        
        // Check if this is NLMSG_DONE
        struct nlmsghdr *mhdr = (struct nlmsghdr*)(bigbuf + total);
        printf("  type=%d flags=0x%x seq=%d pid=%d len=%d\n",
               mhdr->nlmsg_type, mhdr->nlmsg_flags, mhdr->nlmsg_seq, mhdr->nlmsg_pid, mhdr->nlmsg_len);
        
        if (mhdr->nlmsg_type == NLMSG_DONE) { printf("  -> DONE!\n"); total += len; break; }
        if (mhdr->nlmsg_type == NLMSG_ERROR) { 
            printf("  -> ERROR: %d\n", ((struct nlmsgerr*)(mhdr+1))->error);
            total += len; 
            break; 
        }
        
        total += len;
    }
    
    printf("\nTotal data: %d bytes\n", total);
    
    // Now parse the interesting parts
    // Walk through all attributes
    int pos = 0;
    int msg_idx = 0;
    while (pos < total - 16 && msg_idx < 3) {
        struct nlmsghdr *mhdr = (struct nlmsghdr*)(bigbuf + pos);
        if (mhdr->nlmsg_type == NLMSG_DONE || mhdr->nlmsg_type == NLMSG_ERROR) break;
        if (mhdr->nlmsg_len < 20) break;
        
        msg_idx++;
        printf("\n=== Message %d ===\n", msg_idx);
        
        struct genlmsghdr *mgenl = (struct genlmsghdr*)(mhdr + 1);
        int mrem = mhdr->nlmsg_len - sizeof(*mhdr) - sizeof(*mgenl);
        struct nlattr *mattr = (struct nlattr*)(mgenl + 1);
        
        while (mrem >= (int)sizeof(struct nlattr)) {
            int mtype = mattr->nla_type;
            int malen = ((mattr->nla_len + 3) & ~3);
            int mpay = mattr->nla_len - sizeof(struct nlattr);
            
            if (mtype == NL80211_ATTR_SUPPORTED_COMMANDS) {
                printf("  SUPPORTED_COMMANDS (%d bytes):", mpay);
                for (int i = 0; i < mpay; i++)
                    printf(" %02x", ((unsigned char*)(mattr+1))[i]);
                printf("\n");
                // Decode: each command that's supported has its bit set at its index
                // CMD_FRAME = 22 (0x16)
                int byte_idx = 22 / 8;
                int bit_idx = 22 % 8;
                if (byte_idx < mpay)
                    printf("  -> CMD_FRAME (22): %s\n", 
                           (((unsigned char*)(mattr+1))[byte_idx] & (1 << bit_idx)) ? "SUPPORTED" : "NOT SUPPORTED");
            } else if (mtype == NL80211_ATTR_EXT_FEATURES) {
                // Extended features bitmap
                printf("  EXT_FEATURES (%d bytes):", mpay);
                for (int i = 0; i < mpay && i < 32; i++)
                    printf(" %02x", ((unsigned char*)(mattr+1))[i]);
                printf("\n");
            } else if (mtype == NL80211_ATTR_TX_FRAME_TYPES) {
                printf("  TX_FRAME_TYPES (%d bytes) - NESTED:\n", mpay);
                int nr = mpay;
                struct nlattr *na = (struct nlattr*)(mattr + 1);
                while (nr >= (int)sizeof(struct nlattr) && na->nla_len >= sizeof(struct nlattr)) {
                    int nat = na->nla_type & 0xFF;
                    int nal = ((na->nla_len + 3) & ~3);
                    int ndat = na->nla_len - sizeof(struct nlattr);
                    printf("    iftype=%d frame_types:", nat);
                    for (int i = 0; i < ndat && i < 32; i++)
                        printf(" %02x", ((unsigned char*)(na+1))[i]);
                    printf("\n");
                    nr -= nal;
                    na = (struct nlattr*)((char*)na + nal);
                }
            }
            
            mrem -= malen;
            mattr = (struct nlattr*)((char*)mattr + malen);
        }
        
        pos += mhdr->nlmsg_len;
    }
    
    close(sock);
    return 0;
}

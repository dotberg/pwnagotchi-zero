#!/usr/bin/env python3
"""Dump nl80211 wiphy capabilities via pyroute2 or direct netlink"""
import socket, struct, os, select

# Constants
AF_NETLINK = 16
NETLINK_GENERIC = 3
NLM_F_REQUEST = 1
NLM_F_DUMP = 0x300
NLMSG_ERROR = 2
NLMSG_DONE = 3
GENL_ID_CTRL = 0x10
CTRL_CMD_GETFAMILY = 3
CTRL_ATTR_FAMILY_NAME = 2
CTRL_ATTR_FAMILY_ID = 1

NL80211_CMD_GET_WIPHY = 1
NL80211_CMD_GET_INTERFACE = 5
NL80211_ATTR_WIPHY = 1
NL80211_ATTR_IFINDEX = 3
NL80211_ATTR_SUPPORTED_COMMANDS = 42
NL80211_ATTR_TX_FRAME_TYPES = 60
NL80211_ATTR_EXT_FEATURES = 181

def nla_align(l):
    return (l + 3) & ~3

def put_nlattr(typ, data=b''):
    hdr = struct.pack('<HH', len(data) + 4, typ)
    pad = b'\x00' * (nla_align(len(data) + 4) - (len(data) + 4))
    return hdr + data + pad

def get_nl80211_family(sock):
    pid = os.getpid()
    name = b'nl80211\x00'
    attr = put_nlattr(CTRL_ATTR_FAMILY_NAME, name)
    genlhdr = struct.pack('<BB', 0, CTRL_CMD_GETFAMILY)
    nlhdr = struct.pack('<IHHII', len(genlhdr) + len(attr) + 16, GENL_ID_CTRL, NLM_F_REQUEST, 1, pid)
    sock.send(nlhdr + genlhdr + attr)
    data = sock.recv(4096)
    # Parse response
    nlhdr = data[:16]
    nl_len, nl_type, nl_flags, nl_seq, nl_pid = struct.unpack('<IHHII', nlhdr)
    if nl_type == NLMSG_ERROR:
        return -1
    # Skip genlhdr (2 bytes)
    pos = 16 + 2
    while pos < nl_len:
        alen, atyp = struct.unpack('<HH', data[pos:pos+4])
        if atyp == CTRL_ATTR_FAMILY_ID:
            return struct.unpack('<H', data[pos+4:pos+6])[0]
        pos += nla_align(alen)
    return -1

def parse_nested_attrs(data, depth=0):
    """Parse nested nl80211 attributes"""
    indent = '  ' * depth
    pos = 0
    while pos < len(data) - 4:
        alen, atyp = struct.unpack('<HH', data[pos:pos+4])
        if alen < 4 or pos + alen > len(data):
            break
        payload = data[pos+4:pos+alen]
        # Try to detect nested attrs vs raw data
        if len(payload) <= 16:
            val_str = ' '.join(f'{b:02x}' for b in payload[:16])
        else:
            val_str = f'{len(payload)} bytes'
        
        # Special handling for known attrs
        if atyp == NL80211_ATTR_SUPPORTED_COMMANDS:
            cmds = []
            for i in range(0, len(payload), 4):
                if i+4 <= len(payload):
                    cmd = struct.unpack('<I', payload[i:i+4])[0]
                    cmds.append(cmd)
            print(f'{indent}SUPPORTED_COMMANDS: {cmds[:30]}...' if len(cmds) > 30 else f'{indent}SUPPORTED_COMMANDS: {cmds}')
        elif atyp == NL80211_ATTR_EXT_FEATURES:
            # This is a bitmap
            bits = []
            byte_idx = 0
            for b in payload:
                for bit in range(8):
                    if b & (1 << bit):
                        bits.append(byte_idx * 8 + bit)
                byte_idx += 1
            print(f'{indent}EXT_FEATURES (bitmap, set bits): {bits[:40]}...' if len(bits) > 40 else f'{indent}EXT_FEATURES (bitmap, set bits): {bits}')
        elif atyp == NL80211_ATTR_TX_FRAME_TYPES:
            # Nested: iftype -> frame types
            print(f'{indent}TX_FRAME_TYPES:')
            parse_nested_attrs(payload, depth + 1)
        else:
            print(f'{indent}attr {atyp}: {val_str}')
        
        pos += nla_align(alen)

def main():
    sock = socket.socket(AF_NETLINK, socket.SOCK_RAW, NETLINK_GENERIC)
    sock.bind((0, os.getpid()))
    
    family = get_nl80211_family(sock)
    print(f'nl80211 family: {family}')
    
    # Get wiphy index from wlan0
    ifidx = socket.if_nametoindex('wlan0')
    print(f'wlan0 ifindex: {ifidx}')
    
    attr = put_nlattr(NL80211_ATTR_IFINDEX, struct.pack('<I', ifidx))
    genlhdr = struct.pack('<BB', 0, NL80211_CMD_GET_INTERFACE)
    pid = os.getpid()
    nlhdr = struct.pack('<IHHII', len(genlhdr) + len(attr) + 16, family, NLM_F_REQUEST, 2, pid)
    sock.send(nlhdr + genlhdr + attr)
    
    data = sock.recv(4096)
    pos = 16 + 2  # skip nlmsghdr + genlmsghdr
    wiphy = -1
    while pos < len(data) - 4:
        alen, atyp = struct.unpack('<HH', data[pos:pos+4])
        if atyp == NL80211_ATTR_WIPHY:
            wiphy = struct.unpack('<I', data[pos+4:pos+8])[0]
            print(f'wiphy: {wiphy}')
        pos += nla_align(alen)
    
    if wiphy < 0:
        print("Could not get wiphy")
        return
    
    # Now GET_WIPHY
    print(f'\n=== WIPHY {wiphy} ===')
    attr = put_nlattr(NL80211_ATTR_WIPHY, struct.pack('<I', wiphy))
    genlhdr = struct.pack('<BB', 0, NL80211_CMD_GET_WIPHY)
    nlhdr = struct.pack('<IHHII', len(genlhdr) + len(attr) + 16, family, NLM_F_REQUEST | NLM_F_DUMP, 3, pid)
    sock.send(nlhdr + genlhdr + attr)
    
    # Read messages
    all_data = b''
    sock.settimeout(2.0)
    while True:
        try:
            chunk = sock.recv(16384)
            all_data += chunk
        except socket.timeout:
            break
    
    # Parse each message
    pos = 0
    msg_count = 0
    while pos < len(all_data) - 16 and msg_count < 10:
        nlhdr = all_data[pos:pos+16]
        nl_len, nl_type, nl_flags, nl_seq, nl_pid = struct.unpack('<IHHII', nlhdr)
        if nl_type == NLMSG_DONE or nl_type == NLMSG_ERROR:
            break
        if nl_len < 16:
            break
        
        msg_count += 1
        print(f'\n--- Message {msg_count} ---')
        msg_data = all_data[pos+16+2:nl_len]  # skip nlhdr + genlhdr
        parse_nested_attrs(msg_data)
        
        pos += nl_len

if __name__ == '__main__':
    main()

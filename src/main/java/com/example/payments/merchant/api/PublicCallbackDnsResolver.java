package com.example.payments.merchant.api;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Runs inside the connection operator, so the validated addresses are the addresses actually connected. */
final class PublicCallbackDnsResolver implements DnsResolver {
    interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Lookup lookup;

    PublicCallbackDnsResolver() {
        this(InetAddress::getAllByName);
    }

    PublicCallbackDnsResolver(Lookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = lookup.resolve(host);
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("Empty callback DNS answer");
        // Reject the whole answer if any candidate is private; never allow fallback to an unsafe IP.
        for (InetAddress address : addresses) requirePublic(address);
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // Do not introduce a second, unvalidated DNS lookup or change the TLS/Host name.
        return host;
    }

    static void requirePublic(InetAddress address) throws UnknownHostException {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw new UnknownHostException("Callback address is not public");
        }
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            if (a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 192 && b == 88 && c == 99) || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)
                    // Azure platform virtual IP can expose metadata even though it is globally numbered.
                    || address.getHostAddress().equals("168.63.129.16")) {
                throw new UnknownHostException("Callback address is not public");
            }
        } else if (bytes.length == 16) {
            // Global unicast only; excludes ULA, scoped addresses and NAT64/private-address embeddings.
            if ((a & 0xe0) != 0x20 || address.getHostAddress().contains("%")
                    || (a == 0x20 && b == 0x02) // 6to4 embeds an unchecked IPv4 destination.
                    || (a == 0x20 && b == 0x01 && (bytes[2] & 255) < 2) // special-purpose 2001::/23
                    || (a == 0x20 && b == 0x01 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                    || (a == 0x3f && b == 0xff && ((bytes[2] & 255) & 0xf0) == 0)) { // documentation 3fff::/20
                throw new UnknownHostException("Callback address is not public");
            }
        } else {
            throw new UnknownHostException("Unsupported callback address");
        }
    }
}

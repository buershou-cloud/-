package com.example.payments.merchant.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PublicCallbackDnsResolverTest {
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.1.2.3", "127.0.0.1", "127.1", "2130706433", "169.254.169.254",
            "100.100.100.200", "172.16.0.1", "192.168.1.1", "192.0.0.1", "192.0.2.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "168.63.129.16",
            "::", "::1", "::ffff:127.0.0.1", "fe80::1", "fd00:ec2::254", "ff02::1", "64:ff9b::7f00:1",
            "2001:db8::1", "2002:7f00:1::", "2001::1", "3fff::1"})
    void rejectsPrivateReservedAndMetadataAddresses(String address) throws Exception {
        InetAddress parsed = InetAddress.getByName(address);
        assertThatThrownBy(() -> PublicCallbackDnsResolver.requirePublic(parsed)).isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "162.4.172.110", "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void retainsPublicIpv4AndIpv6(String address) throws Exception {
        PublicCallbackDnsResolver.requirePublic(InetAddress.getByName(address));
    }

    @Test
    void checksEveryDnsCandidateAndRejectsRebindingOnNextConnection() throws Exception {
        var calls = new AtomicInteger();
        InetAddress publicAddress = InetAddress.getByName("8.8.8.8");
        InetAddress privateAddress = InetAddress.getByName("127.0.0.1");
        var resolver = new PublicCallbackDnsResolver(host -> calls.incrementAndGet() == 1
                ? new InetAddress[] {publicAddress} : new InetAddress[] {publicAddress, privateAddress});
        assertThat(resolver.resolve("merchant.example")).containsExactly(publicAddress);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> resolver.resolve("merchant.example")).isInstanceOf(UnknownHostException.class);
        assertThat(resolver.resolveCanonicalHostname("merchant.example")).isEqualTo("merchant.example");
        assertThat(calls).hasValue(2);
    }
}

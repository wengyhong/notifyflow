package com.wenghong.notifyflow.endpoint.application;

import java.net.InetAddress;
import java.net.Inet6Address;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookAddressPolicyTests {

    private final WebhookAddressPolicy policy = new WebhookAddressPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "::2",
            "::192.168.1.1",
            "64:ff9b::a00:1",
            "64:ff9b:1::1",
            "100::1",
            "100:0:0:1::1",
            "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "4000::",
            "5f00::1",
            "fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fe00::1",
            "2001::",
            "2001::1",
            "2001:1::1",
            "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
            "2002::",
            "2002:a00:1::1",
            "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void blocksUnsupportedAndSelectedSpecialPurposeIpv6(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2000::",
            "2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:200::",
            "2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "2003::",
            "3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void ipv6ScopeChecksPreserveUnblockedPrefixBoundaries(String literal) throws Exception {
        assertFalse(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @Test
    void blocksMappedIpv4EvenWhenExplicitlyRepresentedAsInet6Address() throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = 127;
        bytes[15] = 1;

        Inet6Address mapped = Inet6Address.getByAddress(null, bytes, -1);

        assertTrue(policy.isBlocked(mapped));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db8::",
            "2001:db8::1",
            "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff",
            "3fff::",
            "3fff::1",
            "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void blocksIpv6DocumentationRanges(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff",
            "2001:db9::",
            "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "3fff:1000::"
    })
    void documentationIpv6ChecksDoNotBlockAdjacentPrefixes(String literal) throws Exception {
        assertFalse(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "192.0.0.0", "192.0.0.255",
            "192.0.0.9", "192.0.0.10",
            "192.0.2.0", "192.0.2.255",
            "192.88.99.0", "192.88.99.255",
            "198.18.0.0", "198.19.255.255",
            "198.51.100.0", "198.51.100.255",
            "203.0.113.0", "203.0.113.255"
    })
    void blocksSelectedSpecialPurposeIpv4Ranges(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "191.255.255.255",
            "192.0.1.0", "192.0.1.255", "192.0.3.0",
            "192.88.98.255", "192.88.100.0",
            "198.17.255.255", "198.20.0.0",
            "198.51.99.255", "198.51.101.0",
            "203.0.112.255", "203.0.114.0"
    })
    void specialPurposeIpv4ChecksDoNotBlockAdjacentRanges(String literal) throws Exception {
        assertFalse(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0",
            "0.0.0.1",
            "0.255.255.255",
            "10.0.0.0",
            "10.255.255.255",
            "127.0.0.0",
            "127.0.0.1",
            "127.255.255.255",
            "169.254.0.0",
            "169.254.169.254",
            "169.254.255.255",
            "172.16.0.0",
            "172.31.255.255",
            "192.168.0.0",
            "192.168.255.255",
            "224.0.0.0",
            "239.255.255.255",
            "240.0.0.0",
            "255.255.255.254",
            "255.255.255.255"
    })
    void blocksLocalMulticastAndReservedIpv4(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "100.64.0.0",
            "100.64.0.1",
            "100.75.1.2",
            "100.127.255.255"
    })
    void blocksEntireSharedIpv4Range(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.0.0.1",
            "8.8.8.8",
            "9.255.255.255",
            "11.0.0.0",
            "100.63.255.255",
            "100.128.0.0",
            "101.64.0.1",
            "126.255.255.255",
            "128.0.0.0",
            "169.253.255.255",
            "169.255.0.0",
            "172.15.255.255",
            "172.32.0.0",
            "192.167.255.255",
            "192.169.0.0",
            "223.255.255.254"
    })
    void allowsPublicIpv4SamplesAndAddressesOutsideBlockedBoundaries(String literal) throws Exception {
        assertFalse(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::",
            "::1",
            "fe80::",
            "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fec0::",
            "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "ff00::",
            "ff02::1",
            "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fc00::",
            "fc00::1",
            "fcff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "fd00::",
            "fd12::1",
            "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void blocksLocalAndMulticastIpv6IncludingEntireUniqueLocalRange(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:4860:4860::8888",
            "2606:4700:4700::1111"
    })
    void allowsPublicIpv6Samples(String literal) throws Exception {
        assertFalse(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.1",
            "::ffff:169.254.169.254",
            "::ffff:100.64.0.1",
            "::ffff:192.168.1.1",
            "::ffff:240.0.0.1"
    })
    void blocksMappedIpv4LiteralsNormalizedByJava(String literal) throws Exception {
        assertTrue(policy.isBlocked(InetAddress.getByName(literal)), literal);
    }

    @Test
    void rejectsMissingAddress() {
        assertThrows(NullPointerException.class, () -> policy.isBlocked(null));
    }
}

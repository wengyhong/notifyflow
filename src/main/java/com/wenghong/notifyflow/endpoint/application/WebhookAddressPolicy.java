package com.wenghong.notifyflow.endpoint.application;

import java.net.InetAddress;
import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public class WebhookAddressPolicy {

    public boolean isBlocked(InetAddress address) {
        Objects.requireNonNull(address);

        return address.isAnyLocalAddress() ||
                address.isLoopbackAddress() ||
                address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() ||
                address.isMulticastAddress() ||
                isUniqueLocalIpv6(address) ||
                isSharedIpv4(address) ||
                isReservedIpv4(address) ||
                isSpecialPurposeIpv4(address) ||
                isDocumentationIpv6(address) ||
                isUnsupportedIpv6(address);
    }

    private boolean isUnsupportedIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length != 16) {
            return false;
        }

        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;

        // Limit IPv6 destinations to the current 2000::/3 unicast allocation.
        if ((first & 0xE0) != 0x20) {
            return true;
        }

        // Conservatively exclude all of 2001::/23, including its exceptions.
        boolean protocolAssignments = first == 0x20
                && second == 0x01
                && (third & 0xFE) == 0;

        // 2002::/16 is the 6to4 transition range.
        boolean sixToFour = first == 0x20
                && second == 0x02;

        return protocolAssignments || sixToFour;
    }

    private boolean isDocumentationIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length != 16) {
            return false;
        }

        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;
        int fourth = bytes[3] & 0xFF;

        boolean firstRange = first == 0x20
                && second == 0x01
                && third == 0x0D
                && fourth == 0xB8;

        boolean secondRange = first == 0x3F
                && second == 0xFF
                && (third & 0xF0) == 0;

        return firstRange || secondRange;
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();

        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private boolean isSharedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();

        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xC0) == 0x40;
    }

    private boolean isReservedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length != 4) {
            return false;
        }

        int first = bytes[0] & 0xFF;

        return first == 0 || first >= 240;
    }

    private boolean isSpecialPurposeIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length != 4) {
            return false;
        }

        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        int third = bytes[2] & 0xFF;

        if (first == 192) {
            return (second == 0 && (third == 0 || third == 2))
                    || (second == 88 && third == 99);
        }

        if (first == 198) {
            return second == 18
                    || second == 19
                    || (second == 51 && third == 100);
        }

        return first == 203 && second == 0 && third == 113;
    }
}

// Each condition asks whether the destination belongs to a category we want to
// block.
// Condition Meaning Examples
// isAnyLocalAddress() A wildcard address meaning “any local interface.” Useful
// when starting a server, unsuitable as a webhook destination. 0.0.0.0, ::
// isLoopbackAddress() Points back to the machine running NotifyFlow. It could
// reach services on that machine. 127.0.0.1, ::1
// isLinkLocalAddress() Intended for communication on the local network link.
// This category also contains commonly used cloud metadata addresses.
// 169.254.169.254, fe80::1
// isSiteLocalAddress() Addresses intended for internal networks. For IPv4, it
// covers the familiar private ranges. 10.0.0.5, 172.16.0.5, 192.168.1.10
// isMulticastAddress() Addresses a group of network participants rather than
// one ordinary webhook server. 224.0.0.1, ff02::1

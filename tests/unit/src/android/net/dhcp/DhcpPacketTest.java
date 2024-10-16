/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.dhcp;

import static android.net.dhcp.DhcpPacket.DHCP_BROADCAST_ADDRESS;
import static android.net.dhcp.DhcpPacket.DHCP_DNS_SERVER;
import static android.net.dhcp.DhcpPacket.DHCP_DOMAIN_NAME;
import static android.net.dhcp.DhcpPacket.DHCP_LEASE_TIME;
import static android.net.dhcp.DhcpPacket.DHCP_MESSAGE_TYPE_ACK;
import static android.net.dhcp.DhcpPacket.DHCP_MESSAGE_TYPE_OFFER;
import static android.net.dhcp.DhcpPacket.DHCP_MTU;
import static android.net.dhcp.DhcpPacket.DHCP_REBINDING_TIME;
import static android.net.dhcp.DhcpPacket.DHCP_RENEWAL_TIME;
import static android.net.dhcp.DhcpPacket.DHCP_ROUTER;
import static android.net.dhcp.DhcpPacket.DHCP_SUBNET_MASK;
import static android.net.dhcp.DhcpPacket.DHCP_VENDOR_INFO;
import static android.net.dhcp.DhcpPacket.ENCAP_BOOTP;
import static android.net.dhcp.DhcpPacket.ENCAP_L2;
import static android.net.dhcp.DhcpPacket.ENCAP_L3;
import static android.net.dhcp.DhcpPacket.INADDR_ANY;
import static android.net.dhcp.DhcpPacket.INFINITE_LEASE;
import static android.net.dhcp.DhcpPacket.ParseException;

import static com.android.net.module.util.Inet4AddressUtils.getBroadcastAddress;
import static com.android.net.module.util.Inet4AddressUtils.getPrefixMaskAsInet4Address;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.Nullable;
import android.net.DhcpResults;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.net.metrics.DhcpErrorEvent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DhcpPacketTest {

    private static final Inet4Address SERVER_ADDR = v4Address("192.0.2.1");
    private static final Inet4Address CLIENT_ADDR = v4Address("192.0.2.234");
    private static final int PREFIX_LENGTH = 22;
    private static final Inet4Address NETMASK = getPrefixMaskAsInet4Address(PREFIX_LENGTH);
    private static final Inet4Address BROADCAST_ADDR = getBroadcastAddress(
            SERVER_ADDR, PREFIX_LENGTH);
    private static final String HOSTNAME = "testhostname";
    private static final String CAPTIVE_PORTAL_API_URL = "https://example.com/capportapi";
    private static final short MTU = 1500;
    // Use our own empty address instead of IPV4_ADDR_ANY or INADDR_ANY to ensure that the code
    // doesn't use == instead of equals when comparing addresses.
    private static final Inet4Address ANY = v4Address("0.0.0.0");
    private static final byte[] TEST_EMPTY_OPTIONS_SKIP_LIST = new byte[0];
    private static final int TEST_IPV6_ONLY_WAIT_S = 1800; // 30 min

    private static final byte[] CLIENT_MAC = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05 };

    private static final Inet4Address v4Address(String addrString) throws IllegalArgumentException {
        return (Inet4Address) InetAddresses.parseNumericAddress(addrString);
    }

    @Before
    public void setUp() {
        DhcpPacket.sTestOverrideVendorId = "android-dhcp-???";
    }

    @After
    public void tearDown() {
        DhcpPacket.sTestOverrideVendorId = null;
    }

    class TestDhcpPacket extends DhcpPacket {
        private byte mType;
        // TODO: Make this a map of option numbers to bytes instead.
        private byte[] mDomainBytes, mVendorInfoBytes, mLeaseTimeBytes, mNetmaskBytes;

        public TestDhcpPacket(byte type, Inet4Address clientIp, Inet4Address yourIp) {
            super(0xdeadbeef, (short) 0, clientIp, yourIp, INADDR_ANY, INADDR_ANY,
                  CLIENT_MAC, true);
            mType = type;
        }

        public TestDhcpPacket(byte type) {
            this(type, INADDR_ANY, CLIENT_ADDR);
        }

        public TestDhcpPacket setDomainBytes(byte[] domainBytes) {
            mDomainBytes = domainBytes;
            return this;
        }

        public TestDhcpPacket setVendorInfoBytes(byte[] vendorInfoBytes) {
            mVendorInfoBytes = vendorInfoBytes;
            return this;
        }

        public TestDhcpPacket setLeaseTimeBytes(byte[] leaseTimeBytes) {
            mLeaseTimeBytes = leaseTimeBytes;
            return this;
        }

        public TestDhcpPacket setNetmaskBytes(byte[] netmaskBytes) {
            mNetmaskBytes = netmaskBytes;
            return this;
        }

        public ByteBuffer buildPacket(int encap, short unusedDestUdp, short unusedSrcUdp) {
            ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);
            fillInPacket(encap, CLIENT_ADDR, SERVER_ADDR,
                         DHCP_CLIENT, DHCP_SERVER, result, DHCP_BOOTREPLY, false);
            return result;
        }

        public void finishPacket(ByteBuffer buffer) {
            addTlv(buffer, DHCP_MESSAGE_TYPE, mType);
            if (mDomainBytes != null) {
                addTlv(buffer, DHCP_DOMAIN_NAME, mDomainBytes);
            }
            if (mVendorInfoBytes != null) {
                addTlv(buffer, DHCP_VENDOR_INFO, mVendorInfoBytes);
            }
            if (mLeaseTimeBytes != null) {
                addTlv(buffer, DHCP_LEASE_TIME, mLeaseTimeBytes);
            }
            if (mNetmaskBytes != null) {
                addTlv(buffer, DHCP_SUBNET_MASK, mNetmaskBytes);
            }
            addTlvEnd(buffer);
        }

        // Convenience method.
        public ByteBuffer build() {
            // ENCAP_BOOTP packets don't contain ports, so just pass in 0.
            ByteBuffer pkt = buildPacket(ENCAP_BOOTP, (short) 0, (short) 0);
            pkt.flip();
            return pkt;
        }
    }

    private void assertDomainAndVendorInfoParses(
            String expectedDomain, byte[] domainBytes,
            String expectedVendorInfo, byte[] vendorInfoBytes) throws Exception {
        ByteBuffer packet = new TestDhcpPacket(DHCP_MESSAGE_TYPE_OFFER)
                .setDomainBytes(domainBytes)
                .setVendorInfoBytes(vendorInfoBytes)
                .build();
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertEquals(expectedDomain, offerPacket.mDomainName);
        assertEquals(expectedVendorInfo, offerPacket.mVendorInfo);
    }

    @Test
    public void testDomainName() throws Exception {
        byte[] nullByte = new byte[] { 0x00 };
        byte[] twoNullBytes = new byte[] { 0x00, 0x00 };
        byte[] nonNullDomain = new byte[] {
            (byte) 'g', (byte) 'o', (byte) 'o', (byte) '.', (byte) 'g', (byte) 'l'
        };
        byte[] trailingNullDomain = new byte[] {
            (byte) 'g', (byte) 'o', (byte) 'o', (byte) '.', (byte) 'g', (byte) 'l', 0x00
        };
        byte[] embeddedNullsDomain = new byte[] {
            (byte) 'g', (byte) 'o', (byte) 'o', 0x00, 0x00, (byte) 'g', (byte) 'l'
        };
        byte[] metered = "ANDROID_METERED".getBytes("US-ASCII");

        byte[] meteredEmbeddedNull = metered.clone();
        meteredEmbeddedNull[7] = (char) 0;

        byte[] meteredTrailingNull = metered.clone();
        meteredTrailingNull[meteredTrailingNull.length - 1] = (char) 0;

        assertDomainAndVendorInfoParses("", nullByte, "\u0000", nullByte);
        assertDomainAndVendorInfoParses("", twoNullBytes, "\u0000\u0000", twoNullBytes);
        assertDomainAndVendorInfoParses("goo.gl", nonNullDomain, "ANDROID_METERED", metered);
        assertDomainAndVendorInfoParses("goo", embeddedNullsDomain,
                                        "ANDROID\u0000METERED", meteredEmbeddedNull);
        assertDomainAndVendorInfoParses("goo.gl", trailingNullDomain,
                                        "ANDROID_METERE\u0000", meteredTrailingNull);
    }

    private void assertLeaseTimeParses(boolean expectValid, Integer rawLeaseTime,
            long leaseTimeMillis, byte[] leaseTimeBytes) throws Exception {
        TestDhcpPacket testPacket = new TestDhcpPacket(DHCP_MESSAGE_TYPE_OFFER);
        if (leaseTimeBytes != null) {
            testPacket.setLeaseTimeBytes(leaseTimeBytes);
        }
        ByteBuffer packet = testPacket.build();
        DhcpPacket offerPacket = null;

        if (!expectValid) {
            try {
                offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP,
                        TEST_EMPTY_OPTIONS_SKIP_LIST);
                fail("Invalid packet parsed successfully: " + offerPacket);
            } catch (ParseException expected) {
            }
            return;
        }

        offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertNotNull(offerPacket);
        assertEquals(rawLeaseTime, offerPacket.mLeaseTime);
        DhcpResults dhcpResults = offerPacket.toDhcpResults();  // Just check this doesn't crash.
        assertEquals(leaseTimeMillis,
                offerPacket.getLeaseTimeMillis(DhcpPacket.DEFAULT_MINIMUM_LEASE));
    }

    @Test
    public void testLeaseTime() throws Exception {
        byte[] noLease = null;
        byte[] tooShortLease = new byte[] { 0x00, 0x00 };
        byte[] tooLongLease = new byte[] { 0x00, 0x00, 0x00, 60, 0x01 };
        byte[] zeroLease = new byte[] { 0x00, 0x00, 0x00, 0x00 };
        byte[] tenSecondLease = new byte[] { 0x00, 0x00, 0x00, 10 };
        byte[] oneMinuteLease = new byte[] { 0x00, 0x00, 0x00, 60 };
        byte[] fiveMinuteLease = new byte[] { 0x00, 0x00, 0x01, 0x2c };
        byte[] oneDayLease = new byte[] { 0x00, 0x01, 0x51, (byte) 0x80 };
        byte[] maxIntPlusOneLease = new byte[] { (byte) 0x80, 0x00, 0x00, 0x01 };
        byte[] infiniteLease = new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };

        assertLeaseTimeParses(true, null, 0, noLease);
        assertLeaseTimeParses(false, null, 0, tooShortLease);
        assertLeaseTimeParses(false, null, 0, tooLongLease);
        assertLeaseTimeParses(true, 0, 60 * 1000, zeroLease);
        assertLeaseTimeParses(true, 10, 60 * 1000, tenSecondLease);
        assertLeaseTimeParses(true, 60, 60 * 1000, oneMinuteLease);
        assertLeaseTimeParses(true, 300, 300 * 1000, fiveMinuteLease);
        assertLeaseTimeParses(true, 86400, 86400 * 1000, oneDayLease);
        assertLeaseTimeParses(true, -2147483647, 2147483649L * 1000, maxIntPlusOneLease);
        assertLeaseTimeParses(true, DhcpPacket.INFINITE_LEASE, 0, infiniteLease);
    }

    private void checkIpAddress(String expected, Inet4Address clientIp, Inet4Address yourIp,
                                byte[] netmaskBytes) throws Exception {
        checkIpAddress(expected, DHCP_MESSAGE_TYPE_OFFER, clientIp, yourIp, netmaskBytes);
        checkIpAddress(expected, DHCP_MESSAGE_TYPE_ACK, clientIp, yourIp, netmaskBytes);
    }

    private void checkIpAddress(String expected, byte type,
                                Inet4Address clientIp, Inet4Address yourIp,
                                byte[] netmaskBytes) throws Exception {
        ByteBuffer packet = new TestDhcpPacket(type, clientIp, yourIp)
                .setNetmaskBytes(netmaskBytes)
                .build();
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_BOOTP,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        DhcpResults results = offerPacket.toDhcpResults();

        if (expected != null) {
            LinkAddress expectedAddress = new LinkAddress(expected);
            assertEquals(expectedAddress, results.ipAddress);
        } else {
            assertNull(results);
        }
    }

    @Test
    public void testIpAddress() throws Exception {
        byte[] slash11Netmask = new byte[] { (byte) 0xff, (byte) 0xe0, 0x00, 0x00 };
        byte[] slash24Netmask = new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x00 };
        byte[] invalidNetmask = new byte[] { (byte) 0xff, (byte) 0xfb, (byte) 0xff, 0x00 };
        Inet4Address example1 = v4Address("192.0.2.1");
        Inet4Address example2 = v4Address("192.0.2.43");

        // A packet without any addresses is not valid.
        checkIpAddress(null, ANY, ANY, slash24Netmask);

        // ClientIP is used iff YourIP is not present.
        checkIpAddress("192.0.2.1/24", example2, example1, slash24Netmask);
        checkIpAddress("192.0.2.43/11", example2, ANY, slash11Netmask);
        checkIpAddress("192.0.2.43/11", ANY, example2, slash11Netmask);

        // Invalid netmasks are ignored.
        checkIpAddress(null, example2, ANY, invalidNetmask);

        // If there is no netmask, implicit netmasks are used.
        checkIpAddress("192.0.2.43/24", ANY, example2, null);
    }

    private void assertDhcpResults(String ipAddress, String gateway, String dnsServersString,
            String domains, String serverAddress, String serverHostName, String vendorInfo,
            int leaseDuration, boolean hasMeteredHint, int mtu, DhcpResults dhcpResults)
                    throws Exception {
        assertEquals(new LinkAddress(ipAddress), dhcpResults.ipAddress);
        assertEquals(v4Address(gateway), dhcpResults.gateway);

        String[] dnsServerStrings = dnsServersString.split(",");
        ArrayList dnsServers = new ArrayList();
        for (String dnsServerString : dnsServerStrings) {
            dnsServers.add(v4Address(dnsServerString));
        }
        assertEquals(dnsServers, dhcpResults.dnsServers);

        assertEquals(domains, dhcpResults.domains);
        assertEquals(v4Address(serverAddress), dhcpResults.serverAddress);
        assertEquals(serverHostName, dhcpResults.serverHostName);
        assertEquals(vendorInfo, dhcpResults.vendorInfo);
        assertEquals(leaseDuration, dhcpResults.leaseDuration);
        assertEquals(hasMeteredHint, dhcpResults.hasMeteredHint());
        assertEquals(mtu, dhcpResults.mtu);
    }

    @Test
    public void testOffer1() throws Exception {
        // TODO: Turn all of these into golden files. This will probably require using
        // androidx.test.InstrumentationRegistry for obtaining a Context object
        // to read such golden files, along with an appropriate Android.mk.
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // IP header.
            "451001480000000080118849c0a89003c0a89ff7" +
            // UDP header.
            "004300440134dcfa" +
            // BOOTP header.
            "02010600c997a63b0000000000000000c0a89ff70000000000000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a89003330400001c200104fffff0000304c0a89ffe06080808080808080404" +
            "3a0400000e103b040000189cff00000000000000000000"));
        // CHECKSTYLE:ON Generated code

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("192.168.159.247/20", "192.168.159.254", "8.8.8.8,8.8.4.4",
                null, "192.168.144.3", "", null, 7200, false, 0, dhcpResults);
    }

    @Test
    public void testOffer2() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name ("dhcp.android.com" plus invalid "AAAA" after null terminator).
            "646863702e616e64726f69642e636f6d00000000000000000000000000000000" +
            "0000000000004141414100000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
            "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544ff"));
        // CHECKSTYLE:ON Generated code

        assertEquals(337, packet.limit());
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("192.168.43.247/24", "192.168.43.1", "192.168.43.1",
                null, "192.168.43.1", "dhcp.android.com", "ANDROID_METERED", 3600, true, 0,
                dhcpResults);
        assertTrue(dhcpResults.hasMeteredHint());
    }

    private void runCapportOptionTest(boolean enabled) throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
                // IP header.
                "450001518d0600004011144dc0a82b01c0a82bf7" +
                // UDP header
                "00430044013d9ac7" +
                // BOOTP header
                "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
                // MAC address.
                "30766ff2a90c00000000000000000000" +
                // Server name ("dhcp.android.com" plus invalid "AAAA" after null terminator).
                "646863702e616e64726f69642e636f6d00000000000000000000000000000000" +
                "0000000000004141414100000000000000000000000000000000000000000000" +
                // File.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // Options
                "638253633501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
                "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544721d" +
                "68747470733a2f2f706f7274616c6170692e6578616d706c652e636f6dff"));
        // CHECKSTYLE:ON Generated code

        final DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                enabled ? TEST_EMPTY_OPTIONS_SKIP_LIST
                        : new byte[] { DhcpPacket.DHCP_CAPTIVE_PORTAL });
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        final DhcpResults dhcpResults = offerPacket.toDhcpResults();
        final String testUrl = enabled ? "https://portalapi.example.com" : null;
        assertEquals(testUrl, dhcpResults.captivePortalApiUrl);
    }

    @Test
    public void testCapportOption() throws Exception {
        runCapportOptionTest(true /* enabled */);
    }

    @Test
    public void testCapportOption_Disabled() throws Exception {
        runCapportOptionTest(false /* enabled */);
    }

    @Test
    public void testCapportOption_Invalid() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
                // IP header.
                "450001518d0600004011144dc0a82b01c0a82bf7" +
                // UDP header
                "00430044013d9ac7" +
                // BOOTP header
                "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
                // MAC address.
                "30766ff2a90c00000000000000000000" +
                // Server name ("dhcp.android.com" plus invalid "AAAA" after null terminator).
                "646863702e616e64726f69642e636f6d00000000000000000000000000000000" +
                "0000000000004141414100000000000000000000000000000000000000000000" +
                // File.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // Options
                "638253633501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
                "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544" +
                // Option 114 (0x72, capport), length 10 (0x0a)
                "720a" +
                // バグ-com in UTF-8, plus the ff byte that marks the end of options.
                "e38390e382b02d636f6dff"));
        // CHECKSTYLE:ON Generated code

        final DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        final DhcpResults dhcpResults = offerPacket.toDhcpResults();
        // Output URL will be garbled because some characters do not exist in the target charset,
        // but the parser should not crash.
        assertTrue(dhcpResults.captivePortalApiUrl.length() > 0);
    }

    private void runIPv6OnlyPreferredOption(boolean enabled) throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
                // IP header.
                "45100158000040004011B5CEC0A80164C0A80102" +
                // UDP header
                "004300440144CE63" +
                // BOOTP header
                "02010600B8BF41E60000000000000000C0A80102C0A8016400000000" +
                // MAC address.
                "22B3614EE01200000000000000000000" +
                // Server name and padding.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // File.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // Options
                "638253633501023604C0A80164330400000E103A04000007083B0400000C4E01" +
                "04FFFFFF001C04C0A801FF0304C0A801640604C0A801640C0C74657374686F73" +
                "746E616D651A0205DC" +
                // Option 108 (0x6c, IPv6-Only preferred option), length 4 (0x04), 1800s
                "6C0400000708" +
                // End of options.
                "FF"));
        // CHECKSTYLE:ON Generated code

        final DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                enabled ? TEST_EMPTY_OPTIONS_SKIP_LIST
                        : new byte[] { DhcpPacket.DHCP_IPV6_ONLY_PREFERRED });
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        assertEquals(offerPacket.mIpv6OnlyWaitTime,
                enabled ? new Integer(TEST_IPV6_ONLY_WAIT_S) : null);
    }

    @Test
    public void testIPv6OnlyPreferredOption() throws Exception {
        runIPv6OnlyPreferredOption(true /* enabled */);
    }

    @Test
    public void testIPv6OnlyPreferredOption_Disable() throws Exception {
        runIPv6OnlyPreferredOption(false /* enabled */);
    }

    @Test
    public void testBadIpPacket() throws Exception {
        final byte[] packet = HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7");

        try {
            DhcpPacket.decodeFullPacket(packet, packet.length, ENCAP_L3,
                    TEST_EMPTY_OPTIONS_SKIP_LIST);
        } catch (DhcpPacket.ParseException expected) {
            assertDhcpErrorCodes(DhcpErrorEvent.L3_TOO_SHORT, expected.errorCode);
            return;
        }
        fail("Dhcp packet parsing should have failed");
    }

    @Test
    public void testBadDhcpPacket() throws Exception {
        final byte[] packet = HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000");

        try {
            DhcpPacket.decodeFullPacket(packet, packet.length, ENCAP_L3);
        } catch (DhcpPacket.ParseException expected) {
            assertDhcpErrorCodes(DhcpErrorEvent.L3_TOO_SHORT, expected.errorCode);
            return;
        }
        fail("Dhcp packet parsing should have failed");
    }

    @Test
    public void testBadTruncatedOffer() throws Exception {
        final byte[] packet = HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File, missing one byte
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000000000000");

        try {
            DhcpPacket.decodeFullPacket(packet, packet.length, ENCAP_L3);
        } catch (DhcpPacket.ParseException expected) {
            assertDhcpErrorCodes(DhcpErrorEvent.L3_TOO_SHORT, expected.errorCode);
            return;
        }
        fail("Dhcp packet parsing should have failed");
    }

    @Test
    public void testBadOfferWithoutACookie() throws Exception {
        final byte[] packet = HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000"
            // No options
            );

        try {
            DhcpPacket.decodeFullPacket(packet, packet.length, ENCAP_L3);
        } catch (DhcpPacket.ParseException expected) {
            assertDhcpErrorCodes(DhcpErrorEvent.DHCP_NO_COOKIE, expected.errorCode);
            return;
        }
        fail("Dhcp packet parsing should have failed");
    }

    @Test
    public void testOfferWithBadCookie() throws Exception {
        final byte[] packet = HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Bad cookie
            "DEADBEEF3501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
            "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544ff");

        try {
            DhcpPacket.decodeFullPacket(packet, packet.length, ENCAP_L3);
        } catch (DhcpPacket.ParseException expected) {
            assertDhcpErrorCodes(DhcpErrorEvent.DHCP_BAD_MAGIC_COOKIE, expected.errorCode);
            return;
        }
        fail("Dhcp packet parsing should have failed");
    }

    private void assertDhcpErrorCodes(int expected, int got) {
        assertEquals(Integer.toHexString(expected), Integer.toHexString(got));
    }

    @Test
    public void testTruncatedOfferPackets() throws Exception {
        final byte[] packet = HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
            "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544ff");

        for (int len = 0; len < packet.length; len++) {
            try {
                DhcpPacket.decodeFullPacket(packet, len, ENCAP_L3);
            } catch (ParseException e) {
                if (e.errorCode == DhcpErrorEvent.PARSING_ERROR) {
                    fail(String.format("bad truncated packet of length %d", len));
                }
            }
        }
    }

    @Test
    public void testRandomPackets() throws Exception {
        final int maxRandomPacketSize = 512;
        final Random r = new Random();
        for (int i = 0; i < 10000; i++) {
            byte[] packet = new byte[r.nextInt(maxRandomPacketSize + 1)];
            r.nextBytes(packet);
            try {
                DhcpPacket.decodeFullPacket(packet, packet.length, ENCAP_L3);
            } catch (ParseException e) {
                if (e.errorCode == DhcpErrorEvent.PARSING_ERROR) {
                    fail("bad packet: " + HexDump.toHexString(packet));
                }
            }
        }
    }

    private byte[] mtuBytes(int mtu) {
        // 0x1a02: option 26, length 2. 0xff: no more options.
        if (mtu > Short.MAX_VALUE - Short.MIN_VALUE) {
            throw new IllegalArgumentException(
                String.format("Invalid MTU %d, must be 16-bit unsigned", mtu));
        }
        String hexString = String.format("1a02%04xff", mtu);
        return HexDump.hexStringToByteArray(hexString);
    }

    private void checkMtu(ByteBuffer packet, int expectedMtu, byte[] mtuBytes) throws Exception {
        if (mtuBytes != null) {
            packet.position(packet.capacity() - mtuBytes.length);
            packet.put(mtuBytes);
            packet.clear();
        }
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);  // Implicitly checks it's non-null.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("192.168.159.247/20", "192.168.159.254", "8.8.8.8,8.8.4.4",
                null, "192.168.144.3", "", null, 7200, false, expectedMtu, dhcpResults);
    }

    @Test
    public void testMtu() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // IP header.
            "451001480000000080118849c0a89003c0a89ff7" +
            // UDP header.
            "004300440134dcfa" +
            // BOOTP header.
            "02010600c997a63b0000000000000000c0a89ff70000000000000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a89003330400001c200104fffff0000304c0a89ffe06080808080808080404" +
            "3a0400000e103b040000189cff00000000"));
        // CHECKSTYLE:ON Generated code

        checkMtu(packet, 0, null);
        checkMtu(packet, 0, mtuBytes(1501));
        checkMtu(packet, 1500, mtuBytes(1500));
        checkMtu(packet, 1499, mtuBytes(1499));
        checkMtu(packet, 1280, mtuBytes(1280));
        checkMtu(packet, 0, mtuBytes(1279));
        checkMtu(packet, 0, mtuBytes(576));
        checkMtu(packet, 0, mtuBytes(68));
        checkMtu(packet, 0, mtuBytes(Short.MIN_VALUE));
        checkMtu(packet, 0, mtuBytes(Short.MAX_VALUE + 3));
        checkMtu(packet, 0, mtuBytes(-1));
    }

    @Test
    public void testExplicitClientId() throws Exception {
        final byte[] clientId = new byte[] {
                0x01 /* CLIENT_ID_ETH */, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06 };

        // CHECKSTYLE:OFF Generated code
        final byte[] packet = HexDump.hexStringToByteArray(
                // IP header.
                "450001518d0600004011144dc0a82b01c0a82bf7" +
                // UDP header
                "00430044013d9ac7" +
                // BOOTP header
                "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
                // MAC address.
                "30766ff2a90c00000000000000000000" +
                // Server name ("dhcp.android.com" plus invalid "AAAA" after null terminator).
                "646863702e616e64726f69642e636f6d00000000000000000000000000000000" +
                "0000000000004141414100000000000000000000000000000000000000000000" +
                // File.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // Options
                "638253633501013d0701010203040506390205dc3c0e616e64726f69642d6468" +
                "63702d52370a0103060f1a1c333a3b2bff00");
        // CHECKSTYLE:ON Generated code

        final DhcpPacket discoverPacket = DhcpPacket.decodeFullPacket(packet,
                packet.length, ENCAP_L3);
        assertTrue(discoverPacket instanceof DhcpDiscoverPacket);
        assertTrue(discoverPacket.hasExplicitClientId());
        assertTrue(Arrays.equals(discoverPacket.mClientId, clientId));
    }

    @Test
    public void testBadHwaddrLength() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // IP header.
            "450001518d0600004011144dc0a82b01c0a82bf7" +
            // UDP header.
            "00430044013d9ac7" +
            // BOOTP header.
            "02010600dfc23d1f0002000000000000c0a82bf7c0a82b0100000000" +
            // MAC address.
            "30766ff2a90c00000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604c0a82b01330400000e103a04000007083b0400000c4e0104ffffff00" +
            "1c04c0a82bff0304c0a82b010604c0a82b012b0f414e44524f49445f4d455445524544ff"));
        // CHECKSTYLE:ON Generated code
        String expectedClientMac = "30766FF2A90C";

        final int hwAddrLenOffset = 20 + 8 + 2;
        assertEquals(6, packet.get(hwAddrLenOffset));

        // Expect the expected.
        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertNotNull(offerPacket);
        assertEquals(6, offerPacket.getClientMac().length);
        assertEquals(expectedClientMac, HexDump.toHexString(offerPacket.getClientMac()));

        // Reduce the hardware address length and verify that it shortens the client MAC.
        packet.flip();
        packet.put(hwAddrLenOffset, (byte) 5);
        offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3, TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertNotNull(offerPacket);
        assertEquals(5, offerPacket.getClientMac().length);
        assertEquals(expectedClientMac.substring(0, 10),
                HexDump.toHexString(offerPacket.getClientMac()));

        packet.flip();
        packet.put(hwAddrLenOffset, (byte) 3);
        offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3, TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertNotNull(offerPacket);
        assertEquals(3, offerPacket.getClientMac().length);
        assertEquals(expectedClientMac.substring(0, 6),
                HexDump.toHexString(offerPacket.getClientMac()));

        // Set the the hardware address length to 0xff and verify that we a) don't treat it as -1
        // and crash, and b) hardcode it to 6.
        packet.flip();
        packet.put(hwAddrLenOffset, (byte) -1);
        offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3, TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertNotNull(offerPacket);
        assertEquals(6, offerPacket.getClientMac().length);
        assertEquals(expectedClientMac, HexDump.toHexString(offerPacket.getClientMac()));

        // Set the the hardware address length to a positive invalid value (> 16) and verify that we
        // hardcode it to 6.
        packet.flip();
        packet.put(hwAddrLenOffset, (byte) 17);
        offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3, TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertNotNull(offerPacket);
        assertEquals(6, offerPacket.getClientMac().length);
        assertEquals(expectedClientMac, HexDump.toHexString(offerPacket.getClientMac()));
    }

    @Test
    public void testPadAndOverloadedOptionsOffer() throws Exception {
        // A packet observed in the real world that is interesting for two reasons:
        //
        // 1. It uses pad bytes, which we previously didn't support correctly.
        // 2. It uses DHCP option overloading, which we don't currently support (but it doesn't
        //    store any information in the overloaded fields).
        //
        // For now, we just check that it parses correctly.
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // Ethernet header.
            "b4cef6000000e80462236e300800" +
            // IP header.
            "4500014c00000000ff11741701010101ac119876" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "004300440138ae5a" +
            // BOOTP header.
            "020106000fa0059f0000000000000000ac1198760000000000000000" +
            // MAC address.
            "b4cef600000000000000000000000000" +
            // Server name.
            "ff00000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "ff00000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options
            "638253633501023604010101010104ffff000033040000a8c03401030304ac1101010604ac110101" +
            "0000000000000000000000000000000000000000000000ff000000"));
        // CHECKSTYLE:ON Generated code

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L2,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("172.17.152.118/16", "172.17.1.1", "172.17.1.1",
                null, "1.1.1.1", "", null, 43200, false, 0, dhcpResults);
    }

    @Test
    public void testBug2111() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // IP header.
            "4500014c00000000ff119beac3eaf3880a3f5d04" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "0043004401387464" +
            // BOOTP header.
            "0201060002554812000a0000000000000a3f5d040000000000000000" +
            // MAC address.
            "00904c00000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "638253633501023604c00002fe33040000bfc60104fffff00003040a3f50010608c0000201c0000202" +
            "0f0f646f6d61696e3132332e636f2e756b0000000000ff00000000"));
        // CHECKSTYLE:ON Generated code

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("10.63.93.4/20", "10.63.80.1", "192.0.2.1,192.0.2.2",
                "domain123.co.uk", "192.0.2.254", "", null, 49094, false, 0, dhcpResults);
    }

    @Test
    public void testBug2136() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // Ethernet header.
            "bcf5ac000000d0c7890000000800" +
            // IP header.
            "4500014c00000000ff119beac3eaf3880a3f5d04" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "0043004401387574" +
            // BOOTP header.
            "0201060163339a3000050000000000000a209ecd0000000000000000" +
            // MAC address.
            "bcf5ac00000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "6382536335010236040a20ff80330400001c200104fffff00003040a20900106089458413494584135" +
            "0f0b6c616e63732e61632e756b000000000000000000ff00000000"));
        // CHECKSTYLE:ON Generated code

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L2,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        assertEquals("BCF5AC000000", HexDump.toHexString(offerPacket.getClientMac()));
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("10.32.158.205/20", "10.32.144.1", "148.88.65.52,148.88.65.53",
                "lancs.ac.uk", "10.32.255.128", "", null, 7200, false, 0, dhcpResults);
    }

    @Test
    public void testUdpServerAnySourcePort() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // Ethernet header.
            "9cd917000000001c2e0000000800" +
            // IP header.
            "45a00148000040003d115087d18194fb0a0f7af2" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            // NOTE: The server source port is not the canonical port 67.
            "C29F004401341268" +
            // BOOTP header.
            "02010600d628ba8200000000000000000a0f7af2000000000a0fc818" +
            // MAC address.
            "9cd91700000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "6382536335010236040a0169fc3304000151800104ffff000003040a0fc817060cd1818003d1819403" +
            "d18180060f0777766d2e6564751c040a0fffffff000000"));
        // CHECKSTYLE:ON Generated code

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L2,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        assertEquals("9CD917000000", HexDump.toHexString(offerPacket.getClientMac()));
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("10.15.122.242/16", "10.15.200.23",
                "209.129.128.3,209.129.148.3,209.129.128.6",
                "wvm.edu", "10.1.105.252", "", null, 86400, false, 0, dhcpResults);
    }

    @Test
    public void testUdpInvalidDstPort() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // Ethernet header.
            "9cd917000000001c2e0000000800" +
            // IP header.
            "45a00148000040003d115087d18194fb0a0f7af2" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            // NOTE: The destination port is a non-DHCP port.
            "0043aaaa01341268" +
            // BOOTP header.
            "02010600d628ba8200000000000000000a0f7af2000000000a0fc818" +
            // MAC address.
            "9cd91700000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "6382536335010236040a0169fc3304000151800104ffff000003040a0fc817060cd1818003d1819403" +
            "d18180060f0777766d2e6564751c040a0fffffff000000"));
        // CHECKSTYLE:ON Generated code

        try {
            DhcpPacket.decodeFullPacket(packet, ENCAP_L2, TEST_EMPTY_OPTIONS_SKIP_LIST);
            fail("Packet with invalid dst port did not throw ParseException");
        } catch (ParseException expected) {}
    }

    @Test
    public void testMultipleRouters() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
            // Ethernet header.
            "fc3d93000000" + "081735000000" + "0800" +
            // IP header.
            "45000148c2370000ff117ac2c0a8bd02ffffffff" +
            // UDP header. TODO: fix invalid checksum (due to MAC address obfuscation).
            "0043004401343beb" +
            // BOOTP header.
            "0201060027f518e20000800000000000c0a8bd310000000000000000" +
            // MAC address.
            "fc3d9300000000000000000000000000" +
            // Server name.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // File.
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            // Options.
            "638253633501023604c0abbd023304000070803a04000038403b04000062700104ffffff00" +
            "0308c0a8bd01ffffff0006080808080808080404ff000000000000"));
        // CHECKSTYLE:ON Generated code

        DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L2,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        assertEquals("FC3D93000000", HexDump.toHexString(offerPacket.getClientMac()));
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertDhcpResults("192.168.189.49/24", "192.168.189.1", "8.8.8.8,8.8.4.4",
                null, "192.171.189.2", "", null, 28800, false, 0, dhcpResults);
    }

    @Test
    public void testDiscoverPacket() throws Exception {
        final short secs = 7;
        final int transactionId = 0xdeadbeef;
        final byte[] hwaddr = {
                (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b, (byte) 0xb1, (byte) 0x7a
        };
        final String testHostname = "android-01234567890abcde";

        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(
                DhcpPacket.ENCAP_L2, transactionId, secs, hwaddr,
                false /* do unicast */, DhcpClient.DEFAULT_REQUESTED_PARAMS,
                false /* rapid commit */, testHostname, null /* customized DHCP options */);

        final byte[] headers = new byte[] {
            // Ethernet header.
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b, (byte) 0xb1, (byte) 0x7a,
            (byte) 0x08, (byte) 0x00,
            // IP header.
            (byte) 0x45, (byte) 0x10, (byte) 0x01, (byte) 0x56,
            (byte) 0x00, (byte) 0x00, (byte) 0x40, (byte) 0x00,
            (byte) 0x40, (byte) 0x11, (byte) 0x39, (byte) 0x88,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            // UDP header.
            (byte) 0x00, (byte) 0x44, (byte) 0x00, (byte) 0x43,
            (byte) 0x01, (byte) 0x42, (byte) 0x6a, (byte) 0x4a,
            // BOOTP.
            (byte) 0x01, (byte) 0x01, (byte) 0x06, (byte) 0x00,
            (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef,
            (byte) 0x00, (byte) 0x07, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b,
            (byte) 0xb1, (byte) 0x7a
        };
        final byte[] options = new byte[] {
            // Magic cookie 0x63825363.
            (byte) 0x63, (byte) 0x82, (byte) 0x53, (byte) 0x63,
            // Message type DISCOVER.
            (byte) 0x35, (byte) 0x01, (byte) 0x01,
            // Client identifier Ethernet, da:01:19:5b:b1:7a.
            (byte) 0x3d, (byte) 0x07,
                    (byte) 0x01,
                    (byte) 0xda, (byte) 0x01, (byte) 0x19, (byte) 0x5b, (byte) 0xb1, (byte) 0x7a,
            // Max message size 1500.
            (byte) 0x39, (byte) 0x02, (byte) 0x05, (byte) 0xdc,
            // Version "android-dhcp-???".
            (byte) 0x3c, (byte) 0x10,
                    'a', 'n', 'd', 'r', 'o', 'i', 'd', '-', 'd', 'h', 'c', 'p', '-', '?', '?', '?',
            // Hostname "android-01234567890abcde"
            (byte) 0x0c, (byte) 0x18,
                    'a', 'n', 'd', 'r', 'o', 'i', 'd', '-',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0', 'a', 'b', 'c', 'd', 'e',
            // Requested parameter list.
            (byte) 0x37, (byte) 0x0a,
                DHCP_SUBNET_MASK,
                DHCP_ROUTER,
                DHCP_DNS_SERVER,
                DHCP_DOMAIN_NAME,
                DHCP_MTU,
                DHCP_BROADCAST_ADDRESS,
                DHCP_LEASE_TIME,
                DHCP_RENEWAL_TIME,
                DHCP_REBINDING_TIME,
                DHCP_VENDOR_INFO,
            // End options.
            (byte) 0xff,
            // Our packets are always of even length. TODO: find out why and possibly fix it.
            (byte) 0x00
        };
        final byte[] expected = new byte[DhcpPacket.MIN_PACKET_LENGTH_L2 + options.length];
        assertTrue((expected.length & 1) == 0);
        assertEquals(DhcpPacket.MIN_PACKET_LENGTH_L2,
                headers.length + 10 /* client hw addr padding */ + 64 /* sname */ + 128 /* file */);
        System.arraycopy(headers, 0, expected, 0, headers.length);
        System.arraycopy(options, 0, expected, DhcpPacket.MIN_PACKET_LENGTH_L2, options.length);

        final byte[] actual = new byte[packet.limit()];
        packet.get(actual);
        String msg = "Expected:\n  " + Arrays.toString(expected) + "\nActual:\n  "
                + Arrays.toString(actual);
        assertTrue(msg, Arrays.equals(expected, actual));
    }

    public void checkBuildOfferPacket(int leaseTimeSecs, @Nullable String hostname)
            throws Exception {
        final int renewalTime = (int) (Integer.toUnsignedLong(leaseTimeSecs) / 2);
        final int rebindingTime = (int) (Integer.toUnsignedLong(leaseTimeSecs) * 875 / 1000);
        final int transactionId = 0xdeadbeef;

        final ByteBuffer packet = DhcpPacket.buildOfferPacket(
                DhcpPacket.ENCAP_BOOTP, transactionId, false /* broadcast */,
                SERVER_ADDR, INADDR_ANY /* relayIp */, CLIENT_ADDR /* yourIp */,
                CLIENT_MAC, leaseTimeSecs, NETMASK /* netMask */,
                BROADCAST_ADDR /* bcAddr */, Collections.singletonList(SERVER_ADDR) /* gateways */,
                Collections.singletonList(SERVER_ADDR) /* dnsServers */,
                SERVER_ADDR /* dhcpServerIdentifier */, null /* domainName */, hostname,
                false /* metered */, MTU, CAPTIVE_PORTAL_API_URL);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // BOOTP headers
        bos.write(new byte[] {
                (byte) 0x02, (byte) 0x01, (byte) 0x06, (byte) 0x00,
                (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                // ciaddr
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        });
        // yiaddr
        bos.write(CLIENT_ADDR.getAddress());
        // siaddr
        bos.write(SERVER_ADDR.getAddress());
        // giaddr
        bos.write(INADDR_ANY.getAddress());
        // chaddr
        bos.write(CLIENT_MAC);

        // Padding
        bos.write(new byte[202]);

        // Options
        bos.write(new byte[]{
                // Magic cookie 0x63825363.
                (byte) 0x63, (byte) 0x82, (byte) 0x53, (byte) 0x63,
                // Message type OFFER.
                (byte) 0x35, (byte) 0x01, (byte) 0x02,
        });
        // Server ID
        bos.write(new byte[] { (byte) 0x36, (byte) 0x04 });
        bos.write(SERVER_ADDR.getAddress());
        // Lease time
        bos.write(new byte[] { (byte) 0x33, (byte) 0x04 });
        bos.write(intToByteArray(leaseTimeSecs));
        if (leaseTimeSecs != INFINITE_LEASE) {
            // Renewal time
            bos.write(new byte[]{(byte) 0x3a, (byte) 0x04});
            bos.write(intToByteArray(renewalTime));
            // Rebinding time
            bos.write(new byte[]{(byte) 0x3b, (byte) 0x04});
            bos.write(intToByteArray(rebindingTime));
        }
        // Subnet mask
        bos.write(new byte[] { (byte) 0x01, (byte) 0x04 });
        bos.write(NETMASK.getAddress());
        // Broadcast address
        bos.write(new byte[] { (byte) 0x1c, (byte) 0x04 });
        bos.write(BROADCAST_ADDR.getAddress());
        // Router
        bos.write(new byte[] { (byte) 0x03, (byte) 0x04 });
        bos.write(SERVER_ADDR.getAddress());
        // Nameserver
        bos.write(new byte[] { (byte) 0x06, (byte) 0x04 });
        bos.write(SERVER_ADDR.getAddress());
        // Hostname
        if (hostname != null) {
            bos.write(new byte[]{(byte) 0x0c, (byte) hostname.length()});
            bos.write(hostname.getBytes(Charset.forName("US-ASCII")));
        }
        // MTU
        bos.write(new byte[] { (byte) 0x1a, (byte) 0x02 });
        bos.write(shortToByteArray(MTU));
        // capport URL. Option 114 = 0x72
        bos.write(new byte[] { (byte) 0x72, (byte) CAPTIVE_PORTAL_API_URL.length() });
        bos.write(CAPTIVE_PORTAL_API_URL.getBytes(Charset.forName("US-ASCII")));
        // End options.
        bos.write(0xff);

        if ((bos.size() & 1) != 0) {
            bos.write(0x00);
        }

        final byte[] expected = bos.toByteArray();
        final byte[] actual = new byte[packet.limit()];
        packet.get(actual);
        final String msg = "Expected:\n  " + HexDump.dumpHexString(expected) +
                "\nActual:\n  " + HexDump.dumpHexString(actual);
        assertTrue(msg, Arrays.equals(expected, actual));
    }

    @Test
    public void testOfferPacket() throws Exception {
        checkBuildOfferPacket(3600, HOSTNAME);
        checkBuildOfferPacket(Integer.MAX_VALUE, HOSTNAME);
        checkBuildOfferPacket(0x80000000, HOSTNAME);
        checkBuildOfferPacket(INFINITE_LEASE, HOSTNAME);
        checkBuildOfferPacket(3600, null);
    }

    @Test
    public void testInvalidLengthIpv6OnlyPreferredOption() throws Exception {
        // CHECKSTYLE:OFF Generated code
        final ByteBuffer packet = ByteBuffer.wrap(HexDump.hexStringToByteArray(
                // IP header.
                "45100158000040004011B5CEC0A80164C0A80102" +
                // UDP header
                "004300440144CE63" +
                // BOOTP header
                "02010600B8BF41E60000000000000000C0A80102C0A8016400000000" +
                // MAC address.
                "22B3614EE01200000000000000000000" +
                // Server name and padding.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // File.
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                // Options
                "638253633501023604C0A80164330400000E103A04000007083B0400000C4E01" +
                "04FFFFFF001C04C0A801FF0304C0A801640604C0A801640C0C74657374686F73" +
                "746E616D651A0205DC" +
                // Option 108 (0x6c, IPv6-Only preferred option), length 8 (0x08)
                "6C080102030405060708" +
                // End of options.
                "FF"));
        // CHECKSTYLE:ON Generated code

        final DhcpPacket offerPacket = DhcpPacket.decodeFullPacket(packet, ENCAP_L3,
                TEST_EMPTY_OPTIONS_SKIP_LIST);
        // rfc8925#section-3.1: The client MUST ignore the IPv6-Only Preferred option if the length
        // field value is not 4.
        assertTrue(offerPacket instanceof DhcpOfferPacket);
        assertEquals(offerPacket.mIpv6OnlyWaitTime, null);
    }

    private static byte[] intToByteArray(int val) {
        return ByteBuffer.allocate(4).putInt(val).array();
    }

    private static byte[] shortToByteArray(short val) {
        return ByteBuffer.allocate(2).putShort(val).array();
    }
}

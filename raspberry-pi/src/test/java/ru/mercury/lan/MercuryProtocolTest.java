package ru.mercury.lan;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class MercuryProtocolTest {
    private static final long FIXTURE_ADDRESS = 0xfe123456L;
    // Independent published fixtures: wirenboard/wb-mqtt-serial/test/mercury200_expectations.cpp
    private static final byte[] REQUEST = HexFormat.of().parseHex("fe1234562736d8");
    private static final byte[] RESPONSE = HexFormat.of().parseHex("fe12345627000621420002083400011111000222222b25");

    @Test void usesPublishedRequestCrcAndAddressOrder() {
        assertArrayEquals(REQUEST, MercuryProtocol.request(FIXTURE_ADDRESS));
        assertArrayEquals(HexFormat.of().parseHex("0009f089270025"), MercuryProtocol.request(651401));
    }

    @Test void decodesPublishedTariffsWithoutLosingHundredths() {
        assertArrayEquals(new long[] {62142, 20834, 11111, 22222}, MercuryProtocol.decode(FIXTURE_ADDRESS, RESPONSE));
    }

    @Test void rejectsTruncationCorruptionWrongAddressAndWrongCommand() {
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.decode(FIXTURE_ADDRESS, Arrays.copyOf(RESPONSE, 22)));
        byte[] corrupted = RESPONSE.clone(); corrupted[8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.decode(FIXTURE_ADDRESS, corrupted));
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.decode(FIXTURE_ADDRESS - 1, RESPONSE));
        byte[] wrongCommand = RESPONSE.clone(); wrongCommand[4] = 0x26; MercuryProtocol.appendCrc(wrongCommand);
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.decode(FIXTURE_ADDRESS, wrongCommand));
    }

    @Test void rejectsInvalidBcdEvenWithValidCrc() {
        byte[] corruptDigit = RESPONSE.clone(); corruptDigit[5] = (byte) 0xa0; MercuryProtocol.appendCrc(corruptDigit);
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.decode(FIXTURE_ADDRESS, corruptDigit));
        corruptDigit[5] = 0x0f; MercuryProtocol.appendCrc(corruptDigit);
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.decode(FIXTURE_ADDRESS, corruptDigit));
    }

    @Test void acceptsReplyWithOrWithoutTxEchoAndResynchronizesAfterBadFrames() {
        MercuryProtocol.Scanner scanner = new MercuryProtocol.Scanner(FIXTURE_ADDRESS);
        for (byte b : new byte[] {0, 0x55, (byte) 0xff, 0x7f}) assertTrue(scanner.accept(b).isEmpty());
        for (byte b : REQUEST) assertTrue(scanner.accept(b).isEmpty());
        byte[] corruptReply = RESPONSE.clone(); corruptReply[10] ^= 0x10;
        for (byte b : corruptReply) assertTrue(scanner.accept(b).isEmpty());
        Optional<long[]> result = Optional.empty();
        for (byte b : RESPONSE) result = scanner.accept(b);
        assertArrayEquals(new long[] {62142, 20834, 11111, 22222}, result.orElseThrow());
        scanner.reset();
        for (int i = 0; i < RESPONSE.length - 1; i++) assertTrue(scanner.accept(RESPONSE[i]).isEmpty());
        assertArrayEquals(new long[] {62142, 20834, 11111, 22222}, scanner.accept(RESPONSE[22]).orElseThrow());
    }

    @Test void invalidAddressCannotBeTruncatedSilently() {
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.request(-1));
        assertThrows(IllegalArgumentException.class, () -> MercuryProtocol.request(0x1_0000_0000L));
    }
}

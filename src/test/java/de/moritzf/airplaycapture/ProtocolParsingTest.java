package de.moritzf.airplaycapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.moritzf.airplaycapture.raop.Dmap;
import de.moritzf.airplaycapture.raop.Sdp;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolParsingTest {

    @Test
    void parsesDmapInsideContainer() {
        byte[] blob = dmap(
            "minm",
            "Song",
            "asar",
            "Artist",
            "asal",
            "Album",
            "asgn",
            "Rock",
            "astn",
            3
        );
        Map<String, Object> metadata = Dmap.parse(blob);
        assertEquals("Song", Dmap.text(metadata, "minm"));
        assertEquals("Artist", Dmap.text(metadata, "asar"));
        assertEquals("Album", Dmap.text(metadata, "asal"));
        assertEquals("Rock", Dmap.text(metadata, "asgn"));
        assertEquals(3, Dmap.number(metadata, "astn"));
    }

    @Test
    void parsesSdpWithSessionKey() {
        String sdp = """
        v=0\r
        o=iTunes 123 0 IN IP4 192.168.1.2\r
        s=iTunes\r
        c=IN IP4 192.168.1.2\r
        t=0 0\r
        m=audio 0 RTP/AVP 96\r
        a=rtpmap:96 AppleLossless\r
        a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r
        a=rsaaeskey:AAECAwQFBgcICQoLDA0ODw==\r
        a=aesiv:EBESExQVFhcYGRobHB0eHw==\r
        """;
        Sdp parsed = Sdp.parse(sdp);
        assertNotNull(parsed.alac());
        assertEquals(352, parsed.alac().frameLength());
        assertEquals(2, parsed.alac().channels());
        assertEquals(16, parsed.alac().bitDepth());
        assertEquals(44100, parsed.alac().sampleRate());
        assertTrue(parsed.isEncrypted());
        assertEquals(16, parsed.aesIv().length);
        assertEquals(24, parsed.alac().magicCookie().length);
    }

    private static byte[] dmap(Object... entries) {
        var items = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < entries.length; i += 2) {
            String tag = (String) entries[i];
            byte[] value;
            if (entries[i + 1] instanceof Integer number) {
                value = new byte[] { (byte) (number.intValue() >> 8), (byte) number.intValue() };
            } else {
                value = ((String) entries[i + 1]).getBytes(StandardCharsets.UTF_8);
            }
            items.writeBytes(tag.getBytes(StandardCharsets.US_ASCII));
            items.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());
            items.writeBytes(value);
        }
        byte[] content = items.toByteArray();
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes("mlit".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).putInt(content.length).array());
        out.writeBytes(content);
        return out.toByteArray();
    }
}

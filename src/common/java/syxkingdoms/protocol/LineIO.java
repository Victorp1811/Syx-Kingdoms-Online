package syxkingdoms.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class LineIO {
    private LineIO() {
    }

    public static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (line.size() == 0) {
                    throw new EOFException("Connection closed");
                }
                throw new ProtocolException("TRUNCATED_FRAME", "Connection closed in the middle of a frame");
            }
            if (next == '\n') {
                break;
            }
            if (next == '\r') {
                continue;
            }
            if (next == 0) {
                throw new ProtocolException("BAD_FRAME", "Frame contains a null byte");
            }
            if (line.size() >= Protocol.MAX_LINE_BYTES) {
                throw new ProtocolException("FRAME_TOO_LARGE", "Frame exceeds " + Protocol.MAX_LINE_BYTES + " bytes");
            }
            line.write(next);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    public static void writeLine(OutputStream output, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Protocol.MAX_LINE_BYTES) {
            throw new ProtocolException("FRAME_TOO_LARGE", "Outgoing frame exceeds " + Protocol.MAX_LINE_BYTES + " bytes");
        }
        output.write(bytes);
        output.write('\n');
        output.flush();
    }
}

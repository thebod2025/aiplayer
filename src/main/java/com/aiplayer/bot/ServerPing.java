package com.aiplayer.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 服务器列表 ping：取目标服的版本名，用来判断 mineflayer 能不能直连。
 */
public final class ServerPing {
    /** 1.21.11 的协议号，只用来做握手，不校验服务端返回值 */
    private static final int PROTOCOL = 767;
    private static final int TIMEOUT_MS = 4000;

    private ServerPing() {
    }

    /** 返回服务器版本名（如 {@code 1.21.4}），失败返回 null */
    public static String versionOf(String host, int port) {
        if (host == null || host.isBlank() || port <= 0 || port > 65535) {
            return null;
        }
        try {
            String version;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);
                OutputStream out = socket.getOutputStream();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] handshake = handshake(host, port);
                out.write(varInt(handshake.length));
                out.write(handshake);
                out.flush();
                out.write(varInt(1));
                out.flush();
                int length = readVarInt(in);
                if (length <= 0 || length > 2097152) {
                    return null;
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                int[] offset = new int[]{0};
                readVarInt(payload, offset);
                int jsonLength = readVarInt(payload, offset);
                if (jsonLength <= 0 || offset[0] + jsonLength > payload.length) {
                    return null;
                }
                String json = new String(payload, offset[0], jsonLength, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (!root.has("version") || !root.get("version").isJsonObject()) {
                    return null;
                }
                JsonObject versionObj = root.getAsJsonObject("version");
                version = versionObj.has("name") ? versionObj.get("name").getAsString() : null;
            }
            return version;
        } catch (Throwable t) {
            return null;
        }
    }

    /** mineflayer 能否直连：未知版本（null）一律放行，让 mineflayer 自己判断 */
    public static boolean supported(String version) {
        if (version == null || version.isBlank()) {
            return true;
        }
        String v = version.trim().toLowerCase(Locale.ROOT);
        return v.matches("1\\.(8|9|1\\d|2[01])(\\.\\d+)?");
    }

    private static byte[] handshake(String host, int port) {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[6 + hostBytes.length + 2 + 1];
        int i = 0;
        data[i++] = 0;
        i = writeVarInt(PROTOCOL, data, i);
        data[i++] = (byte) hostBytes.length;
        System.arraycopy(hostBytes, 0, data, i, hostBytes.length);
        i += hostBytes.length;
        data[i++] = (byte) (port >> 8);
        data[i++] = (byte) port;
        data[i] = 1;
        return data;
    }

    private static byte[] varInt(int value) {
        byte[] buffer = new byte[5];
        int length = writeVarInt(value, buffer, 0);
        byte[] exact = new byte[length];
        System.arraycopy(buffer, 0, exact, 0, length);
        return exact;
    }

    private static int writeVarInt(int value, byte[] out, int offset) {
        int i = offset;
        while ((value & -128) != 0) {
            out[i++] = (byte) (value & 127 | 128);
            value >>>= 7;
        }
        out[i++] = (byte) value;
        return i;
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        do {
            byte b = in.readByte();
            value |= (b & 127) << shift;
            if ((b & 128) == 0) {
                return value;
            }
            shift += 7;
        } while (shift < 32);
        throw new IOException("VarInt 过长");
    }

    private static int readVarInt(byte[] data, int[] offset) {
        int value = 0;
        int shift = 0;
        do {
            byte b = data[offset[0]++];
            value |= (b & 127) << shift;
            if ((b & 128) == 0) {
                return value;
            }
            shift += 7;
        } while (shift < 32);
        return value;
    }
}

package com.jinle.serialmonitor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DistanceParser {
    interface Listener {
        void onLine(int port, String line, Double distanceMm);
    }

    private static final Pattern DISTANCE = Pattern.compile(
            "distances\\s*=\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*mm",
            Pattern.CASE_INSENSITIVE);

    private final StringBuilder[] buffers = new StringBuilder[8];
    private final Listener listener;

    DistanceParser(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < buffers.length; i++) buffers[i] = new StringBuilder();
    }

    synchronized void accept(int port, byte[] data, int offset, int length) {
        if (port < 0 || port >= buffers.length || length <= 0) return;
        StringBuilder buffer = buffers[port];
        buffer.append(new String(data, offset, length, StandardCharsets.UTF_8));

        int newline;
        while ((newline = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, newline).replace("\r", "").trim();
            buffer.delete(0, newline + 1);
            emit(port, line);
        }

        if (buffer.length() > 8192) {
            String line = buffer.toString().replace("\r", "").trim();
            buffer.setLength(0);
            emit(port, line);
        }
    }

    private void emit(int port, String line) {
        if (line.isEmpty()) return;
        Double distance = null;
        Matcher matcher = DISTANCE.matcher(line);
        if (matcher.find()) {
            try {
                distance = Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        listener.onLine(port, line, distance);
    }
}

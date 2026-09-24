package com.jinle.serialmonitor;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-space Android USB Host driver for the WCH CH348 eight-port UART bridge.
 * The device multiplexes eight receive channels into fixed 32-byte records:
 * [port][length][up to 30 data bytes].
 */
final class Ch348Device implements Closeable {
    static final int VENDOR_ID = 0x1A86;
    static final int PRODUCT_ID = 0x55D9;
    static final int PORT_COUNT = 8;

    interface Listener {
        void onLine(Ch348Device source, int port, String raw, Double distanceMm);
        void onError(Ch348Device source, String message, Throwable error);
    }

    private static final int BAUD_RATE = 115200;
    private static final int IO_TIMEOUT_MS = 500;
    private static final int CMD_W_R = 0xC0;
    private static final int CMD_W_BR = 0x80;
    private static final int CMD_WB_E = 0x90;
    private static final int R_INIT = 0xA1;
    private static final int R_C1 = 0x01;
    private static final int R_C2 = 0x02;
    private static final int R_C4 = 0x04;
    private static final int R_UP_O = 0x9D;
    private static final int CMD_VER = 0x96;

    private final UsbManager usbManager;
    private final UsbDevice usbDevice;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<UsbInterface> claimedInterfaces = new ArrayList<>();
    private final DistanceParser distanceParser;
    private final SecureRandom random = new SecureRandom();

    private UsbDeviceConnection connection;
    private UsbEndpoint dataIn;
    private UsbEndpoint statusIn;
    private UsbEndpoint dataOut;
    private UsbEndpoint configOut;
    private Thread dataThread;
    private Thread statusThread;
    private int logicalGroup;
    private String endpointSummary = "";

    Ch348Device(UsbManager usbManager, UsbDevice usbDevice, int logicalGroup, Listener listener) {
        this.usbManager = usbManager;
        this.usbDevice = usbDevice;
        this.logicalGroup = logicalGroup;
        this.listener = listener;
        this.distanceParser = new DistanceParser(
                (port, line, distance) -> listener.onLine(this, port, line, distance));
    }

    UsbDevice getUsbDevice() {
        return usbDevice;
    }

    int getLogicalGroup() {
        return logicalGroup;
    }

    void setLogicalGroup(int logicalGroup) {
        this.logicalGroup = logicalGroup;
    }

    String getDisplayName() {
        return String.format(Locale.US, "CH348-%d (%s)", logicalGroup + 1, usbDevice.getDeviceName());
    }

    String getEndpointSummary() {
        return endpointSummary;
    }

    synchronized void open() throws IOException {
        if (running.get()) return;
        if (!usbManager.hasPermission(usbDevice)) throw new IOException("没有USB访问权限");

        connection = usbManager.openDevice(usbDevice);
        if (connection == null) throw new IOException("无法打开USB设备");

        try {
            UsbInterface serialInterface = null;
            List<UsbEndpoint> bulkEndpoints = null;
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                UsbInterface intf = usbDevice.getInterface(i);
                List<UsbEndpoint> candidates = new ArrayList<>();
                for (int j = 0; j < intf.getEndpointCount(); j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) candidates.add(ep);
                }
                if (candidates.size() >= 4) {
                    serialInterface = intf;
                    bulkEndpoints = candidates;
                    break;
                }
            }

            if (serialInterface == null || bulkEndpoints == null) {
                throw new IOException("没有找到CH348的4个Bulk端点");
            }
            if (!connection.claimInterface(serialInterface, true)) {
                throw new IOException("无法占用USB接口 " + serialInterface.getId());
            }
            claimedInterfaces.add(serialInterface);

            // WCH's official driver uses descriptors 0/1 as the UART data pair and
            // descriptors 2/3 as the command/status pair. Endpoint numbers are not
            // a reliable way to distinguish the pairs (the command pair is commonly EP1).
            UsbEndpoint first = bulkEndpoints.get(0);
            UsbEndpoint second = bulkEndpoints.get(1);
            UsbEndpoint third = bulkEndpoints.get(2);
            UsbEndpoint fourth = bulkEndpoints.get(3);
            if (first.getDirection() != UsbConstants.USB_DIR_IN) {
                UsbEndpoint swap = first;
                first = second;
                second = swap;
            }
            if (third.getDirection() != UsbConstants.USB_DIR_IN) {
                UsbEndpoint swap = third;
                third = fourth;
                fourth = swap;
            }
            if (first.getDirection() != UsbConstants.USB_DIR_IN ||
                    second.getDirection() != UsbConstants.USB_DIR_OUT ||
                    third.getDirection() != UsbConstants.USB_DIR_IN ||
                    fourth.getDirection() != UsbConstants.USB_DIR_OUT) {
                throw new IOException("CH348端点方向异常：" + describeEndpoints(bulkEndpoints));
            }
            dataIn = first;
            dataOut = second;
            statusIn = third;
            configOut = fourth;
            endpointSummary = String.format(Locale.US,
                    "data IN/OUT=%02X/%02X, config IN/OUT=%02X/%02X",
                    dataIn.getAddress(), dataOut.getAddress(), statusIn.getAddress(), configOut.getAddress());

            running.set(true);
            statusThread = new Thread(this::statusLoop, "ch348-status-" + logicalGroup);
            statusThread.start();
            // The chip reports configuration acknowledgements/status on the second IN
            // endpoint, so drain it before sending the per-port setup commands.
            configureAllPorts();
            dataThread = new Thread(this::dataLoop, "ch348-data-" + logicalGroup);
            dataThread.start();
        } catch (IOException | RuntimeException error) {
            close();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException(error);
        }
    }

    private static String describeEndpoints(List<UsbEndpoint> endpoints) {
        StringBuilder result = new StringBuilder();
        for (UsbEndpoint endpoint : endpoints) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X/%s/%d",
                    endpoint.getAddress(),
                    endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                    endpoint.getMaxPacketSize()));
        }
        return result.toString();
    }

    private void configureAllPorts() throws IOException {
        // Querying the chip version is also a useful sanity check for endpoint 0.
        // Older firmware can still work if the request is not implemented.
        byte[] version = new byte[4];
        int versionLength = connection.controlTransfer(
                UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR |
                        0x00, // USB_RECIP_DEVICE
                CMD_VER, 0, 0, version, version.length, IO_TIMEOUT_MS);
        if (versionLength == 4 && (version[0] & 0xFF) >= 0x8A) {
            byte[] uploadMode = new byte[8];
            uploadMode[0] = (byte) CMD_WB_E;
            uploadMode[1] = (byte) R_UP_O;
            uploadMode[2] = 0x01;
            bulkWrite(configOut, uploadMode, "设置CH348上传模式失败");
        }

        for (int port = 0; port < PORT_COUNT; port++) {
            // FIFO and normal receive mode must be configured before line coding.
            portConfig(port, CMD_W_R, R_C2, 0x87);
            portConfig(port, CMD_W_R, R_C4, 0x08);

            byte[] init = new byte[12];
            init[0] = (byte) (CMD_WB_E | port);
            init[1] = (byte) R_INIT;
            int rotate = random.nextInt(16);
            int xor = random.nextInt(256);
            init[2] = (byte) (port | (rotate << 4));
            init[3] = (byte) ((BAUD_RATE >>> 24) & 0xFF);
            init[4] = (byte) ((BAUD_RATE >>> 16) & 0xFF);
            init[5] = (byte) ((BAUD_RATE >>> 8) & 0xFF);
            init[6] = (byte) (BAUD_RATE & 0xFF);
            init[7] = 0; // one stop bit
            init[8] = 0; // no parity
            init[9] = 8; // eight data bits
            init[10] = receiveTimeout(BAUD_RATE);
            init[11] = (byte) xor;
            encodeLineCoding(init, 3, rotate, xor);
            bulkWrite(configOut, init, "设置115200/8N1失败，端口" + (port + 1));

            // Enable receive, transmit-empty, line-status and modem-status interrupts.
            portConfig(port, CMD_W_R, R_C1, 0x0F);
        }
    }

    private static byte receiveTimeout(int baudRate) {
        if (baudRate >= 921600) return 5;
        int delay = 1_000_000 * 15 / baudRate;
        return (byte) (delay / 100 + 1);
    }

    /** Implements cal_outdata() from WCH's official CH9344/CH348 Linux driver. */
    private static void encodeLineCoding(byte[] command, int offset, int rotate, int xor) {
        for (int bit = 0; bit < rotate; bit++) {
            int high = command[offset] & 0x80;
            for (int i = 0; i < 7; i++) {
                int nextHigh = command[offset + i + 1] & 0x80;
                command[offset + i] = (byte) (((command[offset + i] & 0xFF) << 1) |
                        (nextHigh == 0 ? 0 : 1));
            }
            command[offset + 7] = (byte) (((command[offset + 7] & 0xFF) << 1) |
                    (high == 0 ? 0 : 1));
        }
        for (int i = 0; i < 8; i++) command[offset + i] ^= (byte) xor;
    }

    private void portConfig(int port, int action, int register, int control) throws IOException {
        int mappedRegister = register;
        if (port < 4) mappedRegister += 0x10 * port;
        else mappedRegister += 0x10 * (port - 4) + 0x08;
        byte[] command = new byte[]{(byte) action, (byte) mappedRegister, (byte) control};
        bulkWrite(configOut, command, "配置端口" + (port + 1) + "失败");
    }

    private void bulkWrite(UsbEndpoint endpoint, byte[] payload, String message) throws IOException {
        int result = connection.bulkTransfer(endpoint, payload, payload.length, IO_TIMEOUT_MS);
        if (result != payload.length) {
            throw new IOException(message + "，端点=" +
                    String.format(Locale.US, "%02X", endpoint.getAddress()) + "，返回=" + result);
        }
    }

    private void dataLoop() {
        byte[] readBuffer = new byte[16 * 1024];
        FrameDecoder decoder = new FrameDecoder();
        while (running.get()) {
            int count = connection.bulkTransfer(dataIn, readBuffer, readBuffer.length, 250);
            if (count > 0) {
                decoder.accept(readBuffer, count);
            } else if (count < 0 && running.get()) {
                // Android returns -1 on timeout as well as on some transient USB errors.
                Thread.yield();
            }
        }
    }

    private void statusLoop() {
        byte[] buffer = new byte[2048];
        while (running.get()) {
            int count = connection.bulkTransfer(statusIn, buffer, buffer.length, 500);
            if (count < 0 && running.get()) Thread.yield();
        }
    }

    private final class FrameDecoder {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        synchronized void accept(byte[] data, int length) {
            pending.write(data, 0, length);
            byte[] all = pending.toByteArray();
            int offset = 0;
            while (all.length - offset >= 32) {
                int port = all[offset] & 0xFF;
                int dataLength = all[offset + 1] & 0xFF;
                if (port >= PORT_COUNT || dataLength > 30) {
                    offset++;
                    continue;
                }
                if (dataLength > 0) distanceParser.accept(port, all, offset + 2, dataLength);
                offset += 32;
            }
            pending.reset();
            if (offset < all.length) pending.write(all, offset, all.length - offset);
            if (pending.size() > 4096) pending.reset();
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (dataThread != null) dataThread.interrupt();
        if (statusThread != null) statusThread.interrupt();
        if (connection != null) {
            for (UsbInterface intf : claimedInterfaces) {
                try {
                    connection.releaseInterface(intf);
                } catch (Exception ignored) {
                }
            }
            connection.close();
        }
        claimedInterfaces.clear();
        connection = null;
        dataThread = null;
        statusThread = null;
    }
}

package com.jinle.serialmonitor;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity implements Ch348Device.Listener {
    private static final String ACTION_USB_PERMISSION = "com.jinle.serialmonitor.USB_PERMISSION";
    private static final int CHANNEL_COUNT = 16;
    private static final char[] CHANNEL_NAMES = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();
    private final List<Ch348Device> openedDevices = new ArrayList<>();
    private final TextView[] distanceViews = new TextView[CHANNEL_COUNT];
    private final TextView[] stateViews = new TextView[CHANNEL_COUNT];
    private final long[] receiveCounts = new long[CHANNEL_COUNT];
    private final AtomicLong recordSequence = new AtomicLong();
    private final AtomicBoolean connectionInProgress = new AtomicBoolean(false);
    private final Object recordLock = new Object();

    private UsbManager usbManager;
    private PendingIntent permissionIntent;
    private TextView overallStatus;
    private TextView recordStatus;
    private Button measureButton;
    private Button resetButton;
    private View liveView;
    private LinearLayout resultPanel;
    private BodyContourView contourView;
    private TextView waistResultView;
    private TextView hipResultView;
    private TextView ratioResultView;
    private TextView qualityResultView;
    private boolean groupsSwapped;
    private volatile boolean recording;
    private volatile boolean phaseMeasuring;
    private volatile int activePhase = -1;
    private int nextPhase;
    private long phaseEndsAt;
    private MeasurementSession measurementSession = new MeasurementSession();
    private int[] phaseAttempts = new int[MeasurementSession.PHASE_COUNT];
    private BufferedWriter recordWriter;
    private Uri recordUri;
    private long recordCount;
    private boolean permissionRequestInFlight;
    private boolean destroyed;

    private final Runnable measurementTicker = new Runnable() {
        @Override
        public void run() {
            if (!phaseMeasuring || destroyed) return;
            long remaining = phaseEndsAt - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                finishMeasurementPhase();
                return;
            }
            double seconds = remaining / 1000.0;
            String phaseName = MeasurementSession.PHASE_NAMES[activePhase];
            measureButton.setText(String.format(Locale.CHINA, "%s测量中 · %.1f秒", phaseName, seconds));
            recordStatus.setText(String.format(Locale.CHINA, "%s采集中 · %.1f秒 · %,d条",
                    phaseName, seconds, measurementSession.getPhaseSampleCount(activePhase)));
            mainHandler.postDelayed(this, 100);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                permissionRequestInFlight = false;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (!granted) setOverallStatus("USB权限被拒绝，请点击重新连接", false);
                else scanAndConnect();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                scanAndConnect();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isSupportedDevice(detached)) handleDetach(detached);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        groupsSwapped = getPreferences(MODE_PRIVATE).getBoolean("groupsSwapped", false);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        permissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);

        buildUi();
        registerUsbReceiver();
        scanAndConnect();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // USB attach/permission broadcasts originate from the Android system.
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(14), dp(22), dp(14));
        header.setBackgroundColor(Color.rgb(12, 34, 58));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("腰臀围四方向测量", 24, Color.WHITE, true);
        TextView subtitle = text("16层 · 635–1235 mm · 层间距40 mm · 每方向10秒", 13,
                Color.rgb(176, 196, 219), false);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        overallStatus = text("正在检测USB设备…", 14, Color.WHITE, true);
        overallStatus.setPadding(dp(14), dp(8), dp(14), dp(8));
        overallStatus.setBackground(roundRect(Color.rgb(55, 72, 96), 18));
        header.addView(overallStatus);
        root.addView(header);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(18), dp(10), dp(18), dp(10));
        controls.setBackgroundColor(Color.WHITE);

        Button reconnect = button("重新连接", Color.rgb(32, 95, 145));
        reconnect.setOnClickListener(v -> scanAndConnect());
        controls.addView(reconnect);

        Button swap = button("交换两组", Color.rgb(104, 82, 151));
        swap.setOnClickListener(v -> swapGroups());
        controls.addView(swap);

        measureButton = button("开始正面测量（10秒）", Color.rgb(10, 124, 102));
        measureButton.setOnClickListener(v -> startMeasurementPhase());
        controls.addView(measureButton);

        resetButton = button("重新测量", Color.rgb(190, 72, 64));
        resetButton.setOnClickListener(v -> resetMeasurement());
        controls.addView(resetButton);

        recordStatus = text("准备测量：请正对传感器站立", 13, Color.rgb(76, 91, 110), false);
        controls.addView(recordStatus, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(controls);

        ScrollView scroll = new ScrollView(this);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setPadding(dp(12), dp(10), dp(12), dp(16));
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            grid.addView(makeChannelCard(i), new GridLayout.LayoutParams(
                    GridLayout.spec(i / 4, 1f), GridLayout.spec(i % 4, 1f)) {{
                width = 0;
                height = dp(126);
                setMargins(dp(6), dp(6), dp(6), dp(6));
            }});
        }
        scroll.addView(grid);
        liveView = scroll;
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        resultPanel = makeResultPanel();
        resultPanel.setVisibility(View.GONE);
        root.addView(resultPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private LinearLayout makeResultPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackgroundColor(Color.rgb(238, 243, 249));

        contourView = new BodyContourView(this);
        panel.addView(contourView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.VERTICAL);
        metrics.setPadding(dp(22), dp(18), dp(22), dp(18));
        metrics.setBackground(roundRect(Color.WHITE, 10));
        TextView heading = text("测量结果", 20, Color.rgb(19, 49, 76), true);
        waistResultView = text("腰围 -- cm", 30, Color.rgb(226, 112, 56), true);
        hipResultView = text("臀围 -- cm", 30, Color.rgb(120, 82, 170), true);
        ratioResultView = text("腰臀比 --", 20, Color.rgb(31, 93, 140), true);
        qualityResultView = text("", 13, Color.rgb(90, 105, 124), false);
        qualityResultView.setPadding(0, dp(14), 0, 0);
        metrics.addView(heading);
        metrics.addView(waistResultView);
        metrics.addView(hipResultView);
        metrics.addView(ratioResultView);
        metrics.addView(qualityResultView);
        panel.addView(metrics, new LinearLayout.LayoutParams(dp(310),
                LinearLayout.LayoutParams.MATCH_PARENT));
        return panel;
    }

    private View makeChannelCard(int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(roundRect(Color.WHITE, 10));

        TextView label = text(String.format(Locale.CHINA, "第%02d层  ·  设备%d-%c",
                index + 1, index / 8 + 1, CHANNEL_NAMES[index % 8]),
                14, Color.rgb(67, 82, 103), true);
        distanceViews[index] = text("-- mm", 28, Color.rgb(16, 43, 70), true);
        stateViews[index] = text("等待数据", 12, Color.rgb(139, 151, 166), false);
        card.addView(label);
        card.addView(distanceViews[index]);
        card.addView(stateViews[index]);
        return card;
    }

    private void scanAndConnect() {
        if (destroyed) return;
        setOverallStatus("正在检测并连接…", true);
        List<UsbDevice> found = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isSupportedDevice(device)) found.add(device);
        }
        found.sort(Comparator.comparing(UsbDevice::getDeviceName));

        if (found.isEmpty()) {
            setOverallStatus("未检测到CH348设备", false);
            markAllWaiting("请连接带独立供电的USB Hub");
            return;
        }
        if (found.size() < 2) {
            setOverallStatus("仅检测到1片CH348（8路）", false);
        }

        UsbDevice permissionMissing = null;
        for (UsbDevice device : found) {
            if (!usbManager.hasPermission(device)) {
                permissionMissing = device;
                break;
            }
        }
        if (permissionMissing != null) {
            setOverallStatus("请允许USB访问权限", true);
            // Android may deliver one permission result for each device. Request them
            // sequentially so two callbacks cannot queue two competing reconnects.
            if (!permissionRequestInFlight) {
                permissionRequestInFlight = true;
                usbManager.requestPermission(permissionMissing, permissionIntent);
            }
            return;
        }

        if (!connectionInProgress.compareAndSet(false, true)) return;
        final List<UsbDevice> devicesToOpen = found.size() > 2
                ? new ArrayList<>(found.subList(0, 2)) : found;
        usbExecutor.execute(() -> openDevices(devicesToOpen));
    }

    private static boolean isSupportedDevice(UsbDevice device) {
        return device != null && device.getVendorId() == Ch348Device.VENDOR_ID &&
                device.getProductId() == Ch348Device.PRODUCT_ID;
    }

    private void openDevices(List<UsbDevice> devices) {
        closeOpenedDevices();
        List<Ch348Device> newlyOpened = new ArrayList<>();
        try {
            for (int i = 0; i < devices.size(); i++) {
                int group = groupsSwapped && devices.size() == 2 ? 1 - i : i;
                Ch348Device device = new Ch348Device(usbManager, devices.get(i), group, this);
                device.open();
                newlyOpened.add(device);
            }
            synchronized (openedDevices) {
                openedDevices.addAll(newlyOpened);
            }
            runOnUiThread(() -> {
                setOverallStatus(newlyOpened.size() == 2 ? "全部在线 · 16/16" : "部分在线 · 8/16", true);
                for (Ch348Device device : newlyOpened) {
                    int first = device.getLogicalGroup() * 8;
                    for (int i = first; i < first + 8; i++) stateViews[i].setText("已连接，等待数据");
                }
            });
        } catch (Exception error) {
            for (Ch348Device device : newlyOpened) device.close();
            runOnUiThread(() -> setOverallStatus("连接失败：" + error.getMessage(), false));
        } finally {
            connectionInProgress.set(false);
        }
    }

    private void swapGroups() {
        groupsSwapped = !groupsSwapped;
        getPreferences(MODE_PRIVATE).edit().putBoolean("groupsSwapped", groupsSwapped).apply();
        Toast.makeText(this, groupsSwapped ? "已交换设备1和设备2" : "已恢复默认分组", Toast.LENGTH_SHORT).show();
        scanAndConnect();
    }

    @Override
    public void onLine(Ch348Device source, int port, String raw, Double distanceMm) {
        int index = source.getLogicalGroup() * 8 + port;
        if (index < 0 || index >= CHANNEL_COUNT) return;
        long count = ++receiveCounts[index];

        int phase = activePhase;
        if (phaseMeasuring && phase >= 0 && distanceMm != null) {
            measurementSession.addSample(phase, index, distanceMm);
        }
        if (recording && phaseMeasuring && phase >= 0) {
            writeRecord(phase, index, port, raw, distanceMm);
        }
        mainHandler.post(() -> {
            if (distanceMm != null) {
                String value = Math.rint(distanceMm) == distanceMm
                        ? String.format(Locale.CHINA, "%.0f mm", distanceMm)
                        : String.format(Locale.CHINA, "%.1f mm", distanceMm);
                distanceViews[index].setText(value);
                distanceViews[index].setTextColor(Color.rgb(7, 120, 95));
                stateViews[index].setText(String.format(Locale.CHINA, "实时接收 · %,d条", count));
            } else {
                stateViews[index].setText(raw.length() > 36 ? raw.substring(0, 36) + "…" : raw);
            }
        });
    }

    @Override
    public void onError(Ch348Device source, String message, Throwable error) {
        runOnUiThread(() -> setOverallStatus(message, false));
    }

    private void startMeasurementPhase() {
        if (phaseMeasuring || nextPhase >= MeasurementSession.PHASE_COUNT) return;
        int connected;
        synchronized (openedDevices) {
            connected = openedDevices.size();
        }
        if (connected < 2) {
            Toast.makeText(this, "需要两片CH348全部在线后才能进行16层测量", Toast.LENGTH_LONG).show();
            return;
        }
        if (nextPhase == MeasurementSession.PHASE_FRONT && !recording && !startRecording()) return;

        liveView.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        activePhase = nextPhase;
        phaseAttempts[activePhase]++;
        phaseMeasuring = true;
        phaseEndsAt = SystemClock.elapsedRealtime() + 10_000L;
        measureButton.setEnabled(false);
        resetButton.setEnabled(false);
        markAllWaiting(MeasurementSession.PHASE_NAMES[activePhase] + "测量中");
        mainHandler.removeCallbacks(measurementTicker);
        mainHandler.post(measurementTicker);
    }

    private void finishMeasurementPhase() {
        if (!phaseMeasuring || activePhase < 0) return;
        int completedPhase = activePhase;
        phaseMeasuring = false;
        activePhase = -1;
        mainHandler.removeCallbacks(measurementTicker);
        resetButton.setEnabled(true);

        int validLayers = measurementSession.getPhaseValidLayerCount(completedPhase);
        int sampleCount = measurementSession.getPhaseSampleCount(completedPhase);
        if (validLayers < 12) {
            measurementSession.clearPhase(completedPhase);
            nextPhase = completedPhase;
            measureButton.setEnabled(true);
            measureButton.setText("重新测量" + MeasurementSession.PHASE_NAMES[completedPhase] + "（10秒）");
            recordStatus.setText(String.format(Locale.CHINA,
                    "%s数据不足：%,d条/%d层有效，至少需要12层，请检查串口后重测",
                    MeasurementSession.PHASE_NAMES[completedPhase], sampleCount, validLayers));
            Toast.makeText(this, "有效数据不足，请保持站位并重测本方向", Toast.LENGTH_LONG).show();
            return;
        }

        nextPhase = completedPhase + 1;
        if (nextPhase < MeasurementSession.PHASE_COUNT) {
            String nextName = MeasurementSession.PHASE_NAMES[nextPhase];
            measureButton.setEnabled(true);
            measureButton.setText("开始" + nextName + "测量（10秒）");
            recordStatus.setText(String.format(Locale.CHINA,
                    "%s完成 · %,d条/%d层有效；请转身至%s后再按按钮",
                    MeasurementSession.PHASE_NAMES[completedPhase], sampleCount, validLayers, nextName));
            Toast.makeText(this, "本方向测量完成，请转身至" + nextName, Toast.LENGTH_LONG).show();
            return;
        }

        MeasurementSession.Result result = measurementSession.calculate();
        appendMeasurementSummary(result);
        stopRecording();
        showMeasurementResult(result);
        measureButton.setText("四方向测量已完成");
        measureButton.setEnabled(false);
    }

    private void resetMeasurement() {
        if (phaseMeasuring) {
            Toast.makeText(this, "当前10秒测量尚未结束", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recording || recordWriter != null) stopRecording();
        measurementSession = new MeasurementSession();
        phaseAttempts = new int[MeasurementSession.PHASE_COUNT];
        nextPhase = 0;
        activePhase = -1;
        liveView.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        measureButton.setEnabled(true);
        resetButton.setEnabled(true);
        measureButton.setText("开始正面测量（10秒）");
        recordStatus.setText("准备测量：请正对传感器站立");
    }

    private void showMeasurementResult(MeasurementSession.Result result) {
        contourView.setResult(result);
        liveView.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);
        if (result.hasMeasurement()) {
            waistResultView.setText(String.format(Locale.CHINA, "腰围 %.1f cm", result.waistCm()));
            hipResultView.setText(String.format(Locale.CHINA, "臀围 %.1f cm", result.hipCm()));
            ratioResultView.setText(String.format(Locale.CHINA, "腰臀比 %.2f", result.waistHipRatio()));
        } else {
            waistResultView.setText("腰围 -- cm");
            hipResultView.setText("臀围 -- cm");
            ratioResultView.setText("腰臀比 --");
        }
        String quality = "有效轮廓：" + result.validLayers + "/16层";
        if (result.waistLayer >= 0) {
            quality += "\n腰部：第" + (result.waistLayer + 1) + "层 / " +
                    Math.round(result.heightsMm[result.waistLayer]) + " mm";
        }
        if (result.hipLayer >= 0) {
            quality += "\n臀部：第" + (result.hipLayer + 1) + "层 / " +
                    Math.round(result.heightsMm[result.hipLayer]) + " mm";
        }
        if (result.warning != null) quality += "\n提示：" + result.warning;
        qualityResultView.setText(quality);
        recordStatus.setText(result.summary() + " · 数据已保存到 下载/串口距离记录");
    }

    private boolean startRecording() {
        if (recording) return true;
        try {
            String filename = "腰臀围四方向测量_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/串口距离记录");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            recordUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (recordUri == null) throw new IOException("不能创建记录文件");
            OutputStream stream = resolver.openOutputStream(recordUri, "w");
            if (stream == null) throw new IOException("不能打开记录文件");
            recordWriter = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
            recordWriter.write('\uFEFF');
            recordWriter.write("\"时间\",\"序号\",\"方向\",\"层号\",\"设备\",\"通道\",\"距离_mm\",\"原始数据\"\n");
            recordSequence.set(0);
            recordCount = 0;
            recording = true;
            return true;
        } catch (Exception error) {
            Toast.makeText(this, "开始记录失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            abandonRecord();
            return false;
        }
    }

    private void writeRecord(int phase, int index, int port, String raw, Double distanceMm) {
        synchronized (recordLock) {
            if (!recording || recordWriter == null) return;
            try {
                long sequence = recordSequence.incrementAndGet();
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String row = csv(timestamp) + ',' + sequence + ',' +
                        csv(MeasurementSession.PHASE_NAMES[phase] + "-第" + phaseAttempts[phase] + "次") + ',' +
                        (index + 1) + ',' +
                        csv("设备" + (index / 8 + 1)) + ',' + csv(String.valueOf(CHANNEL_NAMES[port])) + ',' +
                        (distanceMm == null ? "" : distanceMm) + ',' + csv(raw) + '\n';
                recordWriter.write(row);
                recordCount++;
                if (recordCount % 50 == 0) recordWriter.flush();
            } catch (IOException error) {
                recording = false;
                mainHandler.post(() -> Toast.makeText(this, "记录写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    private void appendMeasurementSummary(MeasurementSession.Result result) {
        synchronized (recordLock) {
            if (recordWriter == null) return;
            try {
                recordWriter.write("\n\"汇总层号\",\"高度_mm\",\"正面中位数_mm\",\"左侧中位数_mm\",\"背面中位数_mm\",\"右侧中位数_mm\",\"左右径_mm\",\"前后径_mm\",\"椭圆周长_mm\"\n");
                for (int layer = 0; layer < MeasurementSession.LAYER_COUNT; layer++) {
                    recordWriter.write((layer + 1) + "," + result.heightsMm[layer] + "," +
                            number(result.medianDistancesMm[MeasurementSession.PHASE_FRONT][layer]) + "," +
                            number(result.medianDistancesMm[MeasurementSession.PHASE_LEFT][layer]) + "," +
                            number(result.medianDistancesMm[MeasurementSession.PHASE_BACK][layer]) + "," +
                            number(result.medianDistancesMm[MeasurementSession.PHASE_RIGHT][layer]) + "," +
                            number(result.widthsMm[layer]) + "," + number(result.depthsMm[layer]) + "," +
                            number(result.circumferencesMm[layer]) + "\n");
                }
                recordWriter.write("\n\"计算结果\",\"腰围_cm\",\"臀围_cm\",\"腰臀比\"\n");
                recordWriter.write("\"result\"," + number(result.waistCm()) + "," +
                        number(result.hipCm()) + "," + number(result.waistHipRatio()) + "\n");
                recordWriter.flush();
            } catch (IOException error) {
                Toast.makeText(this, "汇总写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : "";
    }

    private void stopRecording() {
        Uri completedUri;
        synchronized (recordLock) {
            if (!recording && recordWriter == null) return;
            recording = false;
            try {
                if (recordWriter != null) {
                    recordWriter.flush();
                    recordWriter.close();
                }
            } catch (IOException ignored) {
            }
            completedUri = recordUri;
            recordWriter = null;
            recordUri = null;
        }
        if (completedUri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(completedUri, done, null, null);
        }
        recordStatus.setText(String.format(Locale.CHINA, "数据已保存 · %,d条 · 下载/串口距离记录", recordCount));
        Toast.makeText(this, "记录已保存到 下载/串口距离记录", Toast.LENGTH_LONG).show();
    }

    private void abandonRecord() {
        synchronized (recordLock) {
            recording = false;
            try {
                if (recordWriter != null) recordWriter.close();
            } catch (IOException ignored) {
            }
            if (recordUri != null) getContentResolver().delete(recordUri, null, null);
            recordWriter = null;
            recordUri = null;
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void handleDetach(UsbDevice detached) {
        boolean removed = false;
        synchronized (openedDevices) {
            for (int i = openedDevices.size() - 1; i >= 0; i--) {
                Ch348Device opened = openedDevices.get(i);
                if (opened.getUsbDevice().getDeviceId() == detached.getDeviceId()) {
                    opened.close();
                    openedDevices.remove(i);
                    removed = true;
                }
            }
        }
        permissionRequestInFlight = false;
        if (removed || isSupportedDevice(detached)) {
            setOverallStatus("CH348已断开，等待设备恢复", false);
            mainHandler.postDelayed(this::scanAndConnect, 1000);
        }
    }

    private void closeOpenedDevices() {
        synchronized (openedDevices) {
            for (Ch348Device device : openedDevices) device.close();
            openedDevices.clear();
        }
    }

    private void markAllWaiting(String message) {
        for (TextView state : stateViews) state.setText(message);
    }

    private void setOverallStatus(String text, boolean positive) {
        runOnUiThread(() -> {
            overallStatus.setText(text);
            overallStatus.setBackground(roundRect(
                    positive ? Color.rgb(20, 111, 85) : Color.rgb(174, 67, 61), 18));
        });
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String title, int color) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMargins(0, 0, dp(10), 0);
        button.setLayoutParams(params);
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        phaseMeasuring = false;
        activePhase = -1;
        mainHandler.removeCallbacksAndMessages(null);
        if (recording || recordWriter != null) stopRecording();
        closeOpenedDevices();
        unregisterReceiver(usbReceiver);
        usbExecutor.shutdownNow();
        super.onDestroy();
    }
}

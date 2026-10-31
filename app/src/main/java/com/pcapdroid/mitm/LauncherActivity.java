package com.pcapdroid.mitm;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * MitmLauncher - 最小 UI：参数输入框 + 启动/停止切换按钮 + 运行状态 + 环境信息 + 日志尾巴。
 *
 * 状态来源（重要）：
 *   界面的 running 只由 Python 报上来的状态决定，不在这里乐观置位。
 *   ★ M2b 起服务跑在 :mitm 独立进程 ⇒ **静态回调跨不了进程**，状态唯一来源是
 *   轮询 status.txt（{@code seq|state|detail|pid}）。文件还有个不可替代的好处：
 *   进程被 killProcess 兜底杀掉时，它是唯一还能留下痕迹的通道。
 *   点按钮 → 发 Intent 给 LauncherService → Python 报状态 → 这里轮询读文件更新。
 *   这样「点了启动但端口被占」会正确显示成「启动失败」，而不是骗用户的「运行中」。
 */
public class LauncherActivity extends Activity {

    private static final String TAG = "MitmLauncherUI";

    private static final String DEFAULT_ARGS = "-p 8080 --set connection_strategy=lazy";
    private static final long LOG_POLL_MS = 700;
    private static final int LOG_TAIL_LINES = 80;

    private EditText argsBox;
    private Button toggleButton;
    private TextView statusView;
    private TextView infoView;
    private TextView logView;
    /** statusView 的默认字色。出错时改成红色，恢复时切回来（不依赖主题资源）。 */
    private int defaultStatusColor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long logSize = -1;

    /** status.txt 里最后处理过的序号（Python 侧单调递增）。用它判重，不用 lastModified。 */
    private long lastSeq = -1;

    /**
     * 上一次**完整处理过**的 status.txt 原文，用它做真正的判重。
     *
     * ★ 为什么不用 seq 判重：LauncherService.writeStatusFile() 会**复用**文件里
     *   已有的 seq（故意的，见那边的注释），所以同一 seq 可能对应两份不同内容。
     *   原先写 `seq <= lastSeq → return`，结果 Java 侧写的那条 error 会被直接吞掉。
     *   整条内容才是可靠的「这条我处理过没有」。
     */
    private String lastStatusContent = "";

    /** 界面当前认定的状态，取值与 Python 侧 STATE_* 对齐。 */
    private String state = "idle";

    /** 与 state 配套的 detail，和 state 一起做「这条状态是不是新的」判重。 */
    private String lastDetail = "";

    /** 点了按钮但还没等到服务回状态的这段时间，按钮暂时不可点，防止连点。 */
    private boolean pending = false;

    /** pending 的开始时间，用来做看门狗（否则 Python 卡死时按钮永久锁死）。 */
    private long pendingSince = 0;

    /** ★ 本次 pending 是不是「启动」引起的。启动/停止的超时预算不一样。 */
    private boolean pendingForStart = false;

    /**
     * ★ 本次 pending 期间是否已经收到过至少一条状态。
     *   收到的瞬间就说明服务确实活着（哪怕报的是失败），后面再没动静就只能是卡死；
     *   而一条都没收到时，需要把「服务进程冷启动 + Python 解释器冷启动」的耗时算进去。
     *   所以在收到过之后可以把超时收紧。
     */
    private boolean pendingHeard = false;

    /**
     * 看门狗时间预算（两档）。
     *
     * 为什么不只用一个固定值：
     *   「从没收到过任何状态」包含 Python 注释器冷启动（首次要 import 整套
     *   mitmproxy，实测 1~3 秒，低端机器更久），必须给足；
     *   「已经收到过状态」说明解释器早就热了，后续每步都是毫秒级，
     *   没必要让用户白等。
     *
     * ★★ 这里的值必须**比 LauncherService 里那套看门狗更宽松**：
     *   服务侧 12000 / 3000，这里 15000 / 5000。
     *   原因：服务侧卡死时会先写一条很具体的 error（如
     *   「启动超时（12秒无响应），已自动重置」）并自杀，界面读得到；
     *   如果界面先超时，就会把它盖成笼统的「等待超时，请重试」，白白丢掉信息。
     *   界面超时只是最后一道傅底（服务进程连看门狗都没跑起来时）。
     */
    private static final long PENDING_TIMEOUT_START_COLD_MS = 15000;
    private static final long PENDING_TIMEOUT_START_WARM_MS = 5000;
    /** 停止：服务侧是 join(2000) + 写状态 + kill，这里给到 6s 让它的话先说。 */
    private static final long PENDING_TIMEOUT_STOP_MS = 6000;

    /**
     * 轮询任务：日志尾巴 + 状态文件 + 看门狗。
     *
     * ★ M2b 拆进程后这里**不再注册任何 Python 回调**：LauncherService 跑在 :mitm，
     * Activity 跑在主进程，静态字段是两份独立的副本，回调根本传不过来。
     * 界面一律读 status.txt（它是唯一跨进程、且能挺过 killProcess 的通道）。
     */
    private final Runnable logPoller = new Runnable() {
        @Override
        public void run() {
            tailLog();
            pollStatus();
            checkPendingTimeout();
            handler.postDelayed(this, LOG_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText("mitmproxy 参数（例如 -p 8080 -s /path/script.py）");
        root.addView(label);

        argsBox = new EditText(this);
        argsBox.setText(DEFAULT_ARGS);
        argsBox.setSingleLine(false);
        root.addView(argsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        // 单个按钮：启动 / 停止 二合一，靠文字与点击行为切换
        toggleButton = new Button(this);
        toggleButton.setOnClickListener(v -> onToggleClicked());
        buttons.addView(toggleButton);

        root.addView(buttons);

        statusView = new TextView(this);
        // 记下默认字色，出错时临时改成红色，恢复时切回来。
        defaultStatusColor = statusView.getCurrentTextColor();
        root.addView(statusView);

        infoView = new TextView(this);
        infoView.setTextSize(11);
        // 版本信息现在由 :mitm 子进程写入日志（launcher.py 的 version()），
        // 主进程不再为了显示它而把 libpython 再加载一份（省 60~120MB）。
        infoView.setText("Python 环境跑在后台 :mitm 进程里");
        root.addView(infoView);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setMovementMethod(new ScrollingMovementMethod());
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        refreshButton();
        requestNotificationPermission();
        resetStaleState();
        handler.post(logPoller);
        // ★ 删掉了 loadEnvInfo()：它会在主进程里 `Python.getInstance()`，
        //   等于把 libpython + mitmproxy 整个再加载一份（约 60~120MB），
        //   而真正干活的 :mitm 子进程自己会冷启动，主进程这份预热纯属浪费。
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(logPoller);
        super.onDestroy();
    }

    // ------------------------------------------------------------ buttons
    /** 单按钮：未运行 → 启动；运行中 → 停止。 */
    private void onToggleClicked() {
        if (pending) {
            return;
        }
        if ("running".equals(state)) {
            stopMitm();
        } else {
            startMitm();
        }
    }

    private void startMitm() {
        String args = argsBox.getText().toString().trim();
        if (args.isEmpty()) {
            showStatus("参数不能为空", true);
            return;
        }
        // ★ 清掉上一次残留的 status.txt：避免轮询先读到旧的 error 闪一下，
        //   也避免 pendingHeard 被残留状态提前置真（会把冷启动预算 15s 缩到 5s）。
        //   代理真在跑时不删（那种情况本不该走到「启动」分支）。
        if (!LauncherService.isRunning(this)) {
            new File(getFilesDir(), "status.txt").delete();
        }
        // ★ 重置判重基线：否则「同一个错误再点一次」会因为 state+detail 没变而被吞掉，
        //   用户会觉得「点了没反应」。重置后同一条错误也会再报一次。
        stateApplied = false;
        lastDetail = "";
        lastStatusContent = "";
        setPending(true, true);
        showStatus("正在启动…", false);
        LauncherService.start(this, args);
    }

    private void stopMitm() {
        // ★ 同理：连点两次停止也要有反馈。
        stateApplied = false;
        lastDetail = "";
        lastStatusContent = "";
        setPending(true, false);
        showStatus("正在停止…", false);
        LauncherService.stop(this);
    }

    private void setPending(boolean value, boolean forStart) {
        pending = value;
        pendingSince = value ? System.currentTimeMillis() : 0;
        pendingForStart = value && forStart;
        pendingHeard = false;
        refreshButton();
    }

    /**
     * 看门狗：等太久就放手。
     *
     * 触发场景是「Python 侧彻底卡死，状态永远不变」——此时若一直禁用按钮，
     * 用户就再也点不动了，只能杀 App。宁可放开让他重试。
     *
     * ★ 这只管界面按钮，不负责杀内核：真正在 :mitm 里把卡死的进程清掉的是
     *   LauncherService 的看门狗。两边是互补的 —— 服务看门狗保证「下次能启动」，
     *   界面看门狗保证「现在就能再点」。
     */
    private void checkPendingTimeout() {
        if (!pending || pendingSince <= 0) {
            return;
        }
        long budget = pendingForStart
                ? (pendingHeard ? PENDING_TIMEOUT_START_WARM_MS : PENDING_TIMEOUT_START_COLD_MS)
                : PENDING_TIMEOUT_STOP_MS;
        if ((System.currentTimeMillis() - pendingSince) <= budget) {
            return;
        }
        // ★ 先存下来：setPending() 会把 pendingForStart 清掉，之后就读不到了。
        boolean wasStart = pendingForStart;
        setPending(false, false);
        // ★ 能走到这里说明连服务侧的看门狗都没能给出结论（最坏情况）。
        showStatus(wasStart
                ? "等待超时，请重试（若内核实际在跑，重启 App 可重新同步）"
                : "停止超时，已放弃等待（若仍在运行，重启 App 可重新同步）", true);
    }

    /**
     * 把 state 同步到按钮与状态行。
     *
     * 按钮文字只看「是否运行中」，pending 期间禁用但保持原文字，避免出现
     * 「启动 → 停止 → 启动」的闪烁。
     */
    private void refreshButton() {
        boolean isRunning = "running".equals(state);
        toggleButton.setText(isRunning ? "停止" : "启动");
        toggleButton.setEnabled(!pending);
    }

    /**
     * 回调与轮询两条通道可能同时送来同一条状态（回调先到，轮询又读到同一个
     * status.txt）。这里做一次幂等过滤：state + detail 都没变就什么都不做。
     * 用 stateApplied 而不是拿 state 的初值当哨兵，是为了让首次的 idle
     * 也能正常刷出「未运行」。
     *
     * M2b 拆进程后回调那条通道在界面上已经不存在了，但因为 Java 侧 service
     * 仍会主动写一条与 Python 同形的内容（如「上次未结束」），
     * 轮询依然可能在一帧内先后读到 starting 与 error，所以幂等过滤保留。
     *
     * ★ 这道过滤只防「重复通知」，真正的「这条读没读过」由 pollStatus() 里
     *   按整条内容做的 lastStatusContent 判定，两者职责不重叠。
     */
    private boolean stateApplied = false;

    /**
     * 状态行统一入口。
     *
     * ★ 为什么用字色而不是 Toast：
     *   Toast 会自己消失，且和系统弹框竞争；这里希望能「一直看得见」地告知失败，
     *   而红色恰好能把「失败」和「运行中/未运行」在视觉上彻底分开。
     *   也不引 androidx（工程没有依赖），直接 Color.rgb 写死一个暗红。
     */
    private void showStatus(String text, boolean isError) {
        statusView.setTextColor(isError ? Color.rgb(0xC6, 0x28, 0x28) : defaultStatusColor);
        statusView.setText(text);
    }

    private void applyState(String newState, String detail) {
        if (stateApplied && newState.equals(state) && detail.equals(lastDetail)) {
            return;
        }
        state = newState;
        lastDetail = detail;
        stateApplied = true;
        // ★ 收到了服务写下的状态 ⇒ 服务确实活着。后续再没动静就可以用短超时判定卡死。
        pendingHeard = true;
        // ★★ 无进展超时：只要还有新状态进来，就把等待时钟往后推。
        //    这样「边启动边推进」永远不会被误杀，只有真·无进展（Python 卡在某个
        //    C 调用里）才会超时。和服务侧看门狗的「成功即退休」构成双保险。
        if (pending) {
            pendingSince = System.currentTimeMillis();
        }
        // starting 是过渡态，按钮必须保持禁用；running / idle / error 都算已落定。
        // 不要在这里无条件 pending=false —— 否则 idle→starting 那一下就把按钮
        // 放开了，用户在启动过程中连点会重复发启动请求。
        boolean wasPending = pending;
        pending = "starting".equals(newState);
        if (pending && !wasPending) {
            pendingSince = System.currentTimeMillis();
        } else if (!pending) {
            pendingSince = 0;
        }
        refreshButton();

        String text;
        switch (newState) {
            case "running":
                text = detail.isEmpty() ? "运行中" : "运行中 · " + detail;
                break;
            case "starting":
                text = "正在启动…";
                break;
            case "stopping":
                text = "正在停止…";
                break;
            case "error":
                // Python 侧的 detail 本身已经是「启动失败（端口被占用或参数有误），退出码 1」，
                // 再拼一次前缀会变成「启动失败：启动失败（…）」，这里去重。
                text = detail.startsWith("启动失败") ? detail : "启动失败：" + detail;
                break;
            default:
                text = "未运行";
                break;
        }
        // ★ 失败用红色直接写在界面上，不再弹 Toast：
        //   Toast 一闪而过，且容易被用户当成普通提示忽略。
        showStatus(text, "error".equals(newState));
    }

    /**
     * 读 Python/Service 写的 status.txt。格式：{@code seq|state|detail|pid}
     *
     * ★★ 字段下标一定要对上：0=seq、1=state、2=detail、3=pid。
     *   曾经把 pid 写成 parts[2]、detail 写成 parts[3]（即 2↔3 写反），
     *   而 parts[2] 在运行中是 **监听地址**（如 ":8080, 0.0.0.0:8080"）,
     *   Integer.parseInt 直接抛 NumberFormatException。抛出点又在一个
     *   `catch { return; }` 里 ⇒ **每一条状态都被静默丢掉**，界面永远停在
     *   「正在启动…」，12 秒后报「等待超时」—— 而内核其实已经跑起来了。
     *   所以：下标必须准，且解析失败绝不能再静默丢弃。
     *
     * 用文件而不是回调，是因为服务在 :mitm 独立进程里、并且最后可能被 killProcess
     * 兜底杀掉：静态变量/回调都传不回来，文件是唯一还活着的通道。
     *
     * ★ 变更检测用**整条内容**，不用 lastModified，也不用 seq：
     *   ① lastModified 只有「秒」精度，同一秒内的「starting → error」会被整段吞掉；
     *   ② seq 会被 LauncherService.writeStatusFile() **复用**（见那边的注释），
     *      同一 seq 可能对应两份不同内容，拿它判重会把 Java 写的那条吞掉。
     *
     * ★ 第 4 个字段 pid 用来剔除「幻影状态」：进程被杀时来不及写 idle，
     *   status.txt 会留下一个 running。若写这条状态的进程已经不在（且心跳也停了），
     *   那它就不是当前真实状态。
     */
    private void pollStatus() {
        File f = new File(getFilesDir(), "status.txt");
        if (!f.exists()) {
            return;
        }

        String content;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            content = new String(buf, "UTF-8").trim();
        } catch (Throwable t) {
            return;
        }
        if (content.isEmpty()) {
            return;
        }

        // 整条内容没变 = 没有新状态（对旧格式也自然适用，无需特判）。
        if (content.equals(lastStatusContent)) {
            return;
        }
        lastStatusContent = content;

        String[] parts = content.split("\\|", 4);
        String newState;
        String detail = "";
        int pid = -1;
        if (parts.length >= 4) {
            newState = parts[1];
            detail = parts[2];          // ★ 2 是 detail
            try {
                lastSeq = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException ignored) {
                // seq 只用于诊断，解不出来也不影响状态
            }
            try {
                pid = Integer.parseInt(parts[3].trim());   // ★ 3 才是 pid
            } catch (NumberFormatException e) {
                // ★ 解析不了 pid 也不能丢状态，降级成「不做幻影判定」。
                Log.w(TAG, "status pid parse failed: " + content);
                pid = -1;
            }
        } else if (parts.length == 3) {
            // 中间版本（seq|state|detail，无 pid），兼容升级后残留的旧文件。
            newState = parts[1];
            detail = parts[2];
        } else {
            // 最老的格式（state|detail），兼容升级后残留的旧文件。
            newState = parts[0];
            detail = parts.length > 1 ? parts[1] : "";
        }

        if (newState.isEmpty()) {
            return;
        }
        // 幻影 running：写这条的进程已经不在，而且心跳也停了 ⇒ 不是真的在跑。
        // pid <= 0 时不做这个判定（宁可显示 running，也不要误刷成 idle）。
        if ("running".equals(newState) && pid > 0 && !isPidAlive(pid)
                && !LauncherService.isRunning(this)) {
            applyState("idle", "");
            return;
        }
        applyState(newState, detail);
    }

    /** /proc/<pid> 是否还在。同 uid 的进程目录可以直接 stat，不需要额外权限。 */
    private boolean isPidAlive(int pid) {
        try {
            return new File("/proc/" + pid).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * status.txt 是「上次运行的遗物」，进程被杀时可能留下 running。
     * 界面刚起来时先按真实情况修正，别把上一次的状态当成本次。
     */
    private void resetStaleState() {
        lastSeq = -1;
        lastStatusContent = "";
        // ★ 跨进程判断：读 :mitm 写的 alive.txt 心跳（不是静态变量）。
        //   老的无参 isRunning() 在主进程里永远是 false，会把正在运行的代理
        //   误判成「没在跑」，进而删掉有效的 status.txt 并显示「未运行」。
        if (LauncherService.isRunning(this)) {
            // 服务真在跑，status.txt 是有效状态，别删（Python 只在状态变化时才写，
            // 删了就再也不会重建，反而是我们制造了不一致）。
            applyState("running", "");
            return;
        }
        // 服务没跑，那把上次留下的 status.txt 当垃圾清掉，避免误显示「运行中」。
        File f = new File(getFilesDir(), "status.txt");
        if (f.exists()) {
            f.delete();
        }
        applyState("idle", "");
    }

    // ------------------------------------------------------------ logging
    private void tailLog() {
        File log = new File(getFilesDir(), "launcher.log");
        if (!log.exists()) {
            logView.setText("(还没有日志: " + log.getAbsolutePath() + ")");
            return;
        }
        long size = log.length();
        if (size == logSize) {
            return;
        }
        logSize = size;
        StringBuilder sb = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long from = Math.max(0, size - 64 * 1024);
            raf.seek(from);
            byte[] buf = new byte[(int) (size - from)];
            raf.readFully(buf);
            String all = new String(buf, "UTF-8");
            String[] lines = all.split("\n");
            int start = Math.max(0, lines.length - LOG_TAIL_LINES);
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
        } catch (Throwable t) {
            sb.append("读日志失败: ").append(t);
        }
        logView.setText(sb.toString());
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 33);
            } catch (Throwable ignored) {
            }
        }
    }
}

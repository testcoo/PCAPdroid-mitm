package io.mitmlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
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

    private EditText argsBox;
    private Button toggleButton;
    private Button clearLogButton;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    /** statusView 的默认字色。出错时改成红色，恢复时切回来（不依赖主题资源）。 */
    private int defaultStatusColor;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * 已消费到的**字节偏移**（相对 launcher.log）。
     *
     * ★ 日志用「增量 append」渲染，不再每轮 setText 全文。原因见 {@link #tailLog()}。
     *   约定：偏移**永远落在某个 '\n' 之后**，所以绝不会切断 UTF-8 多字节字符。
     * {@code -1} = 需要冷启动（首次进入 / 文件被截断 / 清空日志后）。
     */
    private long logOffset = -1;

    /**
     * 界面日志文本的上限（字符数）。
     *
     * ★ 增量 append 的代价是「文本只增不减」，跑久了会一直吃内存。
     *   这里设一个上限，超了就从头删一段。完整日志随时在 launcher.log 文件里，
     *   界面只是「最近一段」，所以不需要留太多。
     */
    private static final int LOG_MAX_CHARS = 200_000;

    /** 需要冷启动时（首次/截断后），从文件尾部读多少字节来还原「最近一段」。 */
    private static final int LOG_COLD_BYTES = 64 * 1024;

    /** 冷启动时最多铺多少行到界面上。 */
    private static final int LOG_COLD_LINES = 200;

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
        // ★ 界面改用布局文件 res/layout/activity_launcher.xml。
        //   原因：代码建 View 时每加一个控件都要改 onCreate，且间距/字号散落在
        //   Java 里，没法一眼看全；布局文件里这些都是声明式的。
        setContentView(R.layout.activity_launcher);

        argsBox = findViewById(R.id.argsBox);
        toggleButton = findViewById(R.id.toggleButton);
        clearLogButton = findViewById(R.id.clearLogButton);
        statusView = findViewById(R.id.statusView);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);

        argsBox.setText(DEFAULT_ARGS);
        // 记下默认字色，出错时临时改成红色，恢复时切回来。
        defaultStatusColor = statusView.getCurrentTextColor();

        toggleButton.setOnClickListener(v -> onToggleClicked());
        clearLogButton.setOnClickListener(v -> clearLog());

        // ★ 这里**不再**监听滚动位置。
        //   原因是日志改用增量 append（见 tailLog），屏幕内容只增不减、位置天然
        //   稳定；而 isScrolledToBottom 这类判断在「程序性滚动」下会被误导成用户
        //   操作（那正是之前日志跳回顶部的元凶之一）。现在每次刷新前现读一次
        //   canScrollVertically(1) 就够了，不需要任何状态位。

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
        toggleButton.setText(isRunning ? R.string.btn_stop : R.string.btn_start);
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
    /**
     * 把 launcher.log 的新增部分**追加**到界面上（增量渲染）。
     *
     * ★★ 为什么不用 {@code setText(全文)}：
     *   日志 TextView 开了 {@code textIsSelectable}（为了长按复制），这会让它内部
     *   有一个 Editor 在管光标/选区，同时让它可获焦。{@code setText()} 替换全文后
     *   光标被归零，TextView 随后会向父 ScrollView 发「把光标滚进可视区」的请求，
     *   于是**界面被强行滚回顶部** —— 而且这个「程序性滚动」还会被
     *   onScrollChange 误当成用户操作，把自动滚动关掉，导致之后再也不自动滚。
     *   {@code append()} 只往尾部追加、不替换文本，光标不动 ⇒ 不产生该请求。
     *
     *   附带收益：
     *     · 屏幕位置天然稳定（内容只增不减），用户翻历史不会被打断；
     *     · 每轮只读「新增的字节」，不再重读 64KB 并重排整段文本；
     *     · 日志很长时也不会因为一次重排而掉帧。
     *
     * ★ 边界处理（一个都不能少，否则会出乱码/错位）：
     *   ① 只消费到**最后一个 '\n' 为止** —— '\n' 是 ASCII，绝不会落在 UTF-8 多字节
     *      序列中间，所以偏移永远停在字符边界上，不会切碎汉字；
     *   ② 最后那个没写完的半行**留给下一轮**，避免显示残缺内容；
     *   ③ 文件变小（被清空 / 新一轮 run 重开）⇒ 丢弃偏移，重走冷启动；
     *   ④ 冷启动时读尾部 LOG_COLD_BYTES，并只保留最后 LOG_COLD_LINES 行。
     */
    private void tailLog() {
        File log = new File(getFilesDir(), "launcher.log");
        if (!log.exists()) {
            // 文件被删了（现在不再会：清空走的是截断）。顺手把界面与偏移复位。
            logOffset = -1;
            logView.setText("");
            return;
        }

        long size = log.length();
        // 文件被截断或重建 ⇒ 之前记的偏移已失效。只有我们自己调 clearLog()
        // 或 Python 重新 open 日志时才会变小（Python 用 mode="a"，正常是只增）。
        if (size < logOffset) {
            logOffset = -1;
        }
        if (size == logOffset) {
            return;                     // 没有新内容，本轮零 I/O
        }

        if (logOffset < 0) {
            coldStartLog(log, size);
            return;
        }

        // ★ 只读新增区间 [logOffset, size)，并在最后一个 '\n' 处截断。
        String chunk;
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            raf.seek(logOffset);
            byte[] buf = new byte[(int) (size - logOffset)];
            raf.readFully(buf);
            String raw = new String(buf, "UTF-8");
            int lastNl = raw.lastIndexOf('\n');
            if (lastNl < 0) {
                return;                 // 新增部分还没有完整的一行，等下一轮
            }
            chunk = raw.substring(0, lastNl + 1);
        } catch (Throwable t) {
            Log.w(TAG, "read log failed", t);
            return;
        }
        // ★ 偏移必须按**字节**推进，而 chunk 是解码后的字符串：
        //   用 UTF-8 重新编码算长度，而不是 String.length()（后者是 UTF-16 码元数，
        //   遇到汉字/emoji 会与实际写入文件的字节数不一致，越推越偏 → 乱码。
        try {
            logOffset += chunk.getBytes("UTF-8").length;
        } catch (Throwable t) {
            logOffset = -1;             // 极端情况下退回冷启动，宁可重铺也不要错位
            return;
        }
        appendLogText(chunk);
    }

    /**
     * 冷启动：把「最近一段」日志直接铺到界面上（首次进入、清空后、文件被截断后）。
     *
     * ★ 直接把尾部截断点当作 logOffset：这样下一轮就能从那里继续 append，
     *   不会重复渲染。截断点是「最后一个 '\n' 之后」，同样保证字符边界安全。
     */
    private void coldStartLog(File log, long size) {
        StringBuilder sb = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long from = Math.max(0, size - LOG_COLD_BYTES);
            raf.seek(from);
            byte[] buf = new byte[(int) (size - from)];
            raf.readFully(buf);
            String raw = new String(buf, "UTF-8");
            // 从 from 开始读可能正好切在某个字符/行的中间：丢掉第一行残片。
            if (from > 0) {
                int firstNl = raw.indexOf('\n');
                raw = firstNl >= 0 ? raw.substring(firstNl + 1) : "";
            }
            // 只保留最后 LOG_COLD_LINES 行，避免一次性铺太多。
            String[] lines = raw.split("\n", -1);
            int start = Math.max(0, lines.length - LOG_COLD_LINES);
            sb.append(String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)));
        } catch (Throwable t) {
            sb.append("读日志失败: ").append(t);
        }
        logView.setText(sb.toString(), TextView.BufferType.EDITABLE);
        logOffset = size;
        // ★ 冷启动后直接贴到底部（这几行是新铺的，用户需要看到最新的末尾）。
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 把一段新日志追加到界面，并在必要时从**头部**删掉一段，控制总长度。
     *
     * ★ 从头部删的原因是「增量」的另一面：文本只增不减会一直吃内存。
     *   完整日志随时在 launcher.log 里，界面只保留最近一段即可。
     */
    private void appendLogText(String chunk) {
        CharSequence cur = logView.getText();
        int curLen = cur == null ? 0 : cur.length();
        boolean atBottom = isAtBottom();

        // ★ 位置必须在改动文本**之前**读，改动后 scrollY 不再可信。
        if (curLen + chunk.length() > LOG_MAX_CHARS) {
            // 超限：保留尾部 LOG_MAX_CHARS/2，并从换行处切开，避免切碎一行。
            String all = cur.toString() + chunk;
            int keepFrom = Math.max(0, all.length() - LOG_MAX_CHARS / 2);
            int nl = all.indexOf('\n', keepFrom);
            String trimmed = nl >= 0 ? all.substring(nl + 1) : all.substring(keepFrom);
            logView.setText(trimmed, TextView.BufferType.EDITABLE);
        } else {
            // ★ 用 append 而不是 setText：只有 append 不替换全文，因而不会触发
            //   「把光标滚进可视区」那个请求（见方法开头的说明）。
            //   （buffer 已在 coldStartLog 里显式设为 EDITABLE，所以这里是真的
            //   纯追加，不会因 buffer 类型不符而隐式走一次 setText。）
            logView.append(chunk);
        }

        if (atBottom) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 是否已滚到底。原生 API，自动处理 padding / layout 状态，不需要容差系数。 */
    private boolean isAtBottom() {
        return !logScroll.canScrollVertically(1);
    }

    /**
     * 清空日志。
     *
     * ★ 用「截断为空」而不是「删除文件」。
     *   原因：Python 侧的 logging.FileHandler 会**一直持有该文件的 fd**。
     *   在 Linux/Android 上，删除一个仍被打开的文件，只是移走目录项，inode 还在
     *   （fd 仍指向它）。之后 Python 的写入会落到那个「看不见的」inode 上 ——
     *   表现为日志从此不再更新，而文件也永远不会被重建（FileHandler 只在构造时
     *   打开一次，即下一次 run() 才会重新 open）。
     *   截断则作用于同一个 inode：FileHandler 以 append 方式打开（O_APPEND），
     *   下一次写入会自动落回偏移 0，日志立刻恢复正常。
     */
    private void clearLog() {
        File log = new File(getFilesDir(), "launcher.log");
        if (log.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(log, "rw")) {
                raf.setLength(0);
            } catch (Throwable t) {
                Log.w(TAG, "clear log failed", t);
            }
        }
        logOffset = -1;         // 下一轮重走冷启动
        logView.setText("");
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

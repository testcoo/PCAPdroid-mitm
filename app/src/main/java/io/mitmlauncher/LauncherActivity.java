package io.mitmlauncher;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
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
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileOutputStream;
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

    /** SharedPreferences 的文件名与键。只存一项：上次成功运行过的参数。 */
    private static final String PREFS = "mitm";
    private static final String PREF_LAST_ARGS = "last_args";

    /**
     * 本次启动实际发出去的参数。
     *
     * ★ 存在的理由：参数的保存时机选在「真的跑起来」（状态变成 running）而非
     *   「点了启动」，所以要在启动时先把参数存下来，等 running 到来再写盘。
     *   null 表示本次没有发起过启动（例如 App 刚打开、发现服务还在跑）。
     */
    private String pendingArgs;

    private EditText argsBox;
    private Button toggleButton;
    private Button clearLogButton;
    private Button copyLogButton;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    /** statusView 的默认字色。出错时改成红色，恢复时切回来（不依赖主题资源）。 */
    private int defaultStatusColor;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 界面上的日志最多显示多少行（只渲染尾部，不渲染整个文件）。 */
    private static final int LOG_TAIL_LINES = 80;

    /** 读日志时最多回溯多少字节来凑够 LOG_TAIL_LINES 行。 */
    private static final int LOG_TAIL_BYTES = 64 * 1024;

    /**
     * 「已到底部」的容差（dp）。
     *
     * ★ 为什么不能直接用 {@code !canScrollVertically(1)}：手指拖到底但差几个像素
     *   松手时，OverScroller 不会吸附到边界，于是被判成「没到底」—— 用户明明拖到
     *   了最底，自动滚动却没开。留一点余量就没这个问题。
     */
    private static final int BOTTOM_TOLERANCE_DP = 8;

    /** 上一轮看到的日志文件长度。用来跳过「没有新内容」的轮询，省掉所有 I/O。 */
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
        copyLogButton = findViewById(R.id.copyLogButton);

        // ★ 参数预填：上次**成功运行过**的那串；没有记录才用内置默认值。
        //   为什么不存「最后输入的」：失败/手误的配置不该变成下次的默认值，
        //   否则用户会一次次打开 App 面对一个跑不起来的配置。
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_LAST_ARGS, null);
        argsBox.setText(saved != null ? saved : DEFAULT_ARGS);

        // ★ 不让输入框自动获得焦点：否则一打开 App 就弹软键盘、遮挡日志区。
        //   抢焦点的完整逻辑在 onResume() 里（onCreate 只在首次创建时跑，
        //   从后台切回来走的是 onRestart → onResume，这里清一次不够）。
        clearArgsFocus();

        // 记下默认字色，出错时临时改成红色，恢复时切回来。
        defaultStatusColor = statusView.getCurrentTextColor();

        toggleButton.setOnClickListener(v -> onToggleClicked());
        copyLogButton.setOnClickListener(v -> copyLog());
        clearLogButton.setOnClickListener(v -> clearLog());

        // ★ 点击空白处让输入框失焦（并收起键盘）。
        //   EditText 的默认行为是「点外部不失焦」，所以要显式处理。
        //   监听设在根布局上 = 只有点到没被其他控件消费的区域才触发，
        //   点按钮/输入框时事件被它们自己消费，不受影响。
        findViewById(R.id.root).setOnClickListener(v -> clearArgsFocus());

        refreshButton();
        requestNotificationPermission();
        resetStaleState();
        handler.post(logPoller);
        // ★ 删掉了 loadEnvInfo()：它会在主进程里 `Python.getInstance()`，
        //   等于把 libpython + mitmproxy 整个再加载一份（约 60~120MB），
        //   而真正干活的 :mitm 子进程自己会冷启动，主进程这份预热纯属浪费。
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ★ 每次回到前台都收走输入框焦点并收起键盘。
        //   为什么必须放这里（而不是只在 onCreate）：从后台切回来走的是
        //   onRestart → onStart → onResume，**onCreate 不会再跑**；而且系统会
        //   恢复离开时的焦点状态，EditText 会记得自己曾是焦点并自动弹出输入法。
        //   放在 onResume 就覆盖了「首次进入 / 后台切回 / 多任务回来」全部路径。
        clearArgsFocus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(logPoller);
        super.onDestroy();
    }

    /**
     * 让参数输入框失焦并收起软键盘。
     *
     * ★ 用 androidx 的 WindowInsetsControllerCompat（声明式），不再用平台的
     *   InputMethodManager.hideSoftInputFromWindow()。后者是「发一次 RPC 请求」，
     *   在窗口处于焦点切换的过渡态时会被静默丢弃、或被随后的一次 show 覆盖。
     *   真机复现就是：点空白**第一次只丢焦点不收键盘**，第二次才收。
     *   声明式 API 改的是本窗口的 Insets 目标可见性，系统后续算 View 状态时
     *   只要目标仍是不可见，就不会再拉起来。
     *
     * ★ 刻意**不**调 root.requestFocus()。
     *   根布局设了 focusableInTouchMode，让它抢焦点会触发一次焦点重算
     *   （ViewRootImpl.performTraversals → InputMethodManager.checkFocus），
     *   在部分 ROM（尤其 MIUI）上这次重算会把刚发出的 hide 请求冲掉。
     *   收键盘并不需要把焦点交给别的 View，清掉当前焦点即可。
     *   （前提仍是根布局有 focusable + focusableInTouchMode —— 见 layout，
     *    否则 clearFocus() 后框架可能把焦点还给唯一的 EditText。）
     *
     * ★ 也不再需要手工 post() 补第二次：声明式调用没有「被覆盖」的问题。
     */
    private void clearArgsFocus() {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        diag("clearFocus: before focused=" + getCurrentFocus()
                + " imeVisible=" + (c != null && c.isVisible(WindowInsetsCompat.Type.ime())));
        argsBox.clearFocus();
        if (c != null) {
            c.hide(WindowInsetsCompat.Type.ime());
        }
        // ★ 诊断：hide 是异步生效的（IME 有动画），所以延后再读一次真实状态。
        //   验证完应删掉本段与 diag()。
        getWindow().getDecorView().postDelayed(() -> diag("clearFocus: +300ms focused="
                + getCurrentFocus() + " imeVisible="
                + (c != null && c.isVisible(WindowInsetsCompat.Type.ime()))), 300);
    }

    /**
     * ★ 临时诊断：把一行写进 launcher.log（界面日志区就能看到，不需要 adb）。
     *   验证键盘问题后请连同调用点一起删除。
     */
    private void diag(String msg) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(
                new File(getFilesDir(), "launcher.log"), true)) {
            o.write(("[UI] " + msg + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ buttons
    /** 单按钮：未运行 → 启动；运行中 → 停止。 */
    private void onToggleClicked() {
        // ★ 点按钮就收走输入框焦点：用户的意图已经表达完（要启动/停止了），
        //   这时键盘继续占着半个屏幕没有意义。
        clearArgsFocus();
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
        // ★ 记下本次真正发出去的参数。等状态变成 running（= 真的 listen 成功）
        //   再写盘，见 applyState()。
        pendingArgs = args;
        LauncherService.start(this, args);
    }

    /**
     * 把本次成功运行的参数写进 SharedPreferences（供下次打开 App 预填）。
     *
     * ★ 只在真的跑起来了才写（applyState 的 running 分支调用）—— 失败或手误的
     *   配置不该成为下次的默认值，否则用户会一次次面对一个跑不起来的配置。
     * ★ 只写一次：applyState 开头有「state+detail 未变则 return」的判重，而同一次
     *   运行里 running 的 detail（监听地址）固定，所以这里最多执行一次。
     *   保险起见还是比对一下，避免边界情况下重复写盘。
     */
    private void saveArgsIfRunning() {
        String args = pendingArgs;
        if (args == null || args.isEmpty()) {
            return;                 // 本次不是我们发起的启动（如 App 重开、服务已在跑）
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (args.equals(prefs.getString(PREF_LAST_ARGS, null))) {
            return;                 // 没变化，不必写
        }
        prefs.edit().putString(PREF_LAST_ARGS, args).apply();
        Log.i(TAG, "saved args for next launch: " + args);
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
                // ★ 到这里说明 mitmproxy 已经真的在监听了 ⇒ 这串参数是可用的，
                //   记下来当作下次打开 App 的预填值。放在这里而不是「点了启动」时，
                //   是为了不把失败/手误的配置变成下次的默认值。
                saveArgsIfRunning();
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
     * 把 launcher.log 的尾巴刷到界面上。日志就是「实时看你正在发生什么」，
     * 所以规则只有两条：
     *
     *   ① **在底部**（含 {@link #BOTTOM_TOLERANCE_DP} 容差）→ 重渲染最后
     *      {@link #LOG_TAIL_LINES} 行并跟到底部，像 tail -f。
     *   ② **不在底部**（用户在翻看）→ **一个字节都不动**。
     *
     * ★★ 规则②为什么是「不动」而不是「保留滚动位置」：
     *   这是本文件最值得记住的一条。日志 TextView 只要有 {@code textIsSelectable}
     *   （或任何可编辑状态），内部就有 Editor 在管光标/选区。文本一旦变化
     *   （{@code setText} 替换全文、甚至只是长度变了），Editor 就会为「把光标滚进
     *   可视区」向父 ScrollView 发请求，父容器响应后**界面自己跳走** ——
     *   表现为「翻看时新日志一来就跳回顶部/底部」。
     *   所以：不去修滚动位置，而是**根本不给它变化的机会**。用户滑回底部后，
     *   规则①会立刻渲染最新内容，不需要任何状态位或监听器。
     *
     * ★ 顺带：本工程已去掉 {@code textIsSelectable}（复制改用 {@link #copyLog()}），
     *   上面那个 Editor 也就不存在了。但「不在底部就不动」依然保留 —— 它让翻看
     *   体验彻底不受打扰，代价只是一个 if。
     */
    private void tailLog() {
        File log = new File(getFilesDir(), "launcher.log");
        if (!log.exists()) {
            return;
        }

        // ★ 翻看中：什么都不做。放在最前面，连文件长度都不读 ——
        //   ① 不渲染 ⇒ TextView 不变 ⇒ 屏幕物理上不可能移动；
        //   ② 不更新 logSize ⇒ 下一轮回到「在底部」时会立刻补上最新内容。
        if (!isAtBottom()) {
            return;
        }

        long size = log.length();
        if (size == logSize) {
            return;                     // 没有新内容，本轮零 I/O
        }
        logSize = size;

        StringBuilder sb = new StringBuilder();
        try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
            long from = Math.max(0, size - LOG_TAIL_BYTES);
            raf.seek(from);
            byte[] buf = new byte[(int) (size - from)];
            raf.readFully(buf);
            String raw = new String(buf, "UTF-8");
            // 从中间开始读可能切在字符/行的中间：丢掉第一行残片。
            if (from > 0) {
                int firstNl = raw.indexOf('\n');
                raw = firstNl >= 0 ? raw.substring(firstNl + 1) : "";
            }
            String[] lines = raw.split("\n", -1);
            int start = Math.max(0, lines.length - LOG_TAIL_LINES);
            sb.append(String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)));
        } catch (Throwable t) {
            Log.w(TAG, "read log failed", t);
            return;
        }

        logView.setText(sb.toString());
        // post 是必要的：TextView 要先按新文本完成 layout，内容变高后才能滚到底。
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /**
     * 是否「在底部」。
     *
     * ★ 为什么用「子 View 底边 - 滚动量 - 视口高度 ≤ 容差」而不是直接用
     *   {@code !canScrollVertically(1)}：手指拖到底但差几像素松手时，OverScroller
     *   不会吸附到边界，{@code canScrollVertically(1)} 仍返回 true ⇒ 被判成
     *   「没到底」，用户明明到底了自动滚动却没开。留 BOTTOM_TOLERANCE_DP 就没这个问题。
     *
     * ★ child == null 时返回 true：还没布局（或内容为空）⇒ 当作「在底部」，
     *   这样首帧、以及清空日志之后，都能立刻触发一次渲染，是自愈的。
     */
    private boolean isAtBottom() {
        View child = logScroll.getChildAt(0);
        if (child == null) {
            return true;
        }
        int gap = child.getBottom() - logScroll.getScrollY() - logScroll.getHeight();
        int tol = (int) (BOTTOM_TOLERANCE_DP * getResources().getDisplayMetrics().density);
        return gap <= tol;
    }

    /** 把界面上正在显示的日志复制到剪贴板（只复制界面这一段，不是整个文件）。 */
    private void copyLog() {
        CharSequence text = logView.getText();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || text == null || text.length() == 0) {
            Toast.makeText(this, R.string.copy_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText("launcher.log", text));
            Toast.makeText(this, R.string.copy_log_done, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "copy log failed", t);
        }
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
     *
     * ★ 不需要额外重置状态：清空后界面文本为空 ⇒ 子 View 高度为 0 ⇒
     *   isAtBottom() 返回 true ⇒ 下一轮轮询自动重新渲染。是自愈的。
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
        logSize = -1;
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

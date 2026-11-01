package io.mitmlauncher;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 跑 mitmproxy 的前台服务。
 *
 * 为什么不复用上游的 VPN 转发服务（已在 v0.3.0 清理时删除）：
 *   上游把 VPNService 抓到的 socket fd 传给 Python（透明/SOCKS5 模式），
 *   而我们是显式代理 —— mitmproxy 自己去 bind 127.0.0.1:8080，根本没有 fd 这回事。
 *   两者的启动参数、状态语义完全不同，硬套只会把无关分支带进来。
 *
 * 进程模型（M2b 起）：**跑在 :mitm 独立进程**（见 AndroidManifest）。
 *   为什么必须拆，M2a 真机给了一个很硬的答案：
 *     mitmproxy 启动失败（端口被占）时抛的是 SystemExit，而它是在 asyncio 拆 loop
 *     的过程中抛出来的。同进程的旧写法在 finally 里 `await` 了一个去操作
 *     asyncio.Server 的协程 ⇒ **Python 工作线程永远不返回** ⇒ 服务永久存活、
 *     端口永久被占、之后每次点「启动」都被「已在运行」挡掉，只能强行停止应用。
 *   拆出去之后有了两条同进程拿不到的退路：
 *     ① 收尾兜底可以是 Process.killProcess(myPid())：在 :mitm 里自杀，内核回收
 *        该进程全部 fd（含监听 socket），主进程 UI 完全不受影响，**不会弹崩溃框**。
 *     ② Python 无论卡成什么样，主进程的状态显示和按钮都还是活的。
 *   代价：静态变量/静态回调跨不了进程。所以
 *     · 状态同步**只认 status.txt**（跨进程 + 跨进程死亡，唯一可靠通道）；
 *     · 存活探测**只认 alive.txt 心跳**（由本进程的 Java 线程写，不受 Python 阻塞影响）。
 *
 * ★ 线程纪律（M2a 真机反馈后定下的硬规矩，拆进程后依然成立）：
 *   onCreate/onStartCommand 跑在**本进程主线程**。
 *   Python 解释器冷启动 + `from mitmproxy import options` 是**秒级**开销，
 *   一旦放在主线程就会把 :mitm 的主线程冻住：通知迟迟不出现、心跳停摆、
 *   极端情况还会撞上 API 26+ 的
 *   "startForegroundService() did not then call startForeground()" 5 秒红线。
 *   所以 onCreate() 里**只允许**做通知渠道这种毫秒级的事，
 *   Python 模块一律在**工作线程**里再取。
 */
public class LauncherService extends Service {

    private static final String TAG = "MitmLauncher";

    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_STOP = "stop";

    private static final String CHANNEL_ID = "mitm_run";
    private static final int NOTIF_ID = 1;

    // ------------------------------------------------------------ 存活心跳
    /**
     * ★ 心跳文件：由本进程（:mitm）的一个**纯 Java 线程**周期性写，格式
     * {@code pid|epochMillis}。主进程靠它判断「代理到底还在不在」。
     *
     * 为什么不用别的办法：
     *   · 静态变量（老的 sRunning）跨不了进程，主进程读到的永远是初始值 false；
     *   · 枚举进程列表（ActivityManager.getRunningAppProcesses）在 API 30+
     *     对同 uid 之外的信息做了裁剪，不可靠；
     *   · status.txt 只反映「状态机最后一次写入」，进程被 killProcess 兜底杀掉时
     *     根本来不及写任何东西，会留下一个永远停在 running 的假状态。
     * 心跳的关键性质：写它的线程**不受 Python 是否阻塞影响**，所以
     * 「进程活着但 Python 卡死」和「进程已经没了」这两种情况能被区分开
     * —— 前者心跳仍然新鲜，后者心跳变陈旧。
     */
    private static final String ALIVE_FILE = "alive.txt";
    private static final long HEARTBEAT_MS = 1000;
    /** 心跳超过这个时长没更新就认定 :mitm 已经不在（留 3 倍冗余，抗 GC/调度抖动）。 */
    private static final long ALIVE_FRESH_MS = 3000;

    // ------------------------------------------------------------ 看门狗
    /**
     * ★ 看门狗时间预算。为什么不只用一个固定值：
     *   启动早期「Python 冷启动 + import mitmproxy」本身就要 1~3 秒，用短超时必然误杀；
     *   而一旦 Python 已经报过状态（说明解释器早就热了，后面的状态变化都是毫秒级），
     *   再长时间没动静就一定是真卡了。
     *   所以分成「从没收到过任何状态」和「收到过状态之后」两档 —— 既快又不误杀。
     */
    /** 收到过至少一条状态之后，这么久没有新状态就判卡死。 */
    private static final long START_TIMEOUT_AFTER_PROGRESS_MS = 3000;
    /** 从点击起一直没收到任何状态（含 Java 自己写的那条 starting 回执），给它这么长时间。 */
    private static final long START_TIMEOUT_ABSOLUTE_MS = 12000;
    /** 探活间隔。比 3s 阈值小一个数量级，保证「死了」能立刻发现而不是等满超时。 */
    private static final long WATCHDOG_TICK_MS = 200;

    /** 本次启动的起点时间戳；0 表示当前不在「启动中」。 */
    private volatile long startAt = 0;
    /** 最近一次收到任何状态的时间（含 Java 自己写的）。用于「收到过状态后」的判定。 */
    private volatile long lastProgressAt = 0;
    /** 本次启动是否见过 starting 之外的状态（= Python 已经真的动起来了）。 */
    private volatile boolean sawProgress = false;
    /** 最近一次写下的状态值。仅在「worker 死了但状态还停在 starting」时用来兜底。 */
    private volatile String lastKnownState = "";

    /** 本进程内的运行标志。只用于「要不要再次启动」这个判断，不跨进程。 */
    private static volatile boolean sRunning = false;

    /**
     * ★ volatile：launcher 现在由工作线程赋值、由主线程 (onDestroy) 读取，
     * 没有 volatile 可能读到 null 或半构造对象。
     */
    private volatile PyObject launcher;

    private NotificationManager nm;
    private Thread worker;
    private Thread heartbeat;
    private Thread watchdog;
    /** 心跳里附带的一句当前阶段说明，纯粹为了排查问题时好认。 */
    private volatile String phase = "starting";

    // ------------------------------------------------------------ CPU 唤醒锁
    /**
     * ★ CPU 唤醒锁（PARTIAL_WAKE_LOCK）。
     *
     * 为什么必须有：`startForeground()` 只保证「内存紧张时进程不被回收」，
     * **完全不保证 CPU 不休眠**。息屏后内核会进入 suspend，所有用户态线程
     * （包括 Python 的 asyncio 事件循环）一起停转 ⇒ 外部来的 TCP 握手
     * 虽然在网络栈（内核态）能被接住，但应用层没人 accept/处理 ⇒
     * 浏览器表现是「转圈直到超时」。
     *
     * ★ 只在代理真的起来之后才 acquire，不是在 onCreate：
     *   onCreate 时 mitmproxy 可能还没启动（甚至参数为空直接 return），
     *   那时拿锁就是「代理没跑却在耗电」。acquire 放在 doStart() 的
     *   startForegroundCompat() 之后；release 放在所有退出路径。
     *
     * ★ 为什么 acquire 不带超时：代理需要一直跑。代价是必须保证释放
     *   （worker 的 finally / onDestroy 各放一次，release 幂等所以重复调无妨）。
     *   注意 killSelf() 走 Process.killProcess，onDestroy 不会执行 ——
     *   但内核会回收进程持有的全部 wake lock，这是安全的。
     */
    private PowerManager.WakeLock cpuLock;

    /** 取名为「包名:用途」，方便在 `dumpsys power` 里认出是谁拿的锁。 */
    private static final String CPU_LOCK_TAG = "MitmLauncher:cpu";

    /**
     * 拿住 CPU 锁。幂等：已持有就直接返回。
     * ★ 用 @SuppressLint("WakelockTimeout") 是刻意的 —— 见 cpuLock 的说明。
     */
    @SuppressLint("WakelockTimeout")
    private void acquireCpuLock() {
        try {
            if (cpuLock != null && cpuLock.isHeld()) {
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) {
                return;
            }
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG);
            cpuLock.setReferenceCounted(false);   // 我们自己管配对，不让框架计数
            cpuLock.acquire();
            Log.i(TAG, "cpu wakelock acquired");
        } catch (Throwable t) {
            // 拿不到锁绝不能让服务崩：最坏情况退化成「息屏可能不响应」
            Log.w(TAG, "acquire wakelock failed", t);
        }
    }

    /** 释放 CPU 锁。幂等：没持有就什么都不做。 */
    private void releaseCpuLock() {
        try {
            if (cpuLock != null && cpuLock.isHeld()) {
                cpuLock.release();
                Log.i(TAG, "cpu wakelock released");
            }
        } catch (Throwable t) {
            Log.w(TAG, "release wakelock failed", t);
        }
    }

    /**
     * ★ 跨进程存活判断：读心跳文件的时间戳。主进程只能调这个版本。
     * 老的无参版本读的是静态变量，拆进程后主进程里永远是 false，会误判成
     * 「没在跑」进而把有效的 status.txt 当垃圾删掉、界面显示「未运行」——
     * 而实际上代理正在跑。
     */
    public static boolean isRunning(Context ctx) {
        if (ctx == null) {
            return false;
        }
        long ts = readHeartbeat(ctx);
        return ts > 0 && (System.currentTimeMillis() - ts) < ALIVE_FRESH_MS;
    }

    /** 读心跳文件里的写入时间戳；解析失败返回 -1。 */
    private static long readHeartbeat(Context ctx) {
        FileInputStream in = null;
        try {
            File f = new File(ctx.getFilesDir(), ALIVE_FILE);
            if (!f.exists()) {
                return -1;
            }
            byte[] buf = new byte[128];
            in = new FileInputStream(f);
            int n = in.read(buf);
            if (n <= 0) {
                return -1;
            }
            String[] parts = new String(buf, 0, n, "UTF-8").trim().split("\\|");
            if (parts.length < 2) {
                return -1;
            }
            return Long.parseLong(parts[1]);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 启动后台服务。startForegroundService 的 Intent 是跨进程的，
     * 系统会把 :mitm 拉起来并调用它的 onStartCommand —— 不需要 AIDL/Messenger。
     */
    public static void start(Context ctx, String args) {
        Intent i = new Intent(ctx, LauncherService.class);
        i.putExtra(EXTRA_ARGS, args);
        if (Build.VERSION.SDK_INT >= 26) {
            // 启动必须用 startForegroundService：要在 5s 内 startForeground。
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, LauncherService.class);
        i.putExtra(EXTRA_STOP, true);
        // 停止**故意**用普通 startService，不能用 startForegroundService：
        // 后者要求 5 秒内必须调 startForeground，而「停止」这条路径根本不该
        // 再起前台通知；服务已死时走 startForegroundService 会直接触发
        // "Context.startForegroundService() did not then call Service.startForeground()"
        // 而崩溃（API 26+）。
        // 对**已在运行**的服务发 startService 不算「后台启动前台服务」，不会触发
        // Android 12+ 的 ForegroundServiceStartNotAllowedException。
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // ★ 这里坚决不碰 Python。渠道创建是幂等的，放这里可以避免每次 buildNotification
        //   都去查一遍系统服务（真机上也能省掉一点通知刷新的抖动）。
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureNotificationChannel();
        // ★ 心跳线程必须在 onCreate 就起来：主进程一调 start()，:mitm 就被拉起来，
        //   从这一刻起心跳就是「这个进程活着」的凭证，与 Python 是否阻塞无关。
        startHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            doStop();
            return START_NOT_STICKY;
        }

        String args = intent.getStringExtra(EXTRA_ARGS);
        if (args == null) {
            return START_NOT_STICKY;
        }
        doStart(args);
        return START_NOT_STICKY;
    }

    private void doStart(final String args) {
        if (worker != null && worker.isAlive()) {
            // ★★ 这里以前是 `return` 静默吞掉 —— 真机表现为「第一次失败之后，
            //    再点启动什么都不发生（连日志都没有），界面一路走到状态超时」。
            //    现在改成**明确写状态 + 记日志**：界面立刻能看到原因，
            //    也不会再出现「点了没反应」的黑洞。
            Log.w(TAG, "already running, ignore start request");
            String msg = "上一次运行尚未结束，请先点停止再启动";
            refreshNotificationByState("error", msg);
            writeStatusFile("error", msg);
            return;
        }

        // 必须在 5 秒内 startForeground，否则 ANR / 系统直接杀服务。
        // ★ 由于 onCreate 已经不再加载 Python，这一步是「立刻」发生的。
        startForegroundCompat(buildNotification("正在启动…", "正在初始化环境"));
        // ★ 拿 CPU 唤醒锁：从这一刻起直到服务退场，CPU 不允许因息屏而 suspend。
        //   放在 startForeground 之后（而不是 onCreate）：那时才确定「这次真的要跑」，
        //   之前拿锁会在「参数为空直接 return」之类的情况下白白耗电。
        acquireCpuLock();
        // ★ 立刻写一条 starting 回执（<50ms，早于任何 Python 代码执行）。
        //   界面因此一定会先看到「正在启动…」，而不是等 Python 冷启动完才有反应。
        //   看门狗的「从未收到任何状态」那条兜底也因此有了明确的计时起点。
        writeStatusFile("starting", args);
        phase = "starting";
        lastKnownState = "starting";
        // ★ 启动看门狗：这是「点下去之后无论发生什么都能给出结论」的保险。
        startAt = System.currentTimeMillis();
        lastProgressAt = startAt;
        sawProgress = false;
        startWatchdog();

        sRunning = true;
        worker = new Thread(() -> {
            try {
                // ★ Python 模块在工作线程里取：冷启动 + import mitmproxy 的秒级开销
                //   全部发生在后台，不阻塞 :mitm 的主线程，也不占用 5 秒红线。
                launcher = Python.getInstance().getModule("launcher");
                // 把状态回调传进 Python：Python 侧每次状态变化都会回调到这里。
                // ★ 拆进程后这个回调**只服务通知刷新**（sUiListener 已删除），
                //   界面的状态来源只剩 status.txt —— 那是唯一跨进程可靠的通道。
                launcher.callAttr("run", args, new StateBridge());
            } catch (Throwable t) {
                Log.e(TAG, "launcher.run failed", t);
                // ★ 只在 Python 还没给出结论时才兜底写错，否则会把更具体的首因
                //   （如「端口 8080 已被占用」）盖成泛化的「Python 调用失败：…」。
                if ("starting".equals(lastKnownState)) {
                    writeStatusFile("error", "Python 调用失败：" + t);
                }
            } finally {
                sRunning = false;
                phase = "finished";
                startAt = 0;          // ★ 关掉看门狗的启动计时（工作线程正常退出了）
                Log.i(TAG, "worker finished, lastState=" + lastKnownState);
                // 从工作线程收尾：停前台。
                // ★ 走到这里说明 launcher.run() 返回了（正常关停，或已被 Python
                //   内部兜底成 error）。状态文件已由 Python 写好，这里不再覆盖。
                // ★ 先放 CPU 锁再停前台：退出路径上两个都要收，顺序无所谓，
                //   但放锁一定不能漏（漏了就是息屏时白耗电）。
                releaseCpuLock();
                stopForegroundCompat();
                stopSelf();
                if (!"idle".equals(lastKnownState)) {
                    // ★ 异常退出（启动失败 / 崩溃）：直接杀 :mitm 进程。
                    //   目的是让下一次启动拿到**全新的解释器 + 全新的 event_loop**，
                    //   彻底切断任何残留单例（mitmproxy 的 openssl 上下文、异步 task、
                    //   全局模块状态）的污染。:mitm 是私有子进程，杀了不影响 UI。
                    //   正常停止（idle）则不杀，保留热进程让下次启动更快。
                    Log.w(TAG, "abnormal exit, killing :mitm process");
                    try {
                        Thread.sleep(150);   // 给状态落盘 / 通知刷新留一点时间
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    killSelf();
                }
            }
        }, "mitmproxy");
        worker.start();
    }

    private void doStop() {
        if (worker == null || !worker.isAlive()) {
            // 服务本来就不在跑，直接收工。顺手清掉可能残留的心跳，
            // 免得下一次读到最后一次运行的旧时间戳。
            stopForegroundCompat();
            stopSelf();
            return;
        }
        phase = "stopping";
        startAt = 0;              // ★ 停止路径不归启动看门管（这里自己 join(2000) 兑底）
        refreshNotificationByState("stopping", "");
        writeStatusFile("stopping", "释放监听端口中");
        // stop() → master.shutdown()，内部是 call_soon_threadsafe，跨线程安全。
        // 不要在这里 join：onStartCommand 跑在主线程，join 会卡住主线程。
        final PyObject py = launcher;
        final Thread stopping = new Thread(() -> {
            try {
                if (py != null) {
                    py.callAttr("stop");
                }
            } catch (Throwable t) {
                Log.e(TAG, "launcher.stop failed", t);
            }
            // ★★ 兜底：等 2 秒，Python 工作线程还不退就自杀。
            //    为什么敢杀：能走到这里说明用户**明确点了停止**，不可能误杀一个
            //    正在正常启动的进程；而 Python 卡住的话，除了杀进程没有任何办法
            //    能让它把监听的 socket fd 交出来（Chaquopy 不支持中断 callAttr）。
            //    Process.killProcess 在 :mitm 里执行 ⇒ 只死这个子进程，主进程（UI）
            //    完全不受影响，也不会弹「应用已停止」的崩溃框。
            try {
                if (worker != null) {
                    worker.join(2000);
                }
            } catch (Throwable ignored) {
            }
            if (worker != null && worker.isAlive()) {
                Log.w(TAG, "worker still alive 2s after stop, killing :mitm process");
                String msg = "内核无响应，已自动重置（可重新启动）";
                // ★ 先写状态再自杀：让界面拿到一句解释，否则「通知突然消失」很莫名。
                writeStatusFile("error", msg);
                refreshNotificationByState("error", msg);
                try {
                    Thread.sleep(150);   // 给文件落盘/通知刷新留一点点时间
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                killSelf();
            }
        }, "mitmproxy-stop");
        stopping.start();
    }

    /** ★ 只允许在 :mitm 进程里调用。主进程自杀会把 UI 一起干掉。 */
    private void killSelf() {
        Log.w(TAG, "killProcess(self, pid=" + Process.myPid() + ")");
        Process.killProcess(Process.myPid());
    }

    @Override
    public void onDestroy() {
        if (worker != null && worker.isAlive()) {
            try {
                final PyObject py = launcher;
                if (py != null) {
                    py.callAttr("stop");
                }
                worker.join(1000);
            } catch (Throwable t) {
                Log.w(TAG, "stop on destroy failed", t);
            }
        }
        sRunning = false;
        clearHeartbeat();
        // ★ 兜底放锁：worker 的 finally 里已经放过一次，release 幂等所以重复调无害；
        //   但 onDestroy 可能因「服务被系统回收」而被单独调用，那条路径没经过 worker。
        releaseCpuLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --------------------------------------------------------------- 心跳
    private void startHeartbeat() {
        if (heartbeat != null && heartbeat.isAlive()) {
            return;
        }
        // ★ 纯 Java、无 Python、无锁：即使 Python 线程彻底卡死，这个线程照样在写。
        //   这是「进程还活着」的唯一凭证，主进程据此区分「卡死」和「进程已死」。
        heartbeat = new Thread(() -> {
            while (true) {
                long ts = writeHeartbeat();
                try {
                    Thread.sleep(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // 写失败（进程正在退出/文件系统异常）就自然结束，不必重试。
                if (ts < 0) {
                    return;
                }
            }
        }, "mitm-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    /** @return 写入的时间戳，失败返回 -1。 */
    private long writeHeartbeat() {
        long now = System.currentTimeMillis();
        FileOutputStream out = null;
        try {
            File tmp = new File(getFilesDir(), ALIVE_FILE + ".tmp");
            out = new FileOutputStream(tmp);
            // 格式 pid|epochMillis|phase，phase 只是给人看的排查信息。
            out.write(String.format("%d|%d|%s", Process.myPid(), now, phase).getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            // 原子替换：主进程绝不会读到写了一半的内容。
            File dst = new File(getFilesDir(), ALIVE_FILE);
            if (!tmp.renameTo(dst)) {
                // rename 在某些文件系统上不可靠，退化成就地覆盖。
                out = new FileOutputStream(dst);
                out.write(String.format("%d|%d|%s", Process.myPid(), now, phase).getBytes("UTF-8"));
                out.flush();
                out.close();
                out = null;
            }
            return now;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void clearHeartbeat() {
        try {
            File f = new File(getFilesDir(), ALIVE_FILE);
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "delete heartbeat file failed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "clear heartbeat failed", t);
        }
    }

    /**
     * 写一份「和 Python 同格式」的状态文件。
     *
     * 为什么 Java 要抢着写：Python 侧卡住时它一个字节都写不出来，而这些失败
     * 恰恰是最需要让界面知道的（例如「上一次还没结束」「内核无响应，已重置」）。
     * 格式必须与 launcher.py 的 {@code seq|state|detail|pid} 完全一致，
     * 否则界面侧解析不出来。
     *
     * ★ seq 的处理：这里**沿用文件里已有的 seq**（不递增）。原因是主进程用
     *   "seq 严格大于上次" 来判重；如果 Java 随便造一个新的更大值，会和 Python
     *   后续写的 seq 撞车，导致真正的新状态被当成重复丢掉。沿用 + 直接覆盖内容，
     *   主进程会因为 detail 变化而刷新（它的判重是 state+detail 一起看）。
     */
    private void writeStatusFile(String state, String detail) {
        try {
            long seq = readStatusSeq();
            String text = String.format("%d|%s|%s|%d",
                    seq, state, detail == null ? "" : detail, Process.myPid());
            File tmp = new File(getFilesDir(), "status.txt.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(text.getBytes("UTF-8"));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            File dst = new File(getFilesDir(), "status.txt");
            if (!tmp.renameTo(dst)) {
                out = new FileOutputStream(dst);
                try {
                    out.write(text.getBytes("UTF-8"));
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "write status file failed", t);
        }
    }

    /** 读现有 status.txt 的 seq；文件不存在或解析失败时返回 0（Python 从 1 开始递增）。 */
    private long readStatusSeq() {
        FileInputStream in = null;
        try {
            File f = new File(getFilesDir(), "status.txt");
            if (!f.exists()) {
                return 0;
            }
            byte[] buf = new byte[256];
            in = new FileInputStream(f);
            int n = in.read(buf);
            if (n <= 0) {
                return 0;
            }
            String[] parts = new String(buf, 0, n, "UTF-8").trim().split("\\|", 2);
            return Long.parseLong(parts[0].trim());
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Python → Java 的桥。
     *
     * 用**具名内部类**而不是 lambda：Chaquopy 从 Python 侧解析 Java 方法时要看到真实的
     * 类型层次，具名类的行为最可预期（匿名/lambda 的合成类历史上踩过坑）。
     *
     * 线程：本方法在 Python 工作线程被调用。通知刷新可以直接做（NotificationManager
     * 是 Binder 代理，线程安全）。
     *
     * ★ 拆进程后这里**只管通知**：Activity 在另一个进程，静态字段 sUiListener
     *   早就是本进程的另一个副本，回调传不过去（这正是它被删掉的原因）。
     *   界面的状态完全靠轮询 status.txt。
     */
    private class StateBridge implements MitmListener {
        @Override
        public void onStateChanged(String state, String detail) {
            // ★ 看门狗记账：「收到过非 starting 的状态」意味着 Python 已经真的动起来了，
            //   后面的超时可以收紧到 3s；否则（一直没动静）给 12s 的宽限。
            lastProgressAt = System.currentTimeMillis();
            lastKnownState = state;
            if (!"starting".equals(state)) {
                sawProgress = true;
            }
            refreshNotificationByState(state, detail);
        }
    }

    // ------------------------------------------------------------- 看门狗
    /**
     * ★ 启动阶段的保险栓。
     *
     * 为什么必须要它：把「Python 工作线程在 asyncio 拆 loop 时死锁」这类问题
     * 归因掉了（预检 + 分态收尾），但仍然可能有别的原因让 run() 永远不返回。
     * 没有看门狗时，用户看到的就是「界面小字：正在启动…」永久停在那里，
     * 然后靠界面自己 6 秒超时猜测「状态超时，请重试」—— 治标不治本，
     * 因为服务其实还活着、下一次启动依然会被「已在运行」挡掉。
     *
     * 判定条件（全部满足才动手）：
     *   ① 处于启动中（startAt > 0）—— 确保不会误杀一个正常运行的代理；
     *   ② worker 已经死了却还没收到终态，或者超时了；
     *   ③ 确实没有收到过任何终态（running/error/idle）。
     */
    private void startWatchdog() {
        if (watchdog != null && watchdog.isAlive()) {
            return;
        }
        watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_TICK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (startAt == 0) {
                    return;      // 启动阶段已结束（成功进入运行 / 失败退出）
                }
                long now = System.currentTimeMillis();
                Thread w = worker;
                boolean workerDead = (w == null) || !w.isAlive();

                // ① 探活：worker 死了 + 没收到终态 ⇒ 不用等满，立刻判定。
                //    ★ 必须配 sawProgress / lastKnownState 一起看：worker 正常结束
                //    时会先写 idle，那属于正常收尾，不能报错。
                if (workerDead && !isTerminalState(lastKnownState)) {
                    failStart("内核已退出但未上报结果，已自动重置");
                    return;
                }
                // ★★ 启动已经成功（收到 running）⇒ 看门狗使命完成，立即退休。
                //    为什么必须加：worker 启动成功后阻塞在 master.run() 的
                //    await should_exit.wait() 里**不会返回**，startAt 因此永远不为 0，
                //    看门狗会一直跑；而 sawProgress 已为 true ⇒ 预算收紧到 3s，
                //    于是健康运行的代理会在启动 3 秒后被自己人误杀。
                //    —— 这正是「空闲端口正常启动，几秒后报启动超时」的根因。
                if ("running".equals(lastKnownState)) {
                    Log.i(TAG, "watchdog: startup succeeded, retiring");
                    startAt = 0;
                    return;
                }
                // ★ 已经落到终态且 worker 已退 ⇒ 收尾完成，退休（正常路径冗余保险）。
                if (workerDead && isTerminalState(lastKnownState)) {
                    startAt = 0;
                    return;
                }
                // ② 超时：收到过状态 → 3s；一直没状态 → 12s。
                long budget = sawProgress ? START_TIMEOUT_AFTER_PROGRESS_MS : START_TIMEOUT_ABSOLUTE_MS;
                long anchor = sawProgress ? lastProgressAt : startAt;
                if (now - anchor > budget) {
                    failStart("启动超时（" + (budget / 1000) + "秒无响应），已自动重置");
                    return;
                }
            }
        }, "mitm-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private boolean isTerminalState(String s) {
        return "running".equals(s) || "error".equals(s) || "idle".equals(s);
    }

    /**
     * 启动阶段卡死时的统一处理：写状态给界面一个解释 → 刷新通知 → 杀 :mitm 自愈。
     *
     * ★ 为什么直接杀而不是先尝试 "优雅" 收尾：
     *   能走到这里就说明 Python 工作线程已经不响应任何指令了（连 stop() 都叫不动），
     *   除了杀进程没有任何手段能让内核把监听的 socket fd 交出来。
     *   而 :mitm 是自己人，杀了以后主进程的界面完全不受影响，也不会弹崩溃框，
     *   下一次点启动就是一个干净的全新进程 —— 这就是「自愈」。
     */
    private void failStart(String reason) {
        Log.w(TAG, "watchdog: " + reason);
        startAt = 0;             // 防止重复触发
        startForegroundCompat(buildNotification("启动失败", reason));
        writeStatusFile("error", reason);
        // 稍微等一下，让界面至少有一次机会读到这条状态、让通知刷新一次。
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        killSelf();
    }

    // --------------------------------------------------------------- 通知
    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "mitmproxy 运行状态", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    /**
     * 按状态刷新通知内容。
     *
     * 「正在启动…」以前是写死的：状态转 running 时没有任何人更新通知，于是通知
     * 永远停在启动态。现在由 Python 回调驱动，内容跟着真实状态走。
     */
    private void refreshNotificationByState(String state, String detail) {
        String title;
        String text;
        if ("running".equals(state)) {
            title = (detail == null || detail.isEmpty()) ? "代理运行中" : "代理运行中 · " + detail;
            text = "点按打开 MitmLauncher";
        } else if ("error".equals(state)) {
            title = "启动失败";
            text = (detail == null || detail.isEmpty()) ? "详情见应用内日志" : detail;
        } else if ("stopping".equals(state)) {
            title = "正在停止…";
            text = "释放监听端口中";
        } else {
            title = "正在启动…";
            text = "正在初始化环境";
        }
        updateNotification(title, text);
    }

    /**
     * 首次必须走 {@link #startForegroundCompat}（前台服务通知必须由 startForeground 建立），
     * 之后的**内容更新**走 NotificationManager.notify() 即可。
     *
     * 早前注释里写着「notify() 在部分 ROM 上不会刷新」——那是不成立的：前台服务通知
     * 与普通通知一样走 NotificationManagerService，notify() 会正常重建通知。
     */
    private void updateNotification(String title, String text) {
        try {
            Notification n = buildNotification(title, text);
            if (nm != null) {
                nm.notify(NOTIF_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "update notification failed", t);
        }
    }

    private Notification buildNotification(String title, String text) {
        // FLAG_IMMUTABLE 的字面量是 0x04000000（少写一个 0 就成了 FLAG_ONE_SHOT，
        // API 31+ 会直接抛 IllegalArgumentException）。
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000;
        Intent open = new Intent(this, LauncherActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        // 「停止」快捷操作。对已在运行的 FGS 发 startService(GET_SERVICE) 属正常
        // 前台交互，不会触发 Android 12+ 的后台启动限制。
        Intent stopIntent = new Intent(this, LauncherService.class);
        stopIntent.putExtra(EXTRA_STOP, true);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, flags);

        // 故意不用 androidx 的 NotificationCompat：本工程没有 androidx 依赖，
        // 为了一个通知去引入整套 androidx 不划算（也增加 CI 变量）。
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this);
            //noinspection deprecation
            b.setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_stat_mitm)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(0, "停止", stopPi).build());

        if (Build.VERSION.SDK_INT >= 31) {
            // 默认行为（DEFAULT / DEFERRED）下，系统「通常会」把通知显示短暂压后，
            // 以避免对用户造成视觉干扰；IMMEDIATE 明确要求立刻显示。
            // 实测这正是「点启动后几秒才看到通知」的一部分成因。
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}

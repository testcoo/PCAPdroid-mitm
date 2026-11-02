#!/usr/bin/env python3
#  MitmLauncher - Python 入口
#
#  与 PCAPdroid-mitm 的 mitm.py 同源思路：**直接调 mitmproxy 的库**，
#  不走 mitmproxy.tools.main 的 CLI 入口（那会装信号处理器，在工作线程必炸）。
#
#  M0/M1：只有 version()
#  M2   ：run(args_text) / stop()
#  M3   ：run_web(args_text)（mitmweb，需要 build.gradle 里 extractPackages("mitmproxy")）
import asyncio
import errno
import logging
import os
import shlex
import socket
import sys
import threading
import traceback

from mitmproxy import options

HOME = os.environ.get("HOME", "/data/user/0/io.mitmlauncher/files")
LOG_FILE = os.path.join(HOME, "launcher.log")
# mitmproxy 的 confdir 默认是 ~/.mitmproxy，而 HOME 已被 Chaquopy 指到 app 私有目录。
# 首次启动要在这里生成 CA 证书（RSA 2048 + 自签名），手机上是几百毫秒量级 ——
# 这段时间 mitmproxy 不会报任何 hook，所以我们要主动发一条心跳给界面，否则短超时会误报。
CA_FILE = os.path.join(HOME, ".mitmproxy", "mitmproxy-ca.pem")

master = None
running = False
_our_handlers = []

# ★ 本次运行是否**真的起来过**（由 _LauncherAddon.running() 置位）。
#   用来区分「启动阶段就失败」和「跑了一阵子之后才崩」——两者的收尾方式完全不同：
#     False：端口从未 bind 成功，只需同步置 server=False，**绝不能 await**
#     True ：正常关停路径已经处理；异常路径交给进程退出兜底（见 _run_dump 注释）
_was_running = False


# ---------------------------------------------------------------- utilities
def version() -> str:
    """给界面用：显示运行环境版本（M1 之后应能看到 mitmproxy 12.3.1）

    顺手把 logging 建起来并写一行，用来验证「日志 → 文件」这条链路可用
    （M2 的日志尾巴依赖它）。
    """
    _setup_logging()
    try:
        from importlib.metadata import version as pkg_version
        mp = pkg_version("mitmproxy")
    except Exception as e:
        mp = "unknown (%r)" % (e,)
    text = "python %s / mitmproxy %s" % (sys.version.split()[0], mp)
    logging.info("[launcher] %s", text)
    return text


def log_start(args_text: str):
    """M0/M1 遗留的链路验证桩（M2 已由 run() 取代，保留只为不删旧调用）。"""
    _setup_logging()
    logging.info("[launcher] start requested: %s", args_text)


# ------------------------------------------------------------------ 状态机
# 状态只在这一个地方改，任何变化立刻写进 STATUS_FILE，Java 侧轮询这个文件即可。
# 之所以用「文件 + 轮询」而不是 Binder 回调：服务在 :mitm 独立进程里，而进程最
# 后被 killProcess 兜底杀掉时，普通回调/静态变量都传不出去；文件是唯一可靠的
# 跨进程、跨进程死亡的通道。代价是 ~700ms 的延迟，对 UI 来说完全够。
STATE_IDLE = "idle"
STATE_STARTING = "starting"
STATE_RUNNING = "running"
STATE_ERROR = "error"

# 只有这一个文件用于跨进程/跨 UI 传状态。格式：seq|state|detail|pid（detail 可空）
# ★ seq 是单调递增序号，Java 侧靠它判断「这条是不是新的」。不能再用文件
#   lastModified 做变更检测：Android 上 java.io.File.lastModified() 只有「秒」
#   精度，同一秒内的多次状态变更会被整段漏掉（真机上表现为「启动中→失败」
#   卡死在「启动中」，直到看门狗超时）。
# ★ pid 是**写这条状态的进程**（服务跑在 :mitm 子进程里）。主进程拿它做两件事：
#   ① 确认这条状态不是自己或历史遗留写的；② 进程没了 = 代理一定没在跑，
#   这正是 killProcess 兜底（跳过 onDestroy）留下的唯一可靠线索。
STATUS_FILE = os.path.join(HOME, "status.txt")

_state = STATE_IDLE
_state_detail = ""
_state_seq = 0
# RLock（可重入）：_set_state() 内部会用到，若中途有人持锁再调就会死锁。
_state_lock = threading.RLock()


def _set_state(state: str, detail: str = "", callback=None):
    """唯一的写状态入口。线程安全，且立刻落盘。

    callback：可选的 Java 侧 {@code MitmListener} 实例（Chaquopy 传进来的代理对象）。
    文件仍是兜底通道（拆进程 / 进程被杀时静态回调传不回来），回调只是把
    「启动中 → 运行中」这类实时性要求高的变化提前送到 UI，省掉最多 700ms 的轮询延迟。
    """
    global _state, _state_detail, _state_seq
    with _state_lock:
        _state = state
        _state_detail = detail or ""
        _state_seq += 1
        seq = _state_seq
        try:
            os.makedirs(HOME, exist_ok=True)
            # status 里同时放「序号 + 状态 + 一句人话 + 本进程 pid」，UI 一行就能显示完。
            text = "%d|%s|%s|%d" % (seq, state, detail or "", os.getpid())
            tmp = STATUS_FILE + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                f.write(text)
            os.replace(tmp, STATUS_FILE)   # 原子替换，Java 不会读到半截内容
        except Exception:
            logging.warning("[launcher] write status failed:\n%s", traceback.format_exc())

    # ★ 回调刻意放在锁外：Java 侧会顺手刷通知（Binder IPC），持锁做 IPC 会
    #   把状态机卡在别人的回调里。异常也要吞掉，回调失败绝不能影响代理本身。
    if callback is not None:
        try:
            callback.onStateChanged(state, detail or "")
        except Exception:
            logging.warning("[launcher] state callback failed:\n%s", traceback.format_exc())


def get_state() -> str:
    """给 run() 的兜底分支判断用（Java 不调这个，它直接读 status.txt）。"""
    with _state_lock:
        return _state


def _listen_desc() -> str:
    """从 proxyserver 拿真实的监听地址，失败就退化成读 options。"""
    m = master
    if m is None:
        return ""
    try:
        ps = m.addons.lookup.get("proxyserver")
        addrs = ps.listen_addrs()
        if addrs:
            # Address 是 namedtuple(host, port, transport_proto)
            return ", ".join("%s:%d" % (a[0], a[1]) for a in addrs)
    except Exception:
        pass
    try:
        host = m.options.listen_host or "0.0.0.0"
        port = m.options.listen_port
        return "%s:%d" % (host, port)
    except Exception:
        return ""


class _LauncherAddon:
    """只做两件事：把「真正开始监听」和「端口关干净了」这两个时刻报出来。

    这两个时刻 mitmproxy 官方的 hook 里才有，Java 侧猜不出来：
      running　= proxyserver 已经把端口 bind 好了（此时才是真的能连）
      done　　 = 所有 addon 的 done 都跑完，端口已释放
    """

    def __init__(self, callback=None):
        self.callback = callback

    def running(self):
        global _was_running
        _was_running = True      # ★ 端口已经真的 bind 上了，后面失败就是「运行中崩」
        desc = _listen_desc()
        _set_state(STATE_RUNNING, desc, self.callback)
        logging.info("[launcher] proxy listening on %s", desc or "(unknown)")

    def done(self):
        # done 之后 log handler 已被卸载，这里写日志是没输出的，只能写状态。
        _set_state(STATE_IDLE, "", self.callback)


def _raw_log(line: str):
    """绕过 logging 直接追加写文件。

    ★ 专用于 asyncio.run() 返回之后的日志：此时 event loop 已关闭，而 mitmproxy
      的 LegacyLogEvents 仍可能挂在 root logger 上（它持有那个已关闭的 loop），
      任何 logging.* 都会抛 RuntimeError: Event loop is closed，并中断 handler 链。
      直接写文件可以保证「首因」一定落盘、不被二次异常吃掉。
    """
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def _purge_mitmproxy_handlers():
    """摘掉残留在 root logger 上的 mitmproxy handler。

    ★ 为什么需要：stopSelf() 后 :mitm 进程通常仍存活（Android 的 cached process），
      Chaquopy 的解释器与 root logger 的 handlers 会原样带到下一次 run()。
      上一次「启动失败」路径泄漏的 handler 持有已关闭的 event_loop，
      只要还挂着，本次任何 logging.* 都会在遍历到它时抛异常，并**中断 handler 链**
      （排在后面的 FileHandler 收不到记录 ⇒ 日志文件停止更新）。
      按 __module__ 前缀判定，与第三方库的内部字段解耦。
    """
    root = logging.getLogger()
    for h in list(root.handlers):
        if type(h).__module__.startswith("mitmproxy"):
            try:
                root.removeHandler(h)
            except Exception:
                pass


def _detach_master_loggers(m):
    """把本次 master 装在 root logger 上的 handler 摘掉。

    ★ 为什么必须手动做：mitmproxy 只在 Master.done() 里 uninstall，而
      · 端口预检失败是我们自己提前 return（根本不进 run()）；
      · errorcheck 的 sys.exit(1) 发生在 master.run() 的 try 之外，done() 不会被调用。
      两种情况 handler 都会永久泄漏在 root logger 上。
      这里只做属性级同步解绑，绝不 await、绝不触发 addon 生命周期（DoneHook）。
    """
    if m is None:
        return
    for attr in ("_legacy_log_events", "_termlog_addon"):
        try:
            obj = getattr(m, attr, None)
            if obj is not None:
                obj.uninstall()
        except Exception:
            pass


def _setup_logging():
    """把 python logging（含 mitmproxy 的日志）同时写到文件 + stderr(→logcat)。

    ★ 开头先 purge 掉 mitmproxy 的残留 handler：run() 入口时不存在正在运行的
      master（旧 run 已结束、新 run 还没建 master），所以此刻挂在 root logger 上的
      mitmproxy handler 一定是上一次「失败路径」泄漏的，必须清掉。
      然后才移除**我们自己**上次加的 handler（保留别人的，避免误删）。
    """
    global _our_handlers
    _purge_mitmproxy_handlers()
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for h in _our_handlers:
        root.removeHandler(h)
    _our_handlers = []

    try:
        os.makedirs(HOME, exist_ok=True)
        fh = logging.FileHandler(LOG_FILE, mode="a", encoding="utf-8")
        fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
        root.addHandler(fh)
        _our_handlers.append(fh)
    except Exception:
        logging.warning("[launcher] file log disabled:\n%s", traceback.format_exc())

    sh = logging.StreamHandler(sys.stderr)
    sh.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
    root.addHandler(sh)
    _our_handlers.append(sh)

    # 每次进程启动都记一条分隔线，方便在日志尾巴里分辨「这是哪一次运行」。
    logging.info("[launcher] ---- logging ready, file = %s ----", LOG_FILE)


def _patch_platform():
    """Android 上 sys.platform == "android"，mitmproxy 认不出 linux 实现。
    只在用 --mode transparent 时才需要，这里照上游做法补上（无害）。"""
    try:
        import mitmproxy.platform
        if (sys.platform == "android") and (mitmproxy.platform.original_addr is None):
            from mitmproxy.platform import linux
            mitmproxy.platform.original_addr = linux.original_addr
    except Exception:
        logging.warning("[launcher] platform patch skipped:\n%s", traceback.format_exc())


def _parse_options(parser, opts, args_text):
    """统一处理参数：--set 必须 defer，否则 addon 注册的选项会静默用默认值。

    ★ 用 shlex.split 而不是 str.split：
      官方 CLI 的 argv 是**操作系统（shell）已经切分好**的列表，shell 会处理引号
      和转义。我们是自己从一整串文本切的，等于在替代 shell 的角色 —— 而
      `str.split()` 只会按空白切分、**完全不认引号**，于是：

          args_text = '--set foo="a b"'
          str.split()   → ['--set', 'foo="a', 'b"']      ← 值被切坏
          shlex.split() → ['--set', 'foo=a b']           ← 正确

      换行等空白两种切法都等价（都是分隔符），所以切分逻辑的差别只在引号/转义。
      注意 shlex 的规则与 POSIX shell 一致：支持 "…"、'…' 和反斜杠转义；
      若用户输入的引号本身不配对，shlex 会抛 ValueError，这里退化成 str.split()
      并记一条警告 —— 宁可让参数按老办法切，也不要因为一个引号直接崩掉。
    """
    from mitmproxy.tools.main import process_options
    try:
        argv = shlex.split(args_text)
    except ValueError:
        logging.warning("[launcher] unbalanced quotes in args, "
                        "falling back to whitespace split: %r", args_text)
        argv = args_text.split()
    args = parser.parse_args(argv)
    opts.set(*args.setoptions, defer=True)
    process_options(parser, opts, args)
    return args


async def _stop_proxyserver(m):
    """master.shutdown() 不会关 proxyserver，必须这样关才真正释放代理端口。"""
    try:
        proxyserver = m.addons.lookup.get("proxyserver")
        if proxyserver:
            logging.info("[launcher] stopping proxyserver")
            m.options.update(server=False)
            await proxyserver.setup_servers()
    except Exception:
        logging.warning("[launcher] stopping proxyserver failed:\n%s", traceback.format_exc())


def _effective_listen_addrs(opts):
    """从 opts 算出「这次到底会 bind 哪个 host:port」。

    ★ 不要用 mitmproxy 的全局 ctx.options：那要先 import mitmproxy.ctx 并跑在
    它的 event loop 里，在启动早期容易拿到错的 master。这里只用我们自己的
    opts 复刻 proxyserver.configure() 里的同一段推导（mode.parse → listen_host/
    listen_port），保证和真正会 bind 的地址一致。
    返回值可能为空列表（例如 --mode local，压根不监听端口）。
    """
    addrs = []
    try:
        from mitmproxy.proxy import mode_specs
        for spec in opts.mode:
            m = mode_specs.ProxyMode.parse(spec)
            host = m.listen_host(opts.listen_host)
            port = m.listen_port(opts.listen_port)
            if port is None:
                continue
            addrs.append((host or "", int(port)))
    except Exception:
        logging.warning("[launcher] compute listen addr failed:\n%s", traceback.format_exc())
        # 退化：至少把全局 listen_port 拿来试（默认 mode=regular → 8080）。
        try:
            if opts.listen_port is not None:
                addrs.append((opts.listen_host or "", int(opts.listen_port)))
        except Exception:
            pass
    return addrs


def _check_ports_available(addrs):
    """★ 进入 mitmproxy 之前自己先 bind 一次，把「端口被占」这件事挡在门外。

    为什么非要自己查（而不是等 mitmproxy 报错）：
      mitmproxy 报端口占用 = sys.exit(1) → 我们拿到的是 SystemExit，而且它是在
      asyncio 拆 loop 的过程中抛出来的，真机上实测会卡死收尾逻辑（见 _safe_stop
      里的说明）。提前用同步 socket 查一下，失败路径就变成「一次普通的函数返回」，
      既快（<5ms）又绝不会让工作线程卡住。

    返回 None 表示可用；否则返回一句给用户看的中文原因。
    """
    for host, port in addrs:
        s = socket.socket(socket.AF_INET6 if ":" in host else socket.AF_INET,
                          socket.SOCK_STREAM)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            # ★ 不能设 SO_REUSEPORT：那会让「已经被别人占着」的端口也 bind 成功，
            #   预检就完全失效了。SO_REUSEADDR 只影响 TIME_WAIT 复用，是安全的。
            s.bind((host or "0.0.0.0", port))
        except OSError as e:
            if e.errno == errno.EADDRINUSE:
                return "端口 %d 已被占用，请换一个端口" % port
            # 其它错误（EACCES / EADDRNOTAVAIL 等）也当成不可用，但把原始信息带上。
            return "端口 %d 不可用（%s）" % (port, e.strerror or e)
        finally:
            try:
                s.close()
            except Exception:
                pass
    return None


def _ca_missing():
    """CA 证书还没生成 → 首次启动会在启动阶段卡几百毫秒（要现算 RSA 密钥）。

    这段时间 mitmproxy 一个 hook 都不报，界面只能干等。提前发一条 heartbeat
    状态，短超时就不会误判成「卡死」。
    """
    try:
        return not os.path.exists(CA_FILE)
    except Exception:
        return False


async def _safe_stop(master_obj, was_running):
    """统一的收尾。★ 注意它被设计成 async，但 was_running=False 这条路**一个 await 都
    不碰**（协程里没有 await = 不会把控制权交回 event loop = 等价于同步执行），
    这样既能让调用方统一写 await，又不会在 loop 拆解期间死锁。

    ★ 为什么不能无脑 `await _stop_proxyserver()`：
      「端口被占」这种启动期失败，是 mitmproxy 内部 sys.exit(1) 抛出来的
      SystemExit，此时 asyncio 已经在拆 loop。在 finally 里 await 一个会去操作
      asyncio.Server 的协程，真机实测**整个工作线程永远不返回** —— 状态停在
      「正在启动」，服务永久存活，之后每次点启动都被 doStart 的「已在运行」挡掉。
      这就是「第二次启动/换端口也永远超时」的根因。

      而「跑了一阵子才崩」这种情况又**必须**把端口关干净，否则端口泄漏。
      所以用 was_running 分态：
        was_running == False → 端口从未 bind 成功，无需 await，只同步关 server 开关
        was_running == True  → 已经 bind 过，用 1.5s 超时 await 关；超时/异常都吞掉，
                               剩下的交给进程退出（:mitm 进程被杀时内核会收回所有 fd）
    """
    if master_obj is None:
        return
    if not was_running:
        # 同步操作，绝不 await：此时 loop 正在拆，await 就是死锁。
        try:
            master_obj.options.update(server=False)
            logging.info("[launcher] server disabled (startup-failure path, no await)")
        except Exception:
            logging.warning("[launcher] disable server failed:\n%s", traceback.format_exc())
        return

    # running 状态下 loop 还活着，可以正常 await。
    try:
        await asyncio.wait_for(_stop_proxyserver(master_obj), timeout=1.5)
    except asyncio.TimeoutError:
        logging.warning("[launcher] stop proxyserver timed out (1.5s), "
                        "leaving cleanup to process exit")
    except Exception:
        logging.warning("[launcher] stop proxyserver failed:\n%s", traceback.format_exc())


# --------------------------------------------------------------------- M2
async def _run_dump(args_text: str, callback=None):
    global master, _was_running
    from mitmproxy.tools import cmdline, dump

    _was_running = False          # ★ 每次运行都从「没起来过」开始
    opts = options.Options()
    # ★ with_termlog=False：我们没有终端，TermLog 只会往 stdout 打，
    #   还会在 root logger 上多挂一个 handler（多一个泄漏点）。
    master = dump.DumpMaster(opts, with_termlog=False)
    # 我们自己报「真正开始监听」，而不是靠 mitmdump 的 running hook —— 那个
    # 是官方给外部 addon 的，我们注册自己的 addon 更可控，且能拿到监听地址。
    master.addons.add(_LauncherAddon(callback))

    logging.info("[launcher] mitmdump %s", args_text)
    parser = cmdline.mitmdump(opts)
    _parse_options(parser, opts, args_text)

    # ★★ 端口预检：在进 mitmproxy 之前把「端口被占」挡掉（详见 _check_ports_available）。
    addrs = _effective_listen_addrs(opts)
    reason = _check_ports_available(addrs)
    if reason is not None:
        logging.error("[launcher] %s", reason)
        _set_state(STATE_ERROR, reason, callback)
        return                    # ★ 普通返回，不抛异常、不碰 asyncio

    # ★ CA 首次生成要几百毫秒且 mitmproxy 全程没有 hook，先发心跳，避免界面误判卡死。
    if _ca_missing():
        _set_state(STATE_STARTING, "正在生成 CA 证书（首次较慢）", callback)

    desc = ", ".join(("%s:%d" % (h or "0.0.0.0", p)) for h, p in addrs) or "(none)"
    logging.info("[launcher] starting proxy on %s (precheck passed)", desc)
    try:
        await master.run()
    finally:
        # ★ 无论正常关停还是运行中崩溃，都要把监听端口关干净。
        #   分态处理的原因写在 _safe_stop 的 docstring 里 —— 启动期失败绝不能 await。
        await _safe_stop(master, _was_running)

    # ★ 只有 master.run() 正常返回（= 真的起来过又正常关停）才写 idle。
    #   启动失败会在这里之前抛出 SystemExit，直接落到 run() 的 except 分支写 error。
    #   早前这句写在 finally 里，导致「启动失败」也会先写一次 idle，UI 会先闪
    #   「未运行」再跳「启动失败」——现在把两条路径彻底分开。
    _set_state(STATE_IDLE, "", callback)
    logging.info("[launcher] mitmdump stopped")


def _chdir_workdir(workdir: str):
    """把当前工作目录切到 workdir，让用户写的相对路径都相对它。

    ★ 为什么用 chdir 而不是在 Java 侧替换参数里的路径：
      要替换就得先知道「哪个参数的值是路径」，而 mitmproxy 的参数形式很多
      （-s 脚本、-w 输出文件、--set key=value 其中 value 可能是路径…），
      逐个识别既容易漏也容易错。chdir 一次对所有参数自动生效，
      而且「导出时不写具体路径就落在工作目录」这件事也天然成立。
      （上游 PCAPdroid-mitm 的 mitm.py 也是这么做的。）

    ★ 目录不存在就尝试创建：用户可能还没写任何脚本/还没授权。
    ★ 失败（权限不足、路径不存在等）**不抛异常**：代理本身仍然可以跑
      （纯 -p 8080 这种参数不需要工作目录），只是相对路径不可用。
      所以退化成「保持原工作目录」并记一条警告。
    ★ 注意 os.chdir 影响进程内所有相对路径，包括 Python 的 import ——
      若工作目录下有与标准库同名的 .py 可能被 import 到。上游已在生产中使用，
      实践中可接受；这里把工作目录限制在 Download/MitmLauncher 也降低了风险。
    """
    if not workdir:
        return
    try:
        os.makedirs(workdir, exist_ok=True)
        os.chdir(workdir)
        logging.info("[launcher] workdir = %s", os.getcwd())
    except Exception:
        logging.warning("[launcher] cannot chdir to %r, "
                        "relative paths will not work:\n%s",
                        workdir, traceback.format_exc())


def run(args_text: str, workdir: str = "", callback=None):
    """由 Java 在后台线程调用；阻塞直到 stop() 或出错。

    workdir：工作目录的绝对路径（Java 侧用 Files.workDirPath() 算好传进来）。
             会在启动代理之前 chdir 到这里，于是用户写的**相对路径**都相对它
             —— 例如 `-s my_addon.py` 就等于 `<workdir>/my_addon.py`，
             `-w flows.mitm` 就等于写到 `<workdir>/flows.mitm`。
             ★ 这样处理而不是在 Java 侧替换参数里的路径：
               要替换就得知道哪个参数是路径（-s / -w / --set xxx= 各不相同），
               既容易漏也容易错；而 chdir 一次对所有参数自动生效。
               （上游 PCAPdroid-mitm 的 mitm.py 也是这么做的：os.chdir(addons_home)。）
             ★ 空字符串 = 不改变工作目录（保持 Chaquopy 给的主进程主目录）。

    callback：Java 侧的 MitmListener 实例（可选），状态一变就回调过去。
    """
    global running, master
    _setup_logging()
    _patch_platform()
    _chdir_workdir(workdir)
    running = True
    _set_state(STATE_STARTING, args_text, callback)
    logging.info("[launcher] start requested: %s", args_text)
    try:
        asyncio.run(_run_dump(args_text, callback))
    except SystemExit as e:
        # mitmproxy 的 errorcheck 在「启动阶段有 ERROR 日志」时 sys.exit(1)：
        # 参数写错、mode 非法等都走这条路（端口占用已被上面的预检提前拦掉）。
        # ★ 注意 SystemExit 继承自 BaseException，不是 Exception，必须显式抓。
        # ★★ 从这行开始 loop 已关闭，绝不能再 logging.*（见 _raw_log 的说明）。
        code = e.code
        msg = "启动失败（参数有误），退出码 %s" % (code,)
        _raw_log("[launcher] " + msg)
        _set_state(STATE_ERROR, msg, callback)
    except BaseException as e:
        # ★ 这里必须是 BaseException：SystemExit / KeyboardInterrupt / CancelledError
        #   都不继承 Exception。早前漏了它们，状态会永远卡在 starting（真机 bug）。
        msg = "%s: %s" % (type(e).__name__, e)
        _raw_log("[launcher] mitmdump crashed: %s\n%s" % (msg, traceback.format_exc()))
        _set_state(STATE_ERROR, msg, callback)
    finally:
        # ★★ 顺序至关重要：先摘掉 master 装在 root logger 上的 handler，再写任何日志。
        #    否则 asyncio.run 已关闭 loop，日志会炸（本次故障的直接成因）。
        _detach_master_loggers(master)
        running = False
        master = None
        # 注意：_run_dump 正常结束时已经写过 idle 了，这里只在异常分支兜底。
        # 必须走 get_state() 而不是 `with _state_lock: ... _set_state(...)`：
        # threading.Lock 不可重入，那样写会自己把自己锁死。
        if get_state() == STATE_STARTING:
            _set_state(STATE_ERROR, "启动中断", callback)
        _raw_log("[launcher] run() returned")


def stop():
    """由 Java 调用（可在其它线程）。

    master.shutdown() 内部是 loop.call_soon_threadsafe，所以从任意线程调都安全。
    """
    global running
    if not running:
        return
    logging.info("[launcher] stopping mitmdump...")
    running = False
    if master:
        try:
            master.shutdown()
        except Exception:
            logging.warning("[launcher] shutdown failed:\n%s", traceback.format_exc())


# --------------------------------------------------------------------- M3
async def _run_web(args_text: str, callback=None):
    """mitmweb（需要 extractPackages("mitmproxy")，否则 static/index.html 取不到）"""
    global master, _was_running
    from mitmproxy.tools import cmdline, web

    _was_running = False
    opts = options.Options()
    master = web.master.WebMaster(opts, with_termlog=False)
    master.addons.add(_LauncherAddon(callback))
    logging.info("[launcher] mitmweb %s", args_text)
    parser = cmdline.mitmweb(opts)
    _parse_options(parser, opts, args_text)

    # 与 _run_dump 同样的三道保险：端口预检 → CA 心跳 → 分态收尾。
    addrs = _effective_listen_addrs(opts)
    reason = _check_ports_available(addrs)
    if reason is not None:
        logging.error("[launcher] %s", reason)
        _set_state(STATE_ERROR, reason, callback)
        return
    if _ca_missing():
        _set_state(STATE_STARTING, "正在生成 CA 证书（首次较慢）", callback)

    try:
        await master.run()
    finally:
        await _safe_stop(master, _was_running)
    _set_state(STATE_IDLE, "", callback)


def run_web(args_text: str, callback=None):
    global running, master
    _setup_logging()
    _patch_platform()
    running = True
    _set_state(STATE_STARTING, args_text, callback)
    try:
        asyncio.run(_run_web(args_text, callback))
    except SystemExit as e:
        # ★★ loop 已关闭，不能再 logging.*（见 _raw_log 的说明）。
        _raw_log("[launcher] mitmweb 启动失败，退出码 %s" % (e.code,))
        _set_state(STATE_ERROR, "启动失败（参数有误），退出码 %s" % (e.code,), callback)
    except BaseException as e:
        # ★ BaseException 而非 Exception：SystemExit 等不继承 Exception。
        _raw_log("[launcher] mitmweb failed:\n%s" % (traceback.format_exc(),))
        _set_state(STATE_ERROR, "%s: %s" % (type(e).__name__, e), callback)
    finally:
        _detach_master_loggers(master)
        running = False
        master = None
        if get_state() == STATE_STARTING:
            _set_state(STATE_ERROR, "启动中断", callback)
        _raw_log("[launcher] run_web() returned")

package io.mitmlauncher;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/**
 * 「所有文件访问权」权限 + 工作目录的公共逻辑。
 *
 * 为什么单独抽一个类：设置页要用（显示状态、发授权请求），首页也要用（判断状态、
 * 显示红字提示、把工作目录传给 Python）。放一处避免两边各写一份、日后改漏一处。
 *
 * ── 关于「所有文件访问权」（MANAGE_EXTERNAL_STORAGE）──
 *
 * 官方文档：https://developer.android.com/training/data-storage/manage-all-files
 *   · 需要三件事：① 清单声明 MANAGE_EXTERNAL_STORAGE
 *                 ② 用 ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 引导用户去系统页开启
 *                 ③ 用 Environment.isExternalStorageManager() 判断是否已开启
 *   · 它授予对共享存储空间所有文件的读写（除 /sdcard/Android 下的大部分目录），
 *     以及 USB OTG / SD 卡根目录。
 *
 * ★ 为什么用它而不是 SAF（ACTION_OPEN_DOCUMENT_TREE）：
 *   SAF 要让用户选一次目录、拿到的是 content:// Uri，之后所有文件操作都得走
 *   ContentResolver，而 mitmproxy（Python 侧）要的是**真实文件系统路径**。
 *   上游正是因为这一点才同时做了两套（SAF 选目录 + 所有文件访问）。
 *   我们只有自己一个用户、也不上架 Google Play，所以直接用所有文件访问，
 *   让 Python 能用普通路径访问 —— 简单得多。
 *   ⚠️ 代价：这是**受 Google Play 政策限制**的权限（仅限文件管理器/备份/杀毒等类别），
 *      上架会被拒。自己侧载安装则完全不受影响。
 *
 * ── 版本差异 ──
 *   API 30+（Android 11）：MANAGE_EXTERNAL_STORAGE + isExternalStorageManager()
 *   API 23~29：用 READ/WRITE_EXTERNAL_STORAGE 运行时权限
 *   API <23：安装即授予
 */
final class Files {

    private Files() {
    }

    // ------------------------------------------------------------ 工作目录

    /**
     * 工作目录：共享存储下的 Download/MitmLauncher。
     *
     * ★ 为什么用 Environment.getExternalStorageDirectory() 拼，
     *   而不是 Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)：
     *   后者在 API 29+ 已废弃，且返回的路径在部分 ROM 上并不等于用户看到的 Download。
     *   getExternalStorageDirectory() 同样标了废弃，但它返回的是稳定的
     *   /storage/emulated/0，拼出来的路径和文件管理器里看到的一致 —— 我们要的正是这个。
     *
     * ★ 选 Download 而不是 Documents：Download 目录几乎在所有设备上都存在，
     *   而 Documents 在某些 ROM 上是空的甚至不存在；且工具类应用普遍用 Download。
     */
    static File workDir() {
        return new File(Environment.getExternalStorageDirectory(), "Download/MitmLauncher");
    }

    /** 工作目录的绝对路径字符串（给界面显示、以及传给 Python 用）。 */
    static String workDirPath() {
        return workDir().getAbsolutePath();
    }

    /**
     * 确保工作目录存在。
     *
     * ★ 必须在**拿到权限之后**调用：没有权限时 mkdirs 会失败（返回 false），
     *   这是正常的、不是错误，调用方不必当成异常处理。
     *
     * @return 目录存在（或创建成功）
     */
    static boolean ensureWorkDir() {
        File d = workDir();
        return d.isDirectory() || d.mkdirs();
    }

    // ------------------------------------------------------------ 权限状态

    /**
     * 是否已有「访问共享存储」的权限。
     *
     * ★ 这个调用成本可忽略（读系统的一个标记位），所以界面上应当**现查**，
     *   不要缓存到变量里 —— 缓存会在"用户在系统设置里撤销权限""卸载重装"
     *   "系统自动回收权限"这几种情况下变成错误信息。
     */
    static boolean hasStorageAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：所有文件访问权
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6~10：两个传统存储权限都要有
            return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        // Android 6 以下：安装即授予
        return true;
    }

    /**
     * 构造「去系统里开启权限」的 Intent；调用方自行 startActivity。
     *
     * ★ 分开返回 Intent 而不是直接 startActivity：设置页和首页都可能要跳，
     *   且设置页还要处理 startActivity 可能抛的异常（部分 ROM 没有这个页面）。
     */
    static Intent buildStorageAccessIntent(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            // data 用 package: 形式，系统会**直接跳到本应用的那一页**，
            // 而不是一个长长的应用列表 —— 用户体验差别很大。
            i.setData(Uri.fromParts("package", ctx.getPackageName(), null));
            return i;
        }
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", ctx.getPackageName(), null));
        return i;
    }
}

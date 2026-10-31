package com.pcapdroid.mitm;

/**
 * 状态变化的实时回调通道（M2a 新增）。
 *
 * 为什么需要它：
 *   M2a 之前界面完全靠轮询 {@code status.txt} 拿状态，最坏情况要等 700ms，
 *   而且文件用 {@link java.io.File#lastModified()} 做变更检测在 Android 上只有
 *   「秒」精度 —— 同一秒内的多次状态变更会被整段漏掉（"启动中→失败" 就因此
 *   卡死在"启动中"）。改成由 Python 直接回调，路径最短。
 *
 * 为什么是「接口」而不是别的：
 *   Chaquopy 支持把一个 Java 接口实例传进 Python，Python 侧按普通属性访问
 *   {@code callback.onStateChanged(state, detail)} 即可（单抽象方法接口也能直接
 *   当函数调用）。这样 Python 不需要知道任何 Android 类。
 *
 * 线程约定（重要）：
 *   回调发生在 **Python 工作线程**，不是主线程。实现方若要更新 View，必须自己
 *   {@code runOnUiThread(...)} / {@code Handler.post(...)}；只做通知刷新则可以直接
 *   调（NotificationManager 是 Binder 代理，线程安全）。
 *
 * 轮询是否保留：保留。拆进程 / 进程被杀等场景下静态回调传不回来，文件仍是兜底通道。
 */
public interface MitmListener {
    /**
     * @param state  与 Python 侧 STATE_* 对齐：idle / starting / running / error
     * @param detail 一句话补充信息（监听地址、错误原因等），可能为空串
     */
    void onStateChanged(String state, String detail);
}

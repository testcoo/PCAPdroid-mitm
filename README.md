# MitmLauncher

在 Android 手机上本地运行 [mitmproxy](https://mitmproxy.org)，作为一个**显式代理**使用
（把 Wi‑Fi 的代理指向 `127.0.0.1:<port>`）。

- 基于 [chaquopy](https://chaquo.com/chaquopy) 在设备上嵌入 Python 3.13 + mitmproxy 12.3.1，**不需要 root，不需要电脑**
- 只有一个界面：参数输入框 + 启动/停止 + 状态 + 日志尾巴

本项目 fork 自 [emanuele-f/PCAPdroid-mitm](https://github.com/emanuele-f/PCAPdroid-mitm)（GPL-3.0），
但从 **v0.3.0** 起已移除全部上游功能代码（VPN 抓包、PCAPdroid 联动、JS 注入器、addon 管理界面），
只保留 mitmproxy 启动器本身。

---

## 构建与发布

### 用 GitHub Actions（推荐，无需本地环境）

**CI 只由 tag 触发**：

```shell
git tag -a v0.4.0 -m "..." && git push origin v0.4.0
```

### 本地构建（可选）

需要：JDK 17、**宿主 Python 3.13**（Chaquopy 要求，版本必须匹配）、Android SDK（platform 34）。

```shell
git clone --recursive <你的 fork>
cd mitmlauncher-live
./gradlew assembleArm64Debug
# 产物: app/build/outputs/apk/arm64/debug/*.apk
```

---

## License

GPL-3.0（继承自 PCAPdroid-mitm），见 [`COPYING`](COPYING)。


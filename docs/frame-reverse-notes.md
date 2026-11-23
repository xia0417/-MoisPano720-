# 逆向笔记：从零到 17 字节

这份记录是为了让你知道**哪些路走不通**，少花时间。真实时间线是「抓包 → 猜帧 → 试时序 → 标定速度」，中间走过不少弯路。

## 1. 先确认能连

Android 蓝牙设置里能看到 `TL-6A_201810000000`，但**配对没用**（配对成功也发不了指令）。用任意 BLE 调试 App（nRF Connect）连上去看 GATT：

```
Service 00001910-0000-1000-8000-00805f9b34fb
  ├ 0000fff1-...  写(without response)
  ├ 0000fff2-...
  ├ 0000fff3-...
  └ 0000fff4-...  读/通知
```

只有 `fff1` 是可控的。`fff4` 挂了通知，但即使打开通知、让云台转，也收不到有意义的数据（没有角度回报）。

## 2. 抓包

「开发者选项 → 启用蓝牙 HCI 嗅探日志」，用官方 App 转一次（拖动全景预览，或按它的方向键），然后：

```bash
adb shell ls /data/misc/bluetooth/logs/     # 需要 root，或用 bugreport 里的 btsnoop
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log
```

Wireshark 打开（`View → Bluetooth → HCI/ATT`），过滤：

```
btatt.opcode == 0x52      # ATT Write Command (without response)
```

会看到一串 `Sent Write Command, Handle 0x00??`，value 如下：

```
11 01 55 01 02 08 02 00 01 03 88 88 88 88 00 0d 0a
```

固定部分一眼可分：头 6 字节 + 5 个 88 + 尾 3 字节，中间夹着 2 字节变值与 2 个单字节。一次拖动里翻十几个包，把变值和你拖的角度对上，就得到：

```
V = round(|θ| / 360 * 4096)
DIR: 右 = 01，左 = 00
MODE: 拖动速度不同 → 1..4
```

> Handle 每台手机可能不同，不必记；靠 UUID 定位更稳。

## 3. 试重放：第一道坑

把抓到的 17 字节**原样**从自己的 App 发给 `fff1`，云台**纹丝不动**。

排查顺序（按顺序都试过，都没用）：

1. 以为要用 with-response → 改用 `WRITE_TYPE_DEFAULT`，卡住（设备不回 ATT 应答）；
2. 以为要配对 → 配对后重连，还是不动；
3. 以为帧要加密/加校验 → 抓包里没有校验位，字节数固定 17；
4. 以为是 MTU 问题 → 申请 MTU 100，依然不动；
5. 以为 `fff4` 有握手 → 打开通知、等它吐数据，没有任何必需的回包。

真正的答案在抓包细节里：**每个执行帧前面 600 ms 左右都有一帧 `deg=0`**（`... 00 00 ...`）。当时先把这帧当成了"开始拖动"的冗余包，删掉后才发现它是必须的。

## 4. 试时序：二分

补上唤醒帧后，用不同的间隔扫：

| 间隔 | 结果 |
|---|---|
| 0（连发） | 不动 |
| 100 ms | 偶尔动 |
| 300 ms | 大部分动 |
| **600 ms** | 稳定 |
| 1000 ms | 稳定但慢 |

取 600 ms。

## 5. 标定速度档

对每档发 `+180°`，同时用另一台手机录像（或对着刻度盘），计 3 次取平均：

```
mode1 ≈ 19.0 s → 105 ms/deg
mode2 ≈ 29.5 s → 164 ms/deg   ← 最慢
mode3 ≈  5.5 s →  31 ms/deg   ← 最快
mode4 ≈ 14.3 s →  80 ms/deg
```

注意**不是** `1<2<3<4` 的直觉顺序。

## 6. 静态分析（可选）

抓包之后做静态分析只是**确认**帧骨架，不是必需。走过的路：

- 官方 App 用 360 加固：`lib/arm64-v8a/` 有 `libjiagu.so`、`libjiagu_a64.so`、`libjiagu_x64.so`、`libjiagu_x86.so`；dex 全部被壳保护，jadx 打开是空壳。
- 但加固**只护 dex**，业务 so 是明文的：`libcorenew.so`（约 3.6 MB，未 strip）。
- `llvm-readelf --dyn-syms libcorenew.so | grep -i rotator` 得到：
  `Java_com_dermandar_dmd_1lib_VRkitRotatorManager_sendCommand`（file offset 0xb3034，588 字节）。
- 反汇编（Ghidra/objdump）能看到常量 `0x11,0x01,0x55,0x01,0x02,0x08`、`0x88` 填充、`0x0D0A` 结尾，以及 `V` 的乘法与方向分支 —— 与抓包结论一致，确认。

出于版权考虑，本仓库**不附带**反编译产物（不提供 so 文件、不提供反汇编文本）。上面给的是方法与符号名，足以复现。

## 7. 还试过但没必要的

- frida gadget 注入 hook 那个 JNI：准备了 `libgadget.so`，但静态+抓包已经够了，没走；
- 用 nRF Connect 手动逐字节试：太慢，只用来验证方向位；
- 猜"是否支持绝对角度定位"：固件不回报角度，`fff4` 也没这个字段，放弃。

## 8. 建议给后来者

先抓包拿到 17 字节，再补 600 ms 唤醒时序，这两步能解决 95% 的问题。静态分析只在"没有可抓包的环境"时才需要。

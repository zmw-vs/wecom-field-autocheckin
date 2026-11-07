# 企业微信自动打卡 App —— 代码修改任务书（可直接交给 AI 执行）

> 使用方式：把本文件全文 + 现有 6 个 Kotlin 源码文件一起交给 AI，要求它按本文件逐条修改并输出完整代码。
> 包名：`com.zmw.autocheckin`　｜　兼容 Android 10 ~ Android 14（minSdk 29 / targetSdk 34）

---

## 一、项目背景与设计意图（务必读，不要改架构）

本 App 通过 `AccessibilityService` 模拟点击企业微信（`com.tencent.wework`）打卡按钮，实现自动打卡。整体链路：

1. `CheckInScheduler` 用 `AlarmManager` 注册闹钟（上班 / 下班两个时间点，按每周勾选的工作日重复）。
2. 闹钟触发 → `AlarmReceiver`：**设备锁屏时不下发唤醒，只发通知并把任务存入 pending 待办；用户解锁后再执行**（`ACTION_USER_PRESENT`）。
3. `CheckInAccessibilityService` 收到解锁广播 / 或用户手动打开企微时，拉起重试队列，扫描页面、找到"打卡"按钮并点击。
4. `passiveScan()`：用户自己手动打卡时，通过无障碍事件被动识别页面上的"已打卡"状态，避免重复打卡。
5. `Prefs` 负责配置、今日打卡完成状态（按日期字符串）、日志（滚动保留 150 行）。
6. `Notifier` 负责通知。

**核心约束：以"最省电"为第一优先级。** 现有实现的省电取向（不唤醒 CPU、不解锁、只在解锁/用户主动打开时干活）是对的，**不要改成 RTC_WAKEUP、不要加 WakeLock、不要改成前台常驻服务**。

---

## 二、🔴 严重 Bug（必须修复）

### Bug-1　`SlotResolver.slotForMinute` 时段判断算法错误（会直接导致漏打卡）
现状：
```kotlin
fun slotForMinute(ctx: Context, curMinute: Int): String {
    val up = Prefs.upMinutes(ctx)
    val down = Prefs.downMinutes(ctx)
    return if (Math.abs(curMinute - up) <= Math.abs(curMinute - down)) "上班" else "下班"
}
```
问题：用"到两个时间点的绝对距离"判断归属，中午之后会算错。
举例（上班 09:00 = 540，下班 18:00 = 1080）：13:00（780）→ 距上班 240、距下班 300 → 被判为"上班"，实际应为下班时段。
后果：用户下午手动打卡后，`passiveScan()` 会把状态标记成"上班已完成"，之后下班闹钟直接跳过 → **当天漏打下班卡**。

修改要求：
- 废弃 `Math.abs` 距离算法，改为**时间区间判定**；
- 判定规则：`curMinute` 落在 `[上班时间, 下班时间)` 之间 → "下班"前置逻辑要写清楚，推荐实现方式：
  - 若 `down > up`（正常班次）：`curMinute < up` → 上班；`curMinute >= up && curMinute < down` → 需按"最接近哪个已过时间点"或按中午分界判定（**优先实现：以 `(up + down) / 2` 为中点，中点前算上班、中点后算下班，且中点本身可配置**）；
  - 若 `down < up`（跨夜班）：做跨零点处理（`curMinute >= up || curMinute < down`）。
- 增加日志输出：识别出的 slot 名称 + 当前时间。
- 不要硬编码 12:00，中点由 up/down 计算得出。

### Bug-2　`AccessibilityNodeInfo` 全部未 `recycle()`，内存泄漏（长期运行必被杀服务）
涉及：`rootInActiveWindow`、`findAccessibilityNodeInfosByText` 返回的所有节点，在 `passiveScan()`、`exists()`、`clickTarget()` 中全部没有回收。
后果：无障碍服务常驻，内存持续上涨，系统回收后自动打卡失效。

修改要求：
- 每一个 `AccessibilityNodeInfo` 使用完毕必须 `.recycle()`；
- **遍历 parent 链时不能回收正在使用的当前节点**，只能在确定不再需要时回收（注意这里直接 recycle 父节点会导致当前节点失效，安全做法是：遍历时只对临时取得的子节点列表做回收，父节点引用交给系统管理，或统一在循环结束后按顺序回收）；
- 增加空安全判断；
- 特别注意 `findAccessibilityNodeInfosByText` 返回的是 `List<AccessibilityNodeInfo>`，**列表中每个元素都要回收**；
- 不要缓存 NodeInfo 到成员变量/全局变量。

### Bug-3　`unlockRx` 广播可能重复注册导致崩溃
现状：在 `onServiceConnected()` 里直接 `registerReceiver(unlockRx, ...)`。系统可能多次回调 `onServiceConnected`（服务被重新绑定），第二次注册会抛 `Receiver already registered`，服务 crash。

修改要求：
- 增加成员标记 `@Volatile private var unlockRxRegistered = false`；
- 只有 `false` 时才注册，注册成功后置 `true`；
- `onDestroy()` / `onUnbind()` 中若已注册则 `unregisterReceiver` 并置回 `false`，用 try-catch 兜底；
- 不要把注册放在可能被多次调用的方法里而无保护。

---

## 三、🟡 业务逻辑缺陷（不崩溃但会出错，建议一并修复）

### Bug-4　`CheckInScheduler.nextTriggerAt` 时间计算隐患
- 循环 `offset in 0..7`，每次重新 `Calendar.getInstance()` 再 `add(DAY_OF_YEAR, offset)`：月末/年末跨月跨年时，从"当天"反复偏移容易产生日历回滚错乱。
- 只向后找 7 天，若 7 天内无勾选工作日直接返回 `-1`，无兜底。

修改要求：以同一个基准 `Calendar` 做 `clone()` 再偏移；扩展搜索范围（例如 0..14）；找不到时写明确日志并返回 `-1`，调用方需能处理 `-1`（不设置闹钟但要记日志）。

### Bug-5　静态 `alive` 持有 Service 实例
`@Volatile private var alive: CheckInAccessibilityService? = null`。系统直接 kill 进程不走 `onDestroy` 时，`alive` 不会被置空，外部拿到失效实例。

修改要求：`onServiceConnected` 赋值、`onDestroy`/`onUnbind` 置空；所有通过 `alive` 调用的地方先判空并 try-catch；不要在别处长期持有该引用。

### Bug-6　`passiveScan()` 页面校验太宽松
- 仅凭文案 `exists(root, DONE_TEXTS)` 就标记今日已打卡；企微文案改动会失效；停在昨天的旧打卡页面上，今天打开会误判。
- 修改要求：增强校验（同时要求"上下班/外出打卡"上下文 + 已打卡文案）；**只有页面上下文明确属于"今天"时才标记完成**（能取到日期文本就比对 `today()`，取不到则只在识别到当日时间上下文时标记）；日志打印识别到的全部关键文本。

### Bug-7　pending 待办超时丢弃无任何提示
`PENDING_STALE_MS = 2 小时`，超期只写日志直接丢，用户不知情。

修改要求：丢弃时通过 `Notifier` 发一条通知（如"打卡任务已超期未执行，请手动打卡"），并写日志。

### Bug-8　`onInterrupt()` 空实现，中断后 tick 循环仍在跑
修改要求：`onInterrupt()` 中 `running = false`，并 `handler.removeCallbacksAndMessages(null)`，终止当前打卡流程。

### Bug-9　`clickTarget` 完全相等匹配 `label != t` 太脆
UI 文本带前后空格或 `contentDescription` 有附加文案时会匹配不到。

修改要求：`trim()` 后再相等判断（**保持相等语义，不要改成 contains，避免误点"打卡统计/打卡记录"**）；匹配不到时输出目标候选节点的全部文本，方便排查。

### Bug-10　`Prefs.log` 日志切割不健壮
`lines().take(150)` 在单条日志含换行时会错乱。

修改要求：写日志时每条强制单行化（把内部 `\n` 替换为空格），再按行截断保留 150 行。

### Bug-11　`BootReceiver` 在新版 Android 收不到开机广播
Android 13+ 应用未被用户手动启动过一次时收不到 `BOOT_COMPLETED`，闹钟不会重建。

修改要求：代码层无法绕过，**不要试图规避系统限制**；仅在日志和 UI 权限检查页写明该限制提示用户。

### Bug-12　打卡完成状态用 `yyyyMMdd` 字符串
系统时间被手动改 / 跨时区会错乱。

修改要求：保留字符串实现（省电、简单），但统一用设备本地时区取日期，并记录时间戳日志，风险写进注释，不做过度设计。

---

## 四、🔋 省电专项优化（核心诉求，逐条落实）

1. **`onAccessibilityEvent` 入口短路**：进入方法第一件事判断 `Prefs.isDone(上班) && Prefs.isDone(下班)`，两者都完成 → 直接 `return`，不做任何节点解析。
2. **`passiveScan` 限频从 1000ms 放宽到 1500~2000ms**；且只有"今天至少还有一个 slot 未打卡"时才执行扫描；两个都已完成直接跳过。
3. **tick 参数调整**：`TICK` 由 1300ms 提升到 1600~1800ms；`MAX_TICK` 由 20 降到 12~15；超时立即结束。
4. **Handler 清理**：所有分支（成功 / 失败 / 超时 / 中断）都要 `handler.removeCallbacksAndMessages(null)`，杜绝残留 Runnable 在后台持续跑。
5. **闹钟策略保持不变**：继续用 `AlarmManager.RTC`（不 `WAKEUP`），锁屏不唤醒 CPU，顺延到解锁执行。**禁止**改成 `RTC_WAKEUP`，**禁止**加 `PARTIAL_WAKE_LOCK` 或其他任何 WakeLock。
6. **unlockRx 收到解锁广播后先判断**：今日两个 slot 都已完成 → 直接 return，不起任何工作。
7. **不要改成前台 Service**：前台常驻通知违背本项目省电目标。
8. **无障碍服务不持有大对象/NodeInfo 全局引用**，局部用完即释放并 recycle。
9. 无障碍配置里 `packageNames` 只监听企微（见第五节），避免全系统事件唤醒。

---

## 五、🟢 健壮性与体验优化

1. `MainActivity` 权限检查：电池优化只做展示，补一句说明"若被系统电池优化杀掉无障碍服务，需用户重新手动开启无障碍"。
2. `Notifier`：所有 `notify()` 加 try-catch 捕获 `SecurityException`（通知权限未授权）并写日志。
3. `clickTarget` 向上找可点击父节点最大深度 6 保留；遍历到上限仍未找到时输出日志。
4. 闹钟触发时若该 slot 已完成，直接跳过，不拉起企微。
5. "立即测试一次"入口执行前先打印完整日志，便于调试。
6. 保留原有日志输出，关键分支（入口短路、slot 识别、页面识别、按钮匹配、pending 超期、闹钟计算）补上调试日志。

---

## 六、AndroidManifest.xml 完整配置

### 6.1 权限声明
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```
> 说明：
> - `SCHEDULE_EXACT_ALARM`（Android 12/S 起）普通应用只能引导用户去系统设置手动授权，代码不能直接申请；
> - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 只声明权限，跳设置页由代码完成；
> - `POST_NOTIFICATIONS`（Android 13/T 起）需运行时申请；
> - **绝对不要加 `WAKE_LOCK` 权限**（省电约束）。

### 6.2 application 内组件
```xml
<!-- 主界面 -->
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>

<!-- 无障碍服务（核心，缺一不可） -->
<service
    android:name=".CheckInAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>

<!-- 闹钟接收器 -->
<receiver
    android:name=".AlarmReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.zmw.autocheckin.action.DUE" />
    </intent-filter>
</receiver>

<!-- 开机 / 应用更新重建闹钟 -->
<receiver
    android:name=".BootReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

### 6.3 新建 `res/xml/accessibility_service_config.xml`
> **省电关键**：`packageNames` 只监听企业微信，不监听全系统。
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeViewClicked|typeViewFocused|typeViewScrolled"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagIncludeNotImportantViews"
    android:canPerformGestures="false"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_desc"
    android:packageNames="com.tencent.wework" />
```
要点：
- `packageNames="com.tencent.wework"` **不得删除、不得改为监听全部应用**；
- `canRetrieveWindowContent="true"` 必须开，否则拿不到节点；
- `canPerformGestures="false"`，本项目只用 `performAction(ACTION_CLICK)`，不需要手势；
- `flagIncludeNotImportantViews` 视实际识别效果保留，若识别不到按钮才加。

### 6.4 `res/values/strings.xml` 新增
```xml
<string name="accessibility_desc">外勤打卡助手：读取企业微信页面，模拟点击打卡按钮；不上传数据，不修改定位。开启后才能实现自动打卡。</string>
```

---

## 七、给 AI 的硬性修改约束（必须全部遵守）

```
1. 包名固定 com.zmw.autocheckin，不得修改；minSdk 29，targetSdk 34。
2. 省电第一优先级：禁止新增任何 WakeLock（PARTIAL_WAKE_LOCK 等）；禁止把 AlarmManager.RTC 改成 RTC_WAKEUP；
   禁止改成前台常驻 Service；保持"锁屏不唤醒、顺延到解锁执行"的原有业务设计。
3. 无障碍 AccessibilityNodeInfo 所有实例必须 recycle()（包括 findAccessibilityNodeInfosByText 返回的列表元素），
   注意不得回收正在遍历使用的节点导致后续访问失效。
4. 不新增任何第三方依赖库，全部使用原生 Android API。
5. 不重构整体架构，在现有 6 个文件上就地修补；不改动 UI 布局结构与交互逻辑。
6. 每个文件输出完整可编译代码，不要用 "....省略" 占位。
7. 保留原有日志输出，并在关键分支补充调试日志（入口短路、slot 识别、页面识别、按钮匹配、pending 超期、闹钟计算）。
8. 兼容 Android 10 ~ Android 14（含 Android 12 精确闹钟权限、Android 13 通知权限、Android 14 广播与后台限制）。
9. 每一条修改请在代码注释里标注对应的 Bug 编号（如 // Bug-1 修复：...），便于复核。
10. 修改完成后输出一份"改动清单"：Bug 编号 → 改动文件 → 改动函数 → 一句话说明。
```

---

## 八、验收清单（AI 交付后逐条核对）

- [ ] `slotForMinute` 不再使用 `Math.abs`，跨中午/跨夜判定正确
- [ ] 所有 NodeInfo 有 recycle，未回收导致遍历失效
- [ ] `unlockRx` 有注册标记 + 注销，反复 `onServiceConnected` 不崩
- [ ] `nextTriggerAt` 用 clone 偏移，兼容月末/跨年，`-1` 有兜底
- [ ] `alive` 有置空与判空
- [ ] 双 slot 已完成时，`onAccessibilityEvent` / `unlockRx` 直接短路
- [ ] `passiveScan` 间隔 ≥1500ms，且双完成时完全不扫描
- [ ] tick 间隔 ≥1600ms，MAX_TICK ≤15，所有分支清理 Handler
- [ ] 全项目无 WakeLock，闹钟仍为 RTC
- [ ] pending 超期有通知提醒
- [ ] `onInterrupt` 能终止 tick
- [ ] Manifest 与 accessibility_service_config.xml 按第六节配置
- [ ] 无新增第三方库

---

## 九、需要开发者自己在系统里手动完成的事（代码无法代劳）

1. 在系统设置里手动开启无障碍服务（代码无法自行开启）。
2. 授予"精确闹钟"权限（Android 12+，跳系统设置页）。
3. 授予通知权限（Android 13+），并选择忽略电池优化。
4. 企业微信需保持登录状态；无障碍服务被系统杀掉后需重新开启。

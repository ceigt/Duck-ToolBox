Duck ToolBox 0.2.0-renewal.3 — Fork 测试版
原项目：https://github.com/eltavine/Duck-ToolBox/tree/v0.2.0-Stable
原作者：Eltavine、KOW、MhmRdd；原 MIT 许可保留于 LICENSE。
Fork 源码与构建：https://github.com/ceigt/Duck-ToolBox
此包不是原作者发布的正式版本。保留上游独立 GPLv3 Play Integrity 引擎及许可。
该内置引擎在全新安装时默认关闭；已有设置不变，避免与独立 PIF 模块冲突。

用途与默认设置
1. 在现有 Duck 模块内加入续签后台和“本机 RKP 签发”页面中的续签面板。
   模块 ID 仍为 duck-toolbox；不是额外安装第二个 KSU 模块。
2. 首次安装默认关闭。首次开启必须明确选择以前在本机签发成功的协议，
   并确认授权到期后的联网签发。不会自动尝试 V2、AIDL V1、Native V1。
   开启或关闭状态均可选择接口并点击“保存接口”；保存不会立即签发或切换
   开关。保存的选择在下次打开和重启后保留；处理证书期间禁止更换接口。
3. 手机运行期间约每 6 小时在本地读取证书链，按整条链最早的到期时间计算。
   剩余不超过 24 小时才触发续签；并不是提前一天的精确定时闹钟。
   关机、深度休眠、进程被终止、网络不可用都可能延后检查或完成时间。
   后台不持有唤醒锁；每 5 分钟活跃运行时间检查一次时钟，证书检查仍由
   6 小时的墙钟门限控制。WebUI 打开时读取状态不计为联网签发。
4. 每滚动 24 小时最多尝试两次，每次至少间隔 6 小时；明确的服务器错误
   可延长至 12 小时。这是本功能自行设置的上限，不代表 Google 公布的限额，
   也不能保证不会被服务器限流。首次无法确定请求结果时会暂停。

兼容范围
- 此安装包仅包含 arm64 原生后端，针对已检查的一加 Ace 3 / Android 16。
- 需要 root、TEESimulator 已启用且 versionCode 至少为 75。
- 只支持 version=1 配置、单个 default 配置、mode=patch、keybox=keybox.xml。
- 现有和新的 Keybox 必须为一把 ECDSA P-256 密钥和完整 Google 根证书链。
  不支持本地自签链、多配置、RSA+EC 混合 Keybox 或其他模块的自动续签。
- 当前手机证书的 CSR 形状已在本地确认属于 V1；它本身不能区分 AIDL V1
  与 Native V1。请按上次成功签发时的记录选择接口，不要默认选择 V2。
  如果不知道上次使用的接口，先保持续签关闭，核对成功记录后再开启。

替换与失败处理
1. 使用本次重新编译的 bin/duckd 的 rkp-device provision，固定一个所选协议。
   继续使用原有厂商 KeyMint 接口；兼容性与手动签发一样依赖固件。
2. 签发前持久化请求状态、冷却时间和次数，并取得独占锁，避免重入。
   超时、崩溃、返回不明结果或多数签发错误会暂停，不盲目重试。
3. 新文件在本地验证私钥匹配、P-256、X.509 路径及约束、已固定的 Google
   根公钥、全链有效期，而且到期时间必须晚于旧证书且剩余超过 24 小时。
4. 只在续签期间下载 Google 公开完整吊销列表，在本地比较序列号。
   列表不可用时保留待应用证书和旧文件，后续复用它，不再次申请证书。
5. 在私有目录备份原文件，记录可恢复事务，再原子替换 keybox.xml。
   读取替换之后完整的新日志：同一原生控制线程的两次 Keybox 解析、
   default/patch 配置暂存、配置提交 epoch 和相同 epoch 的成功 ACK 必须对应。
   日志轮转、缺失、线程或编号不符、新配置插入、失败记录均不能确认成功。
   这是指定 TEESimulator 日志协议的加载证据，不是独立的硬件证明测试。
   当前 TEESimulator 提交的 ConfigStore 观察整个目录的 XML 重命名，
   因此不必改写配置或重启 keystore 来触发重载。
6. 未得到加载确认则恢复旧文件，并暂停自动操作。重启时若有中断事务，
   先恢复旧文件；如果恢复或加载也无法确认，明确显示需要人工检查。
   加载成功只证明模块接受了文件，不等于验证了 Google 的 DEVICE/STRONG。
7. 关闭开关会停止下一次自动签发；已经开始的替换可能完成或回滚后才停止。

清理范围
- 只清理本功能记录在清单中、已到期、内容未改变的输出目录和备份。
- 新证书已确认加载后，才有资格清理旧文件；未过期备份继续保留。
- 当前使用的证书、待应用证书、手动签发的历史输出和用户自己的备份不删。
- 若文件被改动、路径含符号链接或目录有额外文件，保留并等待人工处理。
  删除是文件系统层面的删除，不承诺闪存上的物理安全擦除。

隐私与文件位置
- 普通检查只读本地文件，不向 Google 发送“到期验证”。
- 真正续签沿用 Duck 的公开 CSR/设备证明数据请求，私钥在本机生成与保留。
- 下载吊销列表不发送证书序列号、Keybox 或私钥作为请求参数或请求正文。
- 新功能的状态只包含时间、状态码、路径和文件摘要；不记录 PEM、XML、CSR。
- 私有状态、备份和清单：/data/adb/duck-toolbox/var/renewal（root 专用）。
- 原生签发输出：/data/adb/duck-toolbox/var/outputs/rkp-device-*。
- 应用目标：/data/adb/teesim/keybox.xml。
- 本次交付包和源码不包含这台手机的 Keybox、CSR、证书或私钥。

安装与回退
1. 先导出/保留现有 Duck 的安装包。用 KernelSU 的“从文件安装模块”选择
   此 ZIP，作为同 ID 模块的更新安装；按管理器提示重启。
2. 打开 Duck → 本机 RKP 签发 → RKP 自动续签（本地修改版）。核对到期时间。
3. 选择已成功的协议，再开启自动续签。“立即检查”仍遵守到期窗口和限流，
   不会为了测试而提前申请证书；没有提供强制提前签发按钮。
4. 本地版取消 module.prop 中的稳定更新地址，避免无意被上游覆盖而失去功能。
   上游的手动更新/测试版更新仍可能覆盖本地代码，需要自行保留安装包。
5. 回退时先在面板关闭续签，再安装原版 Duck 并重启。续签私有状态保留在
   原来的 Duck 数据目录中；卸载 Duck 会按原项目行为删除该数据目录。

验证范围
- 本版基于上游 v0.2.0-Stable（273f49f18f28903cae395850abc85ed36a3b9a75）。
- GitHub Actions 运行原生后端、前端、模块脚本、独立引擎和续签合成数据测试。
  每个安装包的构建与测试结果见对应 Actions 运行记录。
- 续签后台使用临时合成证书进行离线测试；CI 不连接手机或 Google 进行签发。
- 前版已于 2026-10-26 完成安装、重启、当前证书与完整性检查；
  本版修复需重新安装验证。真实到期自动续签和跨周运行仍待后续测试。
- 从旧版迁移会保留续签状态及 TEESimulator 配置；一加 Ace 3 的
  ace3 attestation product correction 模块仍应保持启用。

源码结构与复现
renewal/src/dev/duck/renewal/Keybox.java：本地证书与路径检查。
renewal/src/dev/duck/renewal/Renewal.java：可测试的持久化续签/事务状态机。
renewal/src/dev/duck/renewal/Main.java：Android 网络状态、原生签发、加载确认。
module/bin/duckctl.sh、renewal-scheduler.sh：命令入口与后台调度。
ui/src/features/rkp-device：面板、接口与中英文文字；其他语言沿用新增英文内容。

原生 duckd 必须从本版源码重新编译，不能复用旧版或原版二进制。
新版本需要 Rust 1.89+、Android NDK r30 / API 26，构建目标 aarch64-linux-android。
原生签发、网络下载、设备 ID、手动安装均有源代码改动。
根证书为 Google 的公开 attestation 根证书；未来轮换可能需要更新该公开文件。
参考：https://developer.android.com/privacy-and-security/security-key-attestation
热重载实现：https://github.com/JingMatrix/TEESimulator/blob/fcf1738/app/src/main/java/org/matrix/teesim/ConfigStore.kt

构建需要 JDK 17+、Python 3、Node 24+、pnpm、Android SDK 的 android.jar 和 D8/R8。
测试用 org.json：org.json:json:20250517（Android 本身提供 JSON；不打入发行 DEX）。
1. python renewal/build.py --java-home <JDK目录> --r8-jar <r8.jar> --android-jar <android.jar> --json-jar <json.jar>
2. 依照 .github/workflows/ci.yml 安装完整 Android SDK / NDK / CMake，
   执行 python scripts/build-play-integrity.py 构建新版附带的 GPL 引擎及许可证。
3. 在 ui 目录执行 pnpm install --frozen-lockfile、pnpm typecheck、pnpm build。
   构建时设置 VITE_PIF_SOURCE_COMMIT 为当前完整 Git 提交 SHA。
4. 使用 cargo build --locked --release -p duckd --target aarch64-linux-android 构建，
   执行 python scripts/package-module.py --channel stable --output <目标ZIP路径>。
   统一打包器检查架构、DEX、前端、引擎哈希、许可及对应源码提交。
   引擎源码与构建脚本必须先提交到 GitHub；不能用未提交的修改打包发布。
5. 离线测试可用 renewal/tests/fixtures.py 生成一次性合成证书，用 javac 编译
   RenewalTest 后运行 dev.duck.renewal.RenewalTest <合成证书目录> <测试临时目录>。
   发行 DEX 不含测试类，源码包不含生成的测试私钥。

保留的修复与验证边界
- 可放弃待应用证书，不改动现用文件，不重置请求次数或冷却时间。
- 时钟校正后恢复会保留请求次数，至少冷却 24 小时。
- Duck 内的 TEESimulator 手动安装与自动替换共享 Java FileLock 和恢复日志。
  native 签发另有统一签发锁；显式导出不能覆盖已有文件或符号链接。
  外部 root 工具不遵守 Duck 的锁，仍须避免同时修改 TEESimulator 配置。
- 手动导入先验证 PEM、密钥匹配、链签名与有效期。保留 AOSP/自签用途，
  不把所有手动导入都限制为 Google 根。TEESimulator 另做 PKIX 验证与加载确认。
  当前事务型 TEESimulator 手动替换要求存在可备份的 keybox.xml，且与上面的
  单 default/patch 配置兼容；不支持首次空目录安装或多配置自动替换。
- 手动备份使用唯一名称，保留供人工恢复，不参与自动到期清理。
- 其他后端只显示文件写入结果，明确标记未确认加载。
- 状态显示按文件 SHA-256 与根证书集合缓存本地验证结果；时钟回退重新校验。
  空闲界面每 60 秒读状态，事务期间每 10 秒；操作边界仍完整验证。
- 诊断只输出固定错误码与步骤，不输出原始异常、私钥、CSR 或服务响应。
- 设备 ID 默认优先使用 *_for_attestation，冲突需核对确认；设备操作已完成而
  报告保存失败时单独提示，避免误判需要再次写入。
- CI 加入独立 Java 离线测试、DEX 编译与发布依赖。打包校验原生和 DEX 版本。
- 离线测试入口：python renewal/test.py --java-home <JDK> --json-jar <json.jar>。
  使用现场生成的合成证书，不连接手机、不向 Google 请求证书。

安装不会替换现用证书或 TEESimulator 配置；已有续签开启状态会继续生效。
实机功能测试由用户另行安排，当前 TEESimulator 的日志兼容性、热加载及回滚尚待验证。
内置公开 AOSP 示例证书已于 2026 年 1 月到期；可离线解析，但安装校验会拒绝。
RSA-1024 仅保留旧软件证明材料的一致性验证，不用于生成或推荐新证书。
Windows 不能直接运行 Android/Unix 专用的原生测试；这些在 Linux CI 中保留。
具体执行结果见对应版本的 GitHub Actions 记录。

本版审查修复
- 在线仓库改为系统浏览器打开，下载后本地导入；过期 AOSP 入口禁用。
- 手动安装独立于自动续签资格，支持 generation、多配置及自定义 XML 目标。
  需要 TEESimulator code>=75 的日志协议；其他配置文件保持原样。
- 首次安装支持原文件不存在；若此时替换失败，没有旧证书可加载，明确要求检查运行状态。
- 未完成事务可独立恢复，不需开启续签；暂停时保留原诊断。
- 导出验证证书签名、有效期和 CA 约束；默认 Google 端点验证固定根公钥。
- 本地导入有界读取，吊销列表整体 30 秒期限，下载错误隐藏完整 URL。
- 高通设备 ID 操作隔离到 30 秒 worker；超时视为结果未知，不自动重试。

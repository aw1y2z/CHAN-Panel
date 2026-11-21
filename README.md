# CHAN-Panel

为长安汽车（引力域 / 小安控车）开发的第三方控车客户端。
协议逆向自长安 inCall 手表端 APK 与「小安控车」小程序，完整接口表见 `docs/api-notes.md`。

## 功能

| 页面 | 内容 |
|---|---|
| 仪表盘 | 顶部两格「续航 + 油量」（决策信息，低值标橙）、2×8 车况网格（车锁、车门、车窗、天窗、后备箱、引擎盖、空调含风量档位、前/后胎压四轮、电瓶电压、PM2.5、车内/车外温度、总里程、油耗、钥匙电量、水温）、车辆位置（地址 + 经纬度 + 一键看地图）、下拉刷新 |
| 控车（默认页） | 页顶状态胶囊（车锁 / 发动机 / 车窗，正常绿、异常橙）、16 条指令：锁车/解锁、远程启动/熄火、闪灯/鸣笛/寻车、空调开关（可调温、显示风量档位）、车窗开/关/微开、天窗开/关、后备箱开/关；下发期间按钮置灰防连点。功能块**长按可拖拽排序**（空调卡片同样参与），**宽度可在 1/4 · 1/3 · 1/2 之间切换**，顺序与宽度存本机 |
| 我的 | 头像（相册图 / 手动粘贴的图片地址 / 手机号后 4 位文字兜底，圆形裁剪、本机缓存）、账号（打码手机号）、多车切换、车辆改名（仅本机）、深色模式（跟随系统/浅色/深色）、关于、退出登录；和另两页同层显隐，切换无 Activity 跳转 |
| 信息中心 | 官方消息中心（`/openc-apigw/fc/api/v2/open/information/`）：点火熄火、智能守护消息，按分类分组、未读红点、点击标记已读、全部已读、分页加载；官方接口不可用时自动退回本机车况检测（未锁车、车窗未关、胎压异常等） |
| 通知推送 | 安全守护（未锁车、车窗/天窗/后备箱/引擎盖未关、胎压异常、电瓶亏电、钥匙电量低）与点熄火通过系统通知推送，分 `safety` / `ignition` 两个渠道可单独关闭；后台 `SafetyWorker` 每 15 分钟拉一次车况做检测，只有「正常→异常」跃迁才提醒。MIUI 需在省电策略里设为「无限制」并允许自启动，否则后台任务会被延迟 |

登录两种通道：手机号验证码（小程序通道，功能子集）、扫码授权（VCS 通道，全功能）。

### 关于头像

`/app2/api/user/info` 和 open.iov 的 VCS 接口都不返回头像字段，真实头像只存在于引力域自己的账号体系（`api.uni.changan.com.cn/user/myInfo`，请求体用 `paramEncr` 加密）。所以本客户端不假装能自动获取：默认显示手机号后 4 位的文字头像，点开头像可「从相册选择」或「粘贴图片地址」。引力域头像存在 `img.uni.changan.com.cn`，匿名可访问，粘一次后本机会缓存一份，之后离线也能用。

## 构建

项目内置了可用的默认凭据，**clone 后无需任何配置即可构建登录**。若官方调整导致登录失败，可把 `local.properties.example` 复制成 `local.properties`，用其中的 `changan.clientId` / `changan.clientSecret` / `xiaoan.clientId` 覆盖默认值。

**签名**：`local.properties` 里配了 `release.storeFile` / `release.storePassword` / `release.keyAlias` / `release.keyPassword` 就用正式密钥，否则回退 debug 签名。仓库不含 keystore。注意 debug 签名与正式签名不能互相覆盖安装，换签名前需要卸载。

本仓库不带 `gradlew`（工具链由本机提供），Windows 下构建：

```
java -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :app:assembleDebug
```

## 依赖

| 依赖 | 版本 |
|---|---|
| Gradle | 9.4.1 |
| AGP | 9.2.1（Kotlin 内置） |
| compileSdk / targetSdk / minSdk | 37 / 34 / 26 |
| OkHttp | 4.12.0 |
| kotlinx-coroutines | 1.8.1 |
| Material Components | 1.12.0 |

## 声明

与长安汽车官方无关，协议与实现仅供学习研究。

内置的客户端标识逆向自官方客户端，仅用于个人学习与自用。若官方调整或封禁，可在 `local.properties` 覆盖；本项目不对可用性作任何保证。

## 许可证

[GPL-3.0](LICENSE)。本项目不含官方客户端的任何代码或资源，相关商标与协议归各自所有者。

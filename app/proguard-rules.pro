# ============================================================
# R8 规则
#
# 本项目结构简单，规则少是有原因的：
#   - 没有 Gson / Moshi / Room / DataBinding，JSON 全部手写 JSONObject 取值
#   - 没有 JNI，也没有通过反射调用的业务类
#   - 界面是纯 View，无 Compose
# 真正依赖「字符串 → 类/常量」的只有两处：WorkManager 的 Worker 和枚举常量名。
# 其余交给默认规则 + 各库自带的 consumer rules 即可。
# ============================================================

# ---------- 元信息 ----------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 日志 ----------
# release 里把 debug/info 调用整条删掉：
# 既省体积，也避免把车牌、VIN、接口参数这类运行时信息留在日志里。
# warn / error 保留，release 出问题时还得靠它。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---------- WorkManager ----------
# Worker 由 WorkManager 按类名反射实例化。改名后后台巡检会静默失效：
# 不崩溃也不报错，只是永远不执行。
-keep class io.github.aw1y2z.changan.data.SafetyWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepnames class io.github.aw1y2z.changan.data.SafetyWorker

# WorkManager 的数据库由 Room 生成，运行时按无参构造反射创建。
# 缺这条会在 Application 初始化时直接失败，整个 App 打不开。
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# ---------- 枚举 ----------
# ControlAction 的常量名会写进 SharedPreferences（control_tiles 里存 "LOCK:3"），
# 名字一变，老用户排好的顺序和宽度就读不出来了。
-keep class io.github.aw1y2z.changan.data.ControlAction { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 说明 ----------
# 客户端凭据（BuildConfig.CHANGAN_CLIENT_ID 等）会被内联进代码，
# release 包反编译同样可见。客户端凭据无法真正藏住，这里不做无意义的保护，
# 只保证仓库源码里不出现真实值。

-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class com.oppower.shizuku.PrivilegedCommandService { *; }
-keep class com.oppower.shizuku.IPrivilegedCommandService$* { *; }

# COUI：界面库以 Compose 组件形式调用，正常不需要额外 keep。
# 但它内部有 org.json 解析（诊断坐标）与资源反射查找，保守保留入口类名。
-keep class com.itos.coui.diagnostic.** { *; }
-dontwarn com.itos.coui.**

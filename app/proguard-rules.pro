# BMS Japan の R8 設定
# リリース版では Log.d / Log.v を取り除く（通信内容をログに残さない）
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

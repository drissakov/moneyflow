# DTOs are serialized reflectively by Gson. Keep their fields and generic metadata.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.moneyflow.app.data.remote.** { *; }
-keep class com.moneyflow.app.data.Session { *; }
-keep class com.moneyflow.app.data.PendingTransaction { *; }
-keep class com.moneyflow.app.data.domain.User { *; }

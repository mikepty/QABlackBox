-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

-keep class com.qa.blackbox.domain.services.** { *; }

-dontwarn org.jetbrains.annotations.**

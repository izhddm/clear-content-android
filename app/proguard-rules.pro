# Core parsing code is plain Kotlin without reflection; keep enum names used in DataStore.
-keepclassmembers enum com.clearcontent.** { *; }
-dontwarn org.jetbrains.annotations.**

# AGP 9 runs R8 in strict full mode: "-keep class X" no longer keeps the default constructor,
# but Room instantiates its generated database classes reflectively
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Same for WorkManager input mergers (OverwritingInputMerger, ...): they are created with
# Class.getDeclaredConstructor().newInstance(). Without the constructor every one-time work
# fails with "Could not create Input Merger" (periodic work skips the merger and is not affected)
-keep class * extends androidx.work.InputMerger { <init>(); }

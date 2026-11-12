# AGP 9 runs R8 in strict full mode: "-keep class X" no longer keeps the default constructor,
# but Room instantiates its generated database classes reflectively
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Keep the file and line of a frame so a stack trace from a minified build can still be
# read. R8 drops both by default, which turns every report into a list of one-letter class
# names with no line numbers — and the crashes worth reading are exactly the ones that only
# show up in a release build. The rename below replaces the original file name with the
# obfuscated one, so nothing is given away that the class name does not already say.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SMB. Minification is off today; these rules are here so switching it on does not silently
# break the network sources. They are deliberately narrower than a blanket keep on the whole
# library, which would have held on to every class smbj ships and given up most of what
# shrinking is for.
#
# Not verified against a minified build yet. When `isMinifyEnabled` is turned on, the thing
# to exercise is a real connection: negotiation, NTLM authentication, a directory listing
# and a read. A rule that turns out to be missing shows up there as a ClassNotFoundException
# or a NoSuchMethodError, not as a compile error.

# smbj resolves dialects, share access flags and NT status codes through enums, and R8's
# member shrinking removes the generated values() and valueOf() from an enum nothing calls
# them on by name.
-keepclassmembers enum com.hierynomus.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The wire format. smbj builds request packets and reads responses back through these, and
# its own transport layer instantiates them rather than the call site doing it.
-keep class com.hierynomus.mssmb2.messages.** { *; }
-keep class com.hierynomus.msdtyp.** { *; }
-keep class com.hierynomus.msfscc.fileinformation.** { *; }
-keep class com.hierynomus.ntlm.messages.** { *; }

# bouncycastle's JCE provider registers every algorithm under a class name it looks up as a
# string, so nothing references these classes in a way R8 can see. Without them SMB3
# signing and encryption fail at runtime on a provider that reports itself as present.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# Optional dependencies neither library ships for Android.
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**

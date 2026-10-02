# App-specific R8/ProGuard rules (release builds only).
#
# Keep this file small and evidence-based: every rule here must point at a
# concrete failure (a stack trace, an R8 "missing class" warning, a documented
# reflection/JNI entry point). "Just in case" keep rules defeat R8's job.
#
# What the release build already relies on, without rules here:
#   * AGP's bundled proguard-android-optimize.txt keeps manifest-declared
#     components (Activity/Service/BroadcastReceiver/ContentProvider/View),
#     View inflation constructors, Parcelable CREATOR fields, enum valueOf().
#   * Library consumer rules shipped inside the AARs (Compose, Coil, AndroidX,
#     kotlinx-serialization-core) are applied automatically.
#   * Resource names are NOT shrunk/obfuscated: resource shrinking is off, so
#     runtime lookups by name keep working (see below).
#
# Known runtime resource lookup (why resource shrinking must stay off, or a
# tools:keep entry is required if it is ever enabled):
#   CodeWidgetProvider builds widget row ids with
#   resources.getIdentifier("widget_tall_row_$i", "id", packageName)
#   -> keeps working only while resource shrinking/resource obfuscation is off.

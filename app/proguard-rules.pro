# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep the AccessibilityService
-keep class com.focusguard.app.FocusGuardService { *; }

# Keep the DeviceAdminReceiver
-keep class com.focusguard.app.FocusGuardDeviceAdmin { *; }

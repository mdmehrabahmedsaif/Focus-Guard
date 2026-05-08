package com.focusguard.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }

    /**
     * BUG FIX #7: Override onDisableRequested to show a warning message.
     *
     * WHY: Without this override, when a user tries to deactivate Device Admin,
     * Android shows a blank confirmation dialog. By overriding this, we return
     * a custom warning message that makes the user reconsider.
     *
     * NOTE: This does NOT prevent deactivation (that requires Device Owner which
     * needs adb/OEM tools). But combined with BlockerService's instant HOME action,
     * the user is kicked back before they can tap "Deactivate".
     */
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "⚠️ WARNING: Disabling Device Admin will remove FocusGuard protection. " +
               "Your focus session will be compromised.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
    }
}

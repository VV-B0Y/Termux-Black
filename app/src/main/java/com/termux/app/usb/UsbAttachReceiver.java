package com.termux.app.usb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.termux.app.TermuxService;

public final class UsbAttachReceiver extends BroadcastReceiver {
    private static final String TAG = "UsbAttachReceiver";
    public static final String ACTION_ATTACH_USB = "com.termux.app.ATTACH_USB";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "onReceive action: " + action);

        Intent serviceIntent = new Intent(context, TermuxService.class);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) || ACTION_ATTACH_USB.equals(action)) {
            serviceIntent.setAction(TermuxService.ACTION_ATTACH_USB);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                serviceIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to start TermuxService: " + e.getMessage());
            }
        }
    }
}

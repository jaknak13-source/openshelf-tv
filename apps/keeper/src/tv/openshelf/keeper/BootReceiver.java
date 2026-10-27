package tv.openshelf.keeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Exported only so the system can deliver BOOT_COMPLETED (a protected broadcast other apps cannot send).
 * Any other intent aimed at this receiver is ignored; no extras are read.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        KeeperService.start(context, KeeperService.ACTION_BOOT);
    }
}

package tv.openshelf.keeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;

import tv.openshelf.keeper.core.AdbKey;
import tv.openshelf.keeper.core.Job;
import tv.openshelf.keeper.core.PackageList;
import tv.openshelf.keeper.core.Result;
import tv.openshelf.keeper.core.RunLog;
import tv.openshelf.keeper.core.Runner;

/** Foreground service that runs one apply pass on a worker thread, then stops itself. Not exported. */
public final class KeeperService extends Service {
    static final String ACTION_BOOT = "tv.openshelf.keeper.action.BOOT";
    static final String ACTION_MANUAL = "tv.openshelf.keeper.action.MANUAL";

    static final String ADB_HOST = "127.0.0.1";
    static final int ADB_PORT = 5555;
    private static final String KEY_NAME = "openshelf-keeper@tv";
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int AUTH_WAIT_MS = 60_000;
    private static final long BOOT_BUDGET_MS = 180_000;
    private static final long MANUAL_BUDGET_MS = 20_000;

    private static final String TAG = "OpenShelfKeeper";
    private static final String CHANNEL_ID = "keeper";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;

    static void start(Context context, String action) {
        context.startForegroundService(new Intent(context, KeeperService.class).setAction(action));
    }

    static boolean isRunning() {
        return running;
    }

    static File logFile(Context context) {
        return new File(context.getFilesDir(), "keeper.log");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be answered with startForeground(), even while a run is active.
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .build());
        if (worker != null) {
            return START_NOT_STICKY; // the active run covers this request
        }
        boolean boot = intent != null && ACTION_BOOT.equals(intent.getAction());
        worker = new Thread(() -> work(boot), "keeper-run");
        running = true;
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void work(boolean boot) {
        Status status = new Status(this);
        Job.run(log -> AdbKey.loadOrCreate(new File(getFilesDir(), "adbkey"), new File(getFilesDir(), "adbkey.pub.der"), log),
                new Runner(ADB_HOST, ADB_PORT, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, AUTH_WAIT_MS),
                KEY_NAME, PackageList.all(), getPackageName(), boot ? BOOT_BUDGET_MS : MANUAL_BUDGET_MS,
                boot ? "boot" : "manual", new RunLog(logFile(this)), new Job.Sink() {
                    @Override
                    public void state(String state) {
                        status.setState(state);
                    }

                    @Override
                    public void finished(Result result, long time) {
                        if (result.outcome != Result.Outcome.DONE) {
                            Log.w(TAG, "run ended: " + result.summary());
                        }
                        running = false;
                        status.saveResult(result, time);
                    }
                });
        Thread self = Thread.currentThread();
        main.post(() -> finished(self));
    }

    private void finished(Thread done) {
        if (worker != done) {
            return;
        }
        worker = null;
        stopForeground(true);
        stopSelf();
    }
}

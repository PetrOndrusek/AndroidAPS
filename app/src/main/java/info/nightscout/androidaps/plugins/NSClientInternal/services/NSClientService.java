package info.nightscout.androidaps.plugins.NSClientInternal.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;


import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.j256.ormlite.dao.CloseableIterator;
import com.squareup.otto.Subscribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Date;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.events.EventAppExit;
import info.nightscout.androidaps.events.EventConfigBuilderChange;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAddAck;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAuthAck;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSUpdateAck;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastAlarm;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastAnnouncement;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastCals;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastClearAlarm;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastDeviceStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastFood;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastMbgs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastProfile;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastSgvs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastUrgentAlarm;
import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSettingsStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSgv;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientRestart;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientStatus;
import info.nightscout.androidaps.plugins.Overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.Overview.notifications.Notification;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.FabricPrivacy;
import info.nightscout.utils.JsonHelper;
import info.nightscout.utils.SP;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class NSClientService extends Service {
    private static Logger log = LoggerFactory.getLogger(NSClientService.class);

    static public PowerManager.WakeLock mWakeLock;
    private IBinder mBinder = new NSClientService.LocalBinder();

    static ProfileStore profileStore;

    static public Handler handler;

    public static Socket mSocket;
    public static boolean isConnected = false;
    public static boolean hasWriteAuth = false;
    private static Integer dataCounter = 0;
    private static Integer connectCounter = 0;


    public static String nightscoutVersionName = "";
    public static Integer nightscoutVersionCode = 0;

    public NSConfiguration nsConfig = new NSConfiguration();

    public long lastResendTime = 0;

    public long latestDateInReceivedData = 0;

    private String nsAPIhashCode = "";

    public static UploadQueue uploadQueue = new UploadQueue();

    private TransportServiceInterface transportService = null;

    public NSClientService() {
        registerBus();
        if (handler == null) {
            HandlerThread handlerThread = new HandlerThread(NSClientService.class.getSimpleName() + "Handler");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }

        PowerManager powerManager = (PowerManager) MainApp.instance().getApplicationContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NSClientService");
        initialize();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWakeLock.acquire();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mWakeLock.isHeld()) mWakeLock.release();
    }

    public class LocalBinder extends Binder {
        public NSClientService getServiceInstance() {
            return NSClientService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        return START_STICKY;
    }

    private void registerBus() {
        try {
            MainApp.bus().unregister(this);
        } catch (RuntimeException x) {
            // Ignore
        }
        MainApp.bus().register(this);
    }

    @Subscribe
    public void onStatusEvent(EventAppExit event) {
        if (Config.logFunctionCalls)
            log.debug("EventAppExit received");

        destroy();

        stopSelf();
        if (Config.logFunctionCalls)
            log.debug("EventAppExit finished");
    }

    @Subscribe
    public void onStatusEvent(EventPreferenceChange ev) {
        if (ev.isChanged(R.string.key_nsclientinternal_url) ||
                ev.isChanged(R.string.key_nsclientinternal_api_secret) ||
                ev.isChanged(R.string.key_nsclientinternal_paused)
                ) {
            latestDateInReceivedData = 0;
            destroy();
            initialize();
        }
    }

    @Subscribe
    public void onStatusEvent(EventConfigBuilderChange ev) {
        if (nsConfig.enabled != MainApp.getSpecificPlugin(NSClientPlugin.class).isEnabled(PluginBase.GENERAL)) {
            destroy();
            initialize();
        }
    }

    @Subscribe
    public void onStatusEvent(final EventNSClientRestart ev) {
        restart();
    }

    public void initialize() {
        nsConfig = readPreferences();

        getTransportService().initialize();
    }

    private TransportServiceInterface getTransportService() {
        if (transportService == null)
        {
            if (nsConfig.restEnabled) {
                transportService = new WebsocketTransportService(nsConfig, this, handler, uploadQueue);  // REST variant in the future
            }
            else {
                transportService = new WebsocketTransportService(nsConfig, this, handler, uploadQueue);
            }
        }
        return transportService;
    }

    public void destroy() {
        getTransportService().destroy();
        transportService = null;
    }

    public NSConfiguration readPreferences() {
        NSConfiguration conf = new NSConfiguration();
        conf.enabled = MainApp.getSpecificPlugin(NSClientPlugin.class).isEnabled(PluginBase.GENERAL);
        conf.url = SP.getString(R.string.key_nsclientinternal_url, "");
        conf.apiSecret = SP.getString(R.string.key_nsclientinternal_api_secret, "");
        conf.device = SP.getString("careportal_enteredby", "");
        return conf;
    }

    private boolean isCurrent(NSTreatment treatment) {
        long now = (new Date()).getTime();
        long minPast = now - nsConfig.hours * 60L * 60 * 1000;
        if (treatment.getMills() == null) {
            log.debug("treatment.getMills() == null " + treatment.getData().toString());
            return false;
        }
        if (treatment.getMills() > minPast) return true;
        return false;
    }

    public void resend(final String reason) {
        getTransportService().resend(reason);
    }

    public void sendAlarmAck(AlarmAck alarmAck) {
        getTransportService().sendAlarmAck(alarmAck);
    }

    public void restart() {
        destroy();
        initialize();
    }
}

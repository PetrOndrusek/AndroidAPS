package info.nightscout.androidaps.plugins.NSClientInternal.services;

import android.os.Handler;

import org.json.JSONException;
import org.json.JSONObject;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSettingsStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public abstract class AbstractTransportService {

    public abstract void initialize();
    public abstract void destroy();
    public abstract void resend(final String reason, boolean startNow);
    public abstract void sendAlarmAck(AlarmAck alarmAck);

    protected NSClientService mNSClientService = null;
    protected Handler mHandler = null;
    protected UploadQueue mUploadQueue = null;
    protected NSConfiguration nsConfig = null;

    protected boolean isConnected = false;
    protected boolean hasWriteAuth = false;
    protected String nightscoutVersionName = "";
    protected Integer nightscoutVersionCode = 0;

    public boolean isConnected() {
        return isConnected;
    }

    public boolean hasWriteAuth() {
        return hasWriteAuth;
    }

    public String getNightscoutVersionName() {
        return nightscoutVersionName;
    }

    public int getNightscoutVersionCode() {
        return nightscoutVersionCode;
    }

    protected void registerBus() {
        try {
            MainApp.bus().unregister(this);
        } catch (RuntimeException x) {
            // Ignore
        }
        MainApp.bus().register(this);
    }

    protected void handleStatus(JSONObject status, boolean isDelta) throws JSONException {
        NSSettingsStatus nsSettingsStatus = NSSettingsStatus.getInstance().setData(status);

        if (status.has("versionNum")) {
            if (status.getInt("versionNum") < Config.SUPPORTEDNSVERSION) {
                EventNSClientNewLog.emit("ERROR", "Unsupported Nightscout version !!!!");
            }

            nightscoutVersionName = nsSettingsStatus.getVersion();
            nightscoutVersionCode = nsSettingsStatus.getVersionNum();
        }

        BroadcastStatus.handleNewStatus(nsSettingsStatus, MainApp.instance().getApplicationContext(), isDelta);

         /*  Other received data to 2016/02/10
                        {
                          status: 'ok'
                          , name: env.name
                          , version: env.version
                          , versionNum: versionNum (for ver 1.2.3 contains 10203)
                          , serverTime: new Date().toISOString()
                          , apiEnabled: apiEnabled
                          , careportalEnabled: apiEnabled && env.settings.enable.indexOf('careportal') > -1
                          , boluscalcEnabled: apiEnabled && env.settings.enable.indexOf('boluscalc') > -1
                          , head: env.head
                          , settings: env.settings
                          , extendedSettings: ctx.plugins && ctx.plugins.extendedClientSettings ? ctx.plugins.extendedClientSettings(env.extendedSettings) : {}
                          , activeProfile ..... calculated from treatments or missing
                        }
                     */
    }
}

package info.nightscout.androidaps.plugins.NSClientInternal.services;

import android.os.Handler;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public abstract class AbstractTransportService {

    public abstract void initialize();
    public abstract void destroy();
    public abstract void resend(final String reason);
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
}

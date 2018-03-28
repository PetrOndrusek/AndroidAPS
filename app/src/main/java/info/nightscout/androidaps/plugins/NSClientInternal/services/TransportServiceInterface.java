package info.nightscout.androidaps.plugins.NSClientInternal.services;

import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public interface TransportServiceInterface {

    boolean isConnected();
    boolean hasWriteAuth();
    String getNightscoutVersionName();
    int getNightscoutVersionCode();

    void initialize();
    void destroy();
    void resend(final String reason);
    void sendAlarmAck(AlarmAck alarmAck);
}

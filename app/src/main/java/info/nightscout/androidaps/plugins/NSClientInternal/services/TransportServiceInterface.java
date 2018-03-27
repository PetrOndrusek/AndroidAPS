package info.nightscout.androidaps.plugins.NSClientInternal.services;

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
}

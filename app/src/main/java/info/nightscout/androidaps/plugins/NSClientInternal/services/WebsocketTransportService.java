package info.nightscout.androidaps.plugins.NSClientInternal.services;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public class WebsocketTransportService implements TransportServiceInterface {

    private boolean isConnected = false;

    @Override
    public boolean isConnected() {
        return isConnected;
    }


    private boolean hasWriteAuth = false;

    @Override
    public boolean hasWriteAuth() {
        return hasWriteAuth;
    }


    private String nightscoutVersionName = "";

    @Override
    public String getNightscoutVersionName() {
        return nightscoutVersionName;
    }


    private Integer nightscoutVersionCode = 0;

    @Override
    public int getNightscoutVersionCode() {
        return nightscoutVersionCode;
    }


    @Override
    public void initialize() {

    }


    @Override
    public void destroy() {

    }


    @Override
    public void restart() {

    }


    @Override
    public void resend(String reason) {

    }
}

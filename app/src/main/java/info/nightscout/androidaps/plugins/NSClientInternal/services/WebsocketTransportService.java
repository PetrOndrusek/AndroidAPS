package info.nightscout.androidaps.plugins.NSClientInternal.services;

import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public class WebsocketTransportService implements TransportServiceInterface {

    private boolean isConnected = false;
    private boolean hasWriteAuth = false;
    private String nightscoutVersionName = "";
    private Integer nightscoutVersionCode = 0;
    private NSConfiguration nsConfig = null;

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    @Override
    public boolean hasWriteAuth() {
        return hasWriteAuth;
    }

    @Override
    public String getNightscoutVersionName() {
        return nightscoutVersionName;
    }

    @Override
    public int getNightscoutVersionCode() {
        return nightscoutVersionCode;
    }


    public WebsocketTransportService(NSConfiguration nsConfig)
    {
        this.nsConfig = nsConfig;
    }


    @Override
    public void initialize() {

    }


    @Override
    public void destroy() {

    }


    @Override
    public void resend(String reason) {

    }
}

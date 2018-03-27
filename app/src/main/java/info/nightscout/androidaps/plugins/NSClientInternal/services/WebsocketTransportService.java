package info.nightscout.androidaps.plugins.NSClientInternal.services;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import java.net.URISyntaxException;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientStatus;
import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public class WebsocketTransportService implements TransportServiceInterface {

    private boolean isConnected = false;
    private boolean hasWriteAuth = false;
    private String nightscoutVersionName = "";
    private Integer nightscoutVersionCode = 0;
    private NSConfiguration nsConfig = null;
    public Socket mSocket;
    private String nsAPIhashCode = "";

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
        registerBus();
        this.nsConfig = nsConfig;
    }


    @Override
    public void initialize() {

        if (!nsConfig.apiSecret.equals(""))
            nsAPIhashCode = Hashing.sha1().hashString(nsConfig.apiSecret, Charsets.UTF_8).toString();

        MainApp.bus().post(new EventNSClientStatus("Initializing"));
        if (MainApp.getSpecificPlugin(NSClientPlugin.class).paused) {
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "paused"));
            MainApp.bus().post(new EventNSClientStatus("Paused"));
        } else if (!nsConfig.enabled) {
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "disabled"));
            MainApp.bus().post(new EventNSClientStatus("Disabled"));
        } else if (!nsConfig.url.equals("")) {
            try {
                MainApp.bus().post(new EventNSClientStatus("Connecting ..."));
                IO.Options opt = new IO.Options();
                opt.forceNew = true;
                opt.reconnection = true;
                mSocket = IO.socket(nsConfig.url, opt);
                //mSocket.on(Socket.EVENT_CONNECT, onConnect);
                //mSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
                //mSocket.on(Socket.EVENT_PING, onPing);
                MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "do connect"));
                mSocket.connect();
                //mSocket.on("dataUpdate", onDataUpdate);
                //mSocket.on("announcement", onAnnouncement);
                //mSocket.on("alarm", onAlarm);
                //mSocket.on("urgent_alarm", onUrgentAlarm);
                //mSocket.on("clear_alarm", onClearAlarm);
            } catch (URISyntaxException | RuntimeException e) {
                MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "Wrong URL syntax"));
                MainApp.bus().post(new EventNSClientStatus("Wrong URL syntax"));
            }
        } else {
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "No NS URL specified"));
            MainApp.bus().post(new EventNSClientStatus("Not configured"));
        }
    }


    @Override
    public void destroy() {

        if (mSocket != null) {
            mSocket.off(Socket.EVENT_CONNECT);
            mSocket.off(Socket.EVENT_DISCONNECT);
            mSocket.off(Socket.EVENT_PING);
            mSocket.off("dataUpdate");
            mSocket.off("announcement");
            mSocket.off("alarm");
            mSocket.off("urgent_alarm");
            mSocket.off("clear_alarm");

            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "destroy"));
            isConnected = false;
            hasWriteAuth = false;
            mSocket.disconnect();
            mSocket = null;
        }
    }


    @Override
    public void resend(String reason) {

    }


    private void registerBus() {
        try {
            MainApp.bus().unregister(this);
        } catch (RuntimeException x) {
            // Ignore
        }
        MainApp.bus().register(this);
    }
}

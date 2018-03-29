package info.nightscout.androidaps.plugins.NSClientInternal.services.REST;

import android.os.Handler;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.services.NSClientService;
import info.nightscout.androidaps.plugins.NSClientInternal.services.AbstractTransportService;
import info.nightscout.androidaps.plugins.NSClientInternal.services.WebsocketTransportService;
import info.nightscout.utils.Str;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * Created by PetrOndrusek on 29.03.2018.
 */

public class RestTransportService extends AbstractTransportService {
    private static Logger log = LoggerFactory.getLogger(WebsocketTransportService.class);

    private boolean isInitialized = false;
    private NightscoutService mNSService = null;
    private String hashedApiSecret = null;

    public RestTransportService(NSConfiguration nsConfig, NSClientService nsClientService, Handler handler, UploadQueue uploadQueue) {
        registerBus();
        this.nsConfig = nsConfig;
        this.mNSClientService = nsClientService;
        this.mHandler = handler;
        this.mUploadQueue = uploadQueue;
    }


    @Override
    public void initialize() {

        isInitialized = false;

        MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "initialize"));
        MainApp.bus().post(new EventNSClientStatus("Initializing"));

        if (Str.isNullOrEmpty(nsConfig.url))
        {
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "No NS URL specified"));
            MainApp.bus().post(new EventNSClientStatus("Not configured"));
            return;
        }

        String apiUrl = nsConfig.url;
        if (!apiUrl.endsWith("/")) {
            apiUrl += "/";
        }
        apiUrl += "api/v1/";

        if (!Str.isNullOrEmpty(nsConfig.apiSecret))
        {
            hashedApiSecret = Hashing.sha1().hashBytes(nsConfig.apiSecret.getBytes(Charsets.UTF_8)).toString();
        }
        else
        {
            hashedApiSecret = "";
        }

        try {
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(apiUrl)
                    .build();
            mNSService = retrofit.create(NightscoutService.class);
        }
        catch (Exception ex)
        {
            log.error(ex.getMessage());
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "ERROR " + ex.getMessage()));
            MainApp.bus().post(new EventNSClientStatus("Not configured"));
            return;
        }

        MainApp.bus().post(new EventNSClientStatus("Initialized"));
        isInitialized = true;
    }

    @Override
    public void destroy() {
        isInitialized = false;
        isConnected = false;
        hasWriteAuth = false;
        mNSService = null;
        MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "destroy"));
        MainApp.bus().post(new EventNSClientStatus("Stopped"));
    }

    @Override
    public void resend(final String reason) {

        Call<ResponseBody> call = mNSService.getStatus(hashedApiSecret);

        new Thread(new Runnable() {
            @Override
            public void run() {
                Response<ResponseBody> response = null;
                try {
                    response = call.execute();
                    ResponseBody body = response.body();
                    String s = body.string();
                    JSONObject json = new JSONObject(s);
                    String status = json.get("status").toString();
                    MainApp.bus().post(new EventNSClientNewLog("STATUS", status));
                    log.debug(s);
                } catch (IOException ex) {
                    log.error(ex.getMessage());
                } catch (JSONException ex) {
                    log.error(ex.getMessage());
                }

            }
        }).start();
    }

    @Override
    public void sendAlarmAck(AlarmAck alarmAck) {

    }
}

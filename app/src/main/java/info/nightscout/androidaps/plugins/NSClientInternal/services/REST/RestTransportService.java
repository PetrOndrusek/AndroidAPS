package info.nightscout.androidaps.plugins.NSClientInternal.services.REST;

import android.os.Handler;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
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
    private Thread workerThread = null;
    private StoppableScheduler batchScheduler = null;

    public RestTransportService(NSConfiguration nsConfig, NSClientService nsClientService, Handler handler, UploadQueue uploadQueue) {
        registerBus();
        this.nsConfig = nsConfig;
        this.mNSClientService = nsClientService;
        this.mHandler = handler;
        this.mUploadQueue = uploadQueue;
    }


    @Override
    public void initialize() {

        try {
            isInitialized = false;
            workerThread = null;

            EventNSClientNewLog.emit("NSCLIENT", "initialize REST");
            EventNSClientStatus.emit("REST Initializing");

            if (Str.isNullOrEmpty(nsConfig.url)) {
                EventNSClientNewLog.emit("NSCLIENT", "No NS URL specified");
                EventNSClientStatus.emit("REST Not configured");
                return;
            }

            if (MainApp.getSpecificPlugin(NSClientPlugin.class).paused) {
                EventNSClientNewLog.emit("NSCLIENT", "paused");
                EventNSClientStatus.emit("Paused");
                return;
            }
            if (!nsConfig.enabled) {
                EventNSClientNewLog.emit("NSCLIENT", "disabled");
                EventNSClientStatus.emit("Disabled");
                return;
            }

            String apiUrl = nsConfig.url;
            if (!apiUrl.endsWith("/")) {
                apiUrl += "/";
            }
            apiUrl += "api/v1/";

            if (!Str.isNullOrEmpty(nsConfig.apiSecret)) {
                hashedApiSecret = Hashing.sha1().hashBytes(nsConfig.apiSecret.getBytes(Charsets.UTF_8)).toString();
            } else {
                hashedApiSecret = "";
            }

            try {
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(apiUrl)
                        .build();
                mNSService = retrofit.create(NightscoutService.class);
            } catch (Exception ex) {
                log.error(ex.getMessage());
                EventNSClientNewLog.emit("NSCLIENT", "ERROR " + ex.getMessage());
                EventNSClientStatus.emit("REST Not configured");
                return;
            }

            EventNSClientStatus.emit("REST Initialized");
            isInitialized = true;

            // let's prepare cyclic batch scheduler that can be interrupted
            batchScheduler = new StoppableScheduler(new Runnable() {
                @Override
                public void run() {
                    batchCycle();
                }
            }, nsConfig.restSecondsSleep * 1000, 100); // short initial sleep is for log events not to get into race condition
            batchScheduler.start();

            workerThread = new Thread(batchScheduler);
            workerThread.start();
        } catch (Exception ex) {
            logError(ex.getMessage());
            isInitialized = false;
        }
    }

    @Override
    public void destroy() {
        try {
            if (batchScheduler != null) {
                batchScheduler.stop();
            }
            isInitialized = false;
            isConnected = false;
            hasWriteAuth = false;
            mNSService = null;
            workerThread = null;
            EventNSClientNewLog.emit("NSCLIENT", "destroy REST");
            EventNSClientStatus.emit("REST Stopped");
        } catch (Exception ex) {
            logError(ex.getMessage());
        }
    }

    @Override
    public void resend(final String reason, boolean startNow) {
        if (startNow && batchScheduler != null) {
            batchScheduler.activateAfter(0);  // just speed up the cycle activation
        }
    }

    @Override
    public void sendAlarmAck(AlarmAck alarmAck) {
        // alarms are not yet implemented in REST
    }

    private void batchCycle() {
        try {
            if (!canContinue(false))
                return;

            EventNSClientNewLog.emit("NSCLIENT", "Starting batch");

            if (getStatus()) {

            }

            EventNSClientNewLog.emit("NSCLIENT", "Batch completed");
        } catch (Exception ex) {
            logError(ex.getMessage());
        }
    }

    private boolean canContinue(boolean checkIsConnected) {
        return isInitialized
                && batchScheduler != null
                && batchScheduler.isRunning()
                && !MainApp.getSpecificPlugin(NSClientPlugin.class).paused
                && (!checkIsConnected || isConnected);
    }

    private boolean getStatus() {

        isConnected = false;
        if (!canContinue(false))
            return false;

        Call<ResponseBody> call = mNSService.getStatus();

        Response<ResponseBody> response = null;
        try {
            response = call.execute();
            if (response == null || !response.isSuccessful()) {
                logError("Failed to retrieve status from NS");
                return false;
            }
            JSONObject status = new JSONObject(response.body().string());

            if (status.has("version") && !status.has("versionNum"))
            {
                Pattern pattern = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");
                Matcher matcher = pattern.matcher(status.getString("version"));
                if (matcher.find()) {
                    int versionNum =
                        (Integer.parseInt(matcher.group(1)) * 10000)
                            + (Integer.parseInt(matcher.group(2)) * 100)
                            + (Integer.parseInt(matcher.group(3)));
                    status.put("versionNum", versionNum);
                }
            }

            handleStatus(status, false);
            EventNSClientNewLog.emit("STATUS", status.getString("status"));
        } catch (IOException ex) {
            logError(ex.getMessage());
            return false;
        } catch (JSONException ex) {
            logError(ex.getMessage());
            return false;
        }
        isConnected = true;
        return true;
    }

    private void logError(String message) {
        EventNSClientNewLog.emit("ERROR", message);
        log.error(message);
    }
}

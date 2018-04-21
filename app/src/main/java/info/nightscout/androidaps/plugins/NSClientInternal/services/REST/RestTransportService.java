package info.nightscout.androidaps.plugins.NSClientInternal.services.REST;

import android.os.Handler;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.j256.ormlite.dao.CloseableIterator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.db.Treatment;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAddAck;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSUpdateAck;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastDeviceStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastFood;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.services.NSClientService;
import info.nightscout.androidaps.plugins.NSClientInternal.services.AbstractTransportService;
import info.nightscout.androidaps.plugins.NSClientInternal.services.WebsocketTransportService;
import info.nightscout.utils.NSUpload;
import info.nightscout.utils.Str;
import okhttp3.MediaType;
import okhttp3.RequestBody;
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
    private NSNetApiService apiService = null;
    private String hashedApiSecret = null;
    private Thread workerThread = null;
    private StoppableScheduler batchScheduler = null;
    private long latestDateInReceivedData = 0;

    private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");


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
            latestDateInReceivedData = System.currentTimeMillis() - (6 * 3600 * 1000);

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
            apiUrl += "api/";

            if (!Str.isNullOrEmpty(nsConfig.apiSecret)) {
                hashedApiSecret = Hashing.sha1().hashBytes(nsConfig.apiSecret.getBytes(Charsets.UTF_8)).toString();
            } else {
                hashedApiSecret = "";
            }

            try {
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(apiUrl)
                        .build();
                apiService = retrofit.create(NSNetApiService.class);
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
            apiService = null;
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

            uploadCycle();

            EventNSClientNewLog.emit("NSCLIENT", "Batch completed");
        } catch (Exception ex) {
            logError(ex.getMessage());
        }
    }

    private void uploadCycle() {

        uploadChanges();
        downloadChanges();
    }

    private void uploadChanges() {
        CloseableIterator<DbRequest> iterator = null;
        int maxcount = 30;
        try {
            iterator = MainApp.getDbHelper().getDbRequestInterator();
            try {
                while (iterator.hasNext() && maxcount > 0) {
                    DbRequest dbr = iterator.next();
                    if (dbr.action.equals("dbAdd")) {
                        dbAdd(dbr);
                    }  else if (dbr.action.equals("dbRemove")) {
                        dbRemove(dbr);
                    } /*else if (dbr.action.equals("dbUpdate")) {
                        NSUpdateAck updateAck = new NSUpdateAck(dbr.action, dbr._id);
                        dbUpdate(dbr, updateAck);
                    } else if (dbr.action.equals("dbUpdateUnset")) {
                        NSUpdateAck updateUnsetAck = new NSUpdateAck(dbr.action, dbr._id);
                        dbUpdateUnset(dbr, updateUnsetAck);
                    }*/
                    maxcount--;
                }
            } finally {
                iterator.close();
            }
        } catch (SQLException e) {
            log.error("Unhandled exception", e);
        }
    }

    private boolean dbAdd(DbRequest dbr) {
        try {
            RequestBody body = RequestBody.create(MediaType.parse("application/json"), dbr.data);
            Call<ResponseBody> call = apiService.post(hashedApiSecret, dbr.collection, body);
            Response<ResponseBody> response = call.execute();
            if (response == null || !response.isSuccessful()) {
                logError("Failed DBADD " + dbr.collection + " " + dbr.nsClientID);
                return false;
            }
            EventNSClientNewLog.emit("DBADD " + dbr.collection, "Sent " + dbr.nsClientID);

            mUploadQueue.removeNsclientID(dbr.nsClientID);

            JSONObject responseJson = new JSONObject(response.body().string());
            unpackMongoId(responseJson);

            if (responseJson.has("_id")) {
                String mongoId = responseJson.getString("_id");

                Treatment treatment = MainApp.getDbHelper().findTreatmentById(dbr._id);
                if (treatment != null) {
                    treatment._id = mongoId;
                    MainApp.getDbHelper().update(treatment);
                }
            }

            return true;
        } catch (IOException ex) {
                logError(ex.getMessage());
            return false;
        } catch (JSONException ex) {
            logError(ex.getMessage());
            return false;
        }
    }

    private boolean dbRemove(DbRequest dbr) {
        try {
            Call<ResponseBody> call = apiService.delete(hashedApiSecret, dbr.collection, dbr._id);
            Response<ResponseBody> response = call.execute();
            if (response == null || !response.isSuccessful()) {
                logError("Failed DBREMOVE " + dbr.collection + " " + dbr._id);
                return false;
            }
            EventNSClientNewLog.emit("DBREMOVE " + dbr.collection, "Sent " + dbr._id);

            mUploadQueue.removeID("dbRemove", dbr._id);

            return true;
        } catch (IOException ex) {
            logError(ex.getMessage());
            return false;
        }
    }

    private boolean downloadChanges() {

        try {
            String collections = null; // = "all"
            Integer maxCount = null; // = 1000
            Boolean includeDeleted = null; // = true
            Call<ResponseBody> call = apiService.delta(collections, maxCount, includeDeleted, latestDateInReceivedData, null);
            Response<ResponseBody> response = call.execute();
            if (response == null || !response.isSuccessful()) {
                logError("Failed DOWNLOAD");
                return false;
            }
            JSONObject data = new JSONObject(response.body().string());

            if (data.has("treatments")) {
                JSONArray treatments = data.getJSONArray("treatments");
                handleTreatments(treatments);
            }

            if (data.has("devicestatus")) {
                JSONArray devicestatuses = data.getJSONArray("devicestatus");
                handleDevicestatuses(devicestatuses);
            }

            return true;
        } catch (Exception ex) {
            logError(ex.getMessage());
            return false;
        }
    }

    private void handleTreatments(JSONArray treatments) {

        if (treatments.length() > 0)
            EventNSClientNewLog.emit("DATA", "received " + treatments.length() + " treatments");

        JSONArray removedTreatments = new JSONArray();
        JSONArray updatedTreatments = new JSONArray();
        JSONArray addedTreatments = new JSONArray();

        for (Integer index = 0; index < treatments.length(); index++) {
            JSONObject jsonTreatment = null;
            try {
                jsonTreatment = treatments.getJSONObject(index);
                unpackMongoId(jsonTreatment);
                Long modified = unpackModified(jsonTreatment);
                NSTreatment treatment = new NSTreatment(jsonTreatment);


                // remove from upload queue if it is stuck
                mUploadQueue.removeID(jsonTreatment);

                if (treatment.getAction() == null) {
                    addedTreatments.put(jsonTreatment);
                } else if (treatment.getAction().equals("update")) {
                    updatedTreatments.put(jsonTreatment);
                } else if (treatment.getAction().equals("remove")) {
                    if (treatment.getModified() > System.currentTimeMillis() - 24 * 60 * 60 * 1000L) // handle 1 day old deletions only
                        removedTreatments.put(jsonTreatment);
                }

                if (treatment.getModified() > latestDateInReceivedData) {
                    latestDateInReceivedData = treatment.getModified();
                }
            } catch (JSONException ex) {
                logError(ex.getMessage());
            }
        }

        if (removedTreatments.length() > 0) {
            BroadcastTreatment.handleRemovedTreatment(removedTreatments, true);
        }
        if (updatedTreatments.length() > 0) {
            BroadcastTreatment.handleChangedTreatment(updatedTreatments, true);
        }
        if (addedTreatments.length() > 0) {
            BroadcastTreatment.handleNewTreatment(addedTreatments, true);
        }
    }

    private void handleDevicestatuses(JSONArray devicestatuses) {
        if (devicestatuses.length() > 0) {
            EventNSClientNewLog.emit("DATA", "received " + devicestatuses.length() + " devicestatuses");

            for (Integer index = 0; index < devicestatuses.length(); index++) {
                try {
                    JSONObject jsonStatus = devicestatuses.getJSONObject(index);
                    unpackMongoId(jsonStatus);
                    Long modified = unpackModified(jsonStatus);

                    // remove from upload queue if Ack is failing
                    mUploadQueue.removeID(jsonStatus);

                    if (modified != null && modified > latestDateInReceivedData) {
                        latestDateInReceivedData = modified;
                    }
                } catch (JSONException e) {
                    logError(e.getMessage());
                }
            }
            BroadcastDeviceStatus.handleNewDeviceStatus(devicestatuses, MainApp.instance().getApplicationContext(), true);
        }
    }

    private void unpackMongoId(JSONObject json)
    {
        try {
            if (json.has("_id")) {
                JSONObject jsonId = json.getJSONObject("_id");
                if (jsonId.has("$oid")) {
                    String oid = jsonId.getString("$oid");
                    json.remove("_id");
                    json.put("_id", oid);
                }
            }
        } catch (JSONException e) {
            log.error(e.getMessage());
        }
    }

    private Long unpackModified(JSONObject json) {
        if (json.has("modified")) {
            try {
                Object modifiedObj = json.get("modified");
                if (modifiedObj instanceof Long || modifiedObj instanceof Integer) {
                    return (long)modifiedObj;
                }
                if (modifiedObj instanceof JSONObject)
                {
                    JSONObject modifiedJson = (JSONObject) modifiedObj;
                    if (modifiedJson.has("$date")) {
                        long modified = modifiedJson.getLong("$date");
                        json.remove("modified");
                        json.put("modified", modified);
                        return modified;
                    }
                }
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }
        return null;
    }

    private boolean canContinue(boolean checkIsConnected) {
        return isInitialized
                && batchScheduler != null
                && batchScheduler.isRunning()
                && !MainApp.getSpecificPlugin(NSClientPlugin.class).paused
                && (!checkIsConnected || isConnected);
    }

    private void logError(String message) {
        EventNSClientNewLog.emit("ERROR", message);
        log.error(message);
    }
}

package info.nightscout.androidaps.plugins.NSClientInternal.services.REST;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;

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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.db.Treatment;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAddAck;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSUpdateAck;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastCals;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastDeviceStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastFood;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastMbgs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastProfile;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastSgvs;
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
    private String apiUrl = null;

    private Map<String, Long> lastDeltaDates = new HashMap<String, Long>();

    private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static final String COL_ENTRIES = "entries";
    private static final String COL_DEVICESTATUS = "devicestatus";
    private static final String COL_TREATMENTS = "treatments";
    private static final String COL_PROFILE = "profile";

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

            lastDeltaDates = new HashMap<String, Long>();
            long startDeltaDate = System.currentTimeMillis() - (6 * 3600 * 1000);
            lastDeltaDates.put(COL_ENTRIES, startDeltaDate);
            lastDeltaDates.put(COL_DEVICESTATUS, startDeltaDate);
            lastDeltaDates.put(COL_TREATMENTS, startDeltaDate);
            lastDeltaDates.put(COL_PROFILE, (long)0); // send profiles on every init

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

            apiUrl = nsConfig.url;
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
            logError(ex, "Error initializing");
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
            logError(ex, "Error destroying");
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

            syncCycle();

            EventNSClientNewLog.emit("NSCLIENT", "Batch completed");
        } catch (Exception ex) {
            logError(ex, "Error in batchCycle");
        }
    }

    private void syncCycle() {

        if (!getStatus()) {
            return;
        }

        uploadChanges();
        downloadChanges();
    }

    private boolean getStatus() {
        Call<ResponseBody> call = apiService.status(hashedApiSecret);
        Response<ResponseBody> response = null;
        String errorMessage = "Failed STATUS from " + apiUrl;
        try {
            response = call.execute();
        } catch (IOException ex) {
            logError(ex, errorMessage);
            return false;
        }
        if (response == null || (!response.isSuccessful())) {
            if (response.raw().code() == 401) {
                logError("Authentication failure (bad API Secret?)");
            }
            else {
                logError(errorMessage);
            }
            return false;
        }

        return true;
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
                    } else if (dbr.action.equals("dbRemove")) {
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
        PowerManager.WakeLock wakeLock = acquireWakeLock();
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

            if (responseJson.has("_id")) {
                String mongoId = responseJson.getString("_id");

                Treatment treatment = MainApp.getDbHelper().findTreatmentById(dbr._id);
                if (treatment != null) {
                    treatment._id = mongoId;
                    MainApp.getDbHelper().update(treatment);
                }
            }

            return true;
        } catch (Exception ex) {
            logError(ex, "Error dbAdd");
            return false;
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private boolean dbRemove(DbRequest dbr) {
        PowerManager.WakeLock wakeLock = acquireWakeLock();
        try {
            Call<ResponseBody> call = apiService.delete(hashedApiSecret, dbr.collection, dbr._id);
            Response<ResponseBody> response = call.execute();
            if (response == null || (!response.isSuccessful() && response.raw().code() != 404)) {
                logError("Failed DBREMOVE " + dbr.collection + " " + dbr._id);
                return false;
            }
            EventNSClientNewLog.emit("DBREMOVE " + dbr.collection, "Sent " + dbr._id);

            mUploadQueue.removeID("dbRemove", dbr._id);

            return true;
        } catch (IOException ex) {
            logError(ex, "Error dbRemove");
            return false;
        } finally {
            if (wakeLock.isHeld()) wakeLock.release();
        }
    }

    private boolean downloadChanges() {

        try {
            Integer defaultCount = 1000;
            JSONObject inputJson = new JSONObject();

            JSONObject colJson = new JSONObject();
            colJson.put("from", lastDeltaDates.get(COL_TREATMENTS));
            inputJson.put(COL_TREATMENTS, colJson);

            colJson = new JSONObject();
            colJson.put("from", lastDeltaDates.get(COL_DEVICESTATUS));
            inputJson.put(COL_DEVICESTATUS, colJson);

            colJson = new JSONObject();
            colJson.put("from", lastDeltaDates.get(COL_ENTRIES));
            inputJson.put(COL_ENTRIES, colJson);

            colJson = new JSONObject();
            colJson.put("from", lastDeltaDates.get(COL_PROFILE));
            inputJson.put(COL_PROFILE, colJson);

            PowerManager.WakeLock wakeLock = acquireWakeLock();
            try {
                RequestBody body = RequestBody.create(MediaType.parse("application/json"), inputJson.toString());
                Call<ResponseBody> call = apiService.delta(hashedApiSecret, defaultCount, body);
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

                if (data.has("entries")) {
                    JSONArray entries = data.getJSONArray("entries");
                    handleEntries(entries);
                }

                if (data.has("profile")) {
                    JSONArray profiles = data.getJSONArray("profile");
                    handleProfiles(profiles);
                }
            } finally {
                if (wakeLock.isHeld()) wakeLock.release();
            }
            return true;
        } catch (Exception ex) {
            logError(ex, "Error in download");
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
                if (!jsonTreatment.has("mills") && jsonTreatment.has("created")) {
                    jsonTreatment.put("mills", jsonTreatment.get("created"));
                }
                if (!jsonTreatment.has("mills") && jsonTreatment.has("modified")) {
                    jsonTreatment.put("mills", jsonTreatment.get("modified"));
                }
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

                if (treatment.getModified() > lastDeltaDates.get(COL_TREATMENTS)) {
                    lastDeltaDates.put(COL_TREATMENTS, treatment.getModified());
                }
            } catch (JSONException ex) {
                logError(ex, "Error handling treatments");
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
                    Long modified = getLong(jsonStatus, "modified");

                    // remove from upload queue if Ack is failing
                    mUploadQueue.removeID(jsonStatus);

                    if (modified != null && modified > lastDeltaDates.get(COL_DEVICESTATUS)) {
                        lastDeltaDates.put(COL_DEVICESTATUS, modified);
                    }
                } catch (JSONException ex) {
                    logError(ex, "Error handling devicestatus");
                }
            }
            BroadcastDeviceStatus.handleNewDeviceStatus(devicestatuses, MainApp.instance().getApplicationContext(), true);
        }
    }

    private void handleEntries(JSONArray entries) {
        if (entries.length() > 0) {
            JSONArray mbgs = new JSONArray();
            JSONArray cals = new JSONArray();
            JSONArray sgvs = new JSONArray();

            for (Integer index = 0; index < entries.length(); index++) {
                try {
                    JSONObject entry = entries.getJSONObject(index);

                    if (entry.has("type")) {
                        mUploadQueue.removeID(entry);

                        String type = entry.getString("type");
                        switch (type) {
                            case "cal":
                                if (!entry.has("date") && entry.has("created")) {
                                    entry.put("date", entry.get("created"));
                                }
                                if (!entry.has("date") && entry.has("modified")) {
                                    entry.put("date", entry.get("modified"));
                                }
                                cals.put(entry);
                                break;

                            case "mbg":
                                if (!entry.has("mbg") && entry.has("mgdl")) {
                                    entry.put("mbg", entry.get("mgdl"));
                                }
                                if (!entry.has("mills") && entry.has("date")) {
                                    entry.put("mills", entry.get("date"));
                                }
                                if (!entry.has("mills") && entry.has("created")) {
                                    entry.put("mills", entry.get("created"));
                                }
                                if (!entry.has("mills") && entry.has("modified")) {
                                    entry.put("mills", entry.get("modified"));
                                }
                                mbgs.put(entry);
                                break;

                            case "sgv":
                                if (!entry.has("mgdl") && entry.has("sgv")) {
                                    entry.put("mgdl", entry.get("sgv"));
                                }
                                if (!entry.has("mills") && entry.has("date")) {
                                    entry.put("mills", entry.get("date"));
                                }
                                if (!entry.has("mills") && entry.has("created")) {
                                    entry.put("mills", entry.get("created"));
                                }
                                if (!entry.has("mills") && entry.has("modified")) {
                                    entry.put("mills", entry.get("modified"));
                                }
                                sgvs.put(entry);
                                break;
                        }

                        Long modified = getLong(entry, "modified");
                        if (modified != null && modified > lastDeltaDates.get(COL_ENTRIES)) {
                            lastDeltaDates.put(COL_ENTRIES, modified);
                        }
                    }
                } catch (JSONException ex) {
                    logError(ex, "Error handling entries");
                }
            }

            if (cals.length() > 0) {
                BroadcastCals.handleNewCal(cals, MainApp.instance().getApplicationContext(), true);
                EventNSClientNewLog.emit("DATA", "received " + cals.length() + " cals");
            }
            if (mbgs.length() > 0) {
                BroadcastMbgs.handleNewMbg(mbgs, MainApp.instance().getApplicationContext(), true);
                EventNSClientNewLog.emit("DATA", "received " + mbgs.length() + " mbgs");
            }
            if (sgvs.length() > 0) {
                // removed notification dismiss (after 15 minutes), alarms are not yet supported

                BroadcastSgvs.handleNewSgv(sgvs, MainApp.instance().getApplicationContext(), true);
                EventNSClientNewLog.emit("DATA", "received " + sgvs.length() + " sgvs");
            }
        }
    }

    private void handleProfiles(JSONArray profiles) {
        if (profiles.length() > 0) {
            EventNSClientNewLog.emit("DATA", "received profiles");

            for (Integer index = 0; index < profiles.length(); index++) {
                try {
                    JSONObject jsonProfile = profiles.getJSONObject(index);
                    Long modified = getLong(jsonProfile, "modified");

                    ProfileStore profileStore = new ProfileStore(jsonProfile);
                    BroadcastProfile.handleNewTreatment(profileStore, MainApp.instance().getApplicationContext(), true);

                    if (modified != null && modified > lastDeltaDates.get(COL_PROFILE)) {
                        lastDeltaDates.put(COL_PROFILE, modified);
                    }
                } catch (JSONException ex) {
                    logError(ex, "Error handling profiles");
                }
            }
        }
    }

    private static Long getLong(JSONObject data, String key) {
        if (data.has(key)) {
            try {
                return data.getLong(key);
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

    private PowerManager.WakeLock acquireWakeLock() {
        PowerManager powerManager = (PowerManager) MainApp.instance().getApplicationContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RestTransportService");
        wakeLock.acquire();
        return wakeLock;
    }

    private void logError(Exception ex, String defaultMessage) {
        String message = ex.getMessage();
        logError(message != null ? message
                : defaultMessage + " (" + ex.getClass().getName() + ")");
    }

    private void logError(String message) {
        EventNSClientNewLog.emit("ERROR", message);
        log.error(message);
    }
}

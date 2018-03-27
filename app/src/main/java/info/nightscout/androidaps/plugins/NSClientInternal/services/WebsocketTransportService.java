package info.nightscout.androidaps.plugins.NSClientInternal.services;

import android.content.Context;
import android.os.PowerManager;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAuthAck;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastAnnouncement;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastCals;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastDeviceStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastFood;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastMbgs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastProfile;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastSgvs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSettingsStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSgv;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientStatus;
import info.nightscout.androidaps.plugins.Overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.Overview.notifications.Notification;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.FabricPrivacy;
import info.nightscout.utils.JsonHelper;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

/**
 * Created by PetrOndrusek on 27.03.2018.
 */

public class WebsocketTransportService implements TransportServiceInterface {
    private static Logger log = LoggerFactory.getLogger(WebsocketTransportService.class);

    private boolean isConnected = false;
    private boolean hasWriteAuth = false;
    private String nightscoutVersionName = "";
    private Integer nightscoutVersionCode = 0;
    private NSConfiguration nsConfig = null;
    private Socket mSocket;
    private String nsAPIhashCode = "";
    private ProfileStore profileStore;
    private static Integer dataCounter = 0;
    private static Integer connectCounter = 0;
    private long latestDateInReceivedData = 0;
    private NSClientService mNSClientService = null;

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


    public WebsocketTransportService(NSConfiguration nsConfig, NSClientService nsClientService)
    {
        registerBus();
        this.nsConfig = nsConfig;
        this.mNSClientService = nsClientService;
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
                mSocket.on(Socket.EVENT_CONNECT, onConnect);
                mSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
                mSocket.on(Socket.EVENT_PING, onPing);
                MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "do connect"));
                mSocket.connect();
                mSocket.on("dataUpdate", onDataUpdate);
                mSocket.on("announcement", onAnnouncement);
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


    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            connectCounter++;
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "connect #" + connectCounter + " event. ID: " + mSocket.id()));
            sendAuthMessage(new NSAuthAck());
        }
    };

    public void sendAuthMessage(NSAuthAck ack) {
        JSONObject authMessage = new JSONObject();
        try {
            authMessage.put("client", "Android_" + nsConfig.device);
            authMessage.put("history", nsConfig.hours);
            authMessage.put("status", true); // receive status
            authMessage.put("from", latestDateInReceivedData); // send data newer than
            authMessage.put("secret", nsAPIhashCode);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
            return;
        }
        MainApp.bus().post(new EventNSClientNewLog("AUTH", "requesting auth"));
        mSocket.emit("authorize", authMessage, ack);
    }

    private Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "disconnect event"));
        }
    };

    private Emitter.Listener onPing = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            if (Config.detailedLog)
                MainApp.bus().post(new EventNSClientNewLog("PING", "received"));
            // send data if there is something waiting
            resend("Ping received");
        }
    };

    private Emitter.Listener onDataUpdate = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            NSClientService.handler.post(new Runnable() {
                @Override
                public void run() {
                    PowerManager powerManager = (PowerManager) MainApp.instance().getApplicationContext().getSystemService(Context.POWER_SERVICE);
                    PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "onDataUpdate");
                    wakeLock.acquire();
                    try {

                        JSONObject data = (JSONObject) args[0];
                        boolean broadcastProfile = false;
                        try {
                            // delta means only increment/changes are comming
                            boolean isDelta = data.has("delta");
                            boolean isFull = !isDelta;
                            MainApp.bus().post(new EventNSClientNewLog("DATA", "Data packet #" + dataCounter++ + (isDelta ? " delta" : " full")));

                            if (data.has("profiles")) {
                                JSONArray profiles = data.getJSONArray("profiles");
                                if (profiles.length() > 0) {
                                    JSONObject profile = (JSONObject) profiles.get(profiles.length() - 1);
                                    profileStore = new ProfileStore(profile);
                                    broadcastProfile = true;
                                    MainApp.bus().post(new EventNSClientNewLog("PROFILE", "profile received"));
                                }
                            }

                            if (data.has("status")) {
                                JSONObject status = data.getJSONObject("status");
                                NSSettingsStatus nsSettingsStatus = NSSettingsStatus.getInstance().setData(status);

                                if (!status.has("versionNum")) {
                                    if (status.getInt("versionNum") < Config.SUPPORTEDNSVERSION) {
                                        MainApp.bus().post(new EventNSClientNewLog("ERROR", "Unsupported Nightscout version !!!!"));
                                    }
                                } else {
                                    nightscoutVersionName = nsSettingsStatus.getVersion();
                                    nightscoutVersionCode = nsSettingsStatus.getVersionNum();
                                }
                                BroadcastStatus.handleNewStatus(nsSettingsStatus, MainApp.instance().getApplicationContext(), isDelta);

                    /*  Other received data to 2016/02/10
                        {
                          status: 'ok'
                          , name: env.name
                          , version: env.version
                          , versionNum: versionNum (for ver 1.2.3 contains 10203)
                          , serverTime: new Date().toISOString()
                          , apiEnabled: apiEnabled
                          , careportalEnabled: apiEnabled && env.settings.enable.indexOf('careportal') > -1
                          , boluscalcEnabled: apiEnabled && env.settings.enable.indexOf('boluscalc') > -1
                          , head: env.head
                          , settings: env.settings
                          , extendedSettings: ctx.plugins && ctx.plugins.extendedClientSettings ? ctx.plugins.extendedClientSettings(env.extendedSettings) : {}
                          , activeProfile ..... calculated from treatments or missing
                        }
                     */
                            } else if (!isDelta) {
                                MainApp.bus().post(new EventNSClientNewLog("ERROR", "Unsupported Nightscout version !!!!"));
                            }

                            // If new profile received or change detected broadcast it
                            if (broadcastProfile && profileStore != null) {
                                BroadcastProfile.handleNewTreatment(profileStore, MainApp.instance().getApplicationContext(), isDelta);
                                MainApp.bus().post(new EventNSClientNewLog("PROFILE", "broadcasting"));
                            }

                            if (data.has("treatments")) {
                                JSONArray treatments = data.getJSONArray("treatments");
                                JSONArray removedTreatments = new JSONArray();
                                JSONArray updatedTreatments = new JSONArray();
                                JSONArray addedTreatments = new JSONArray();
                                if (treatments.length() > 0)
                                    MainApp.bus().post(new EventNSClientNewLog("DATA", "received " + treatments.length() + " treatments"));
                                for (Integer index = 0; index < treatments.length(); index++) {
                                    JSONObject jsonTreatment = treatments.getJSONObject(index);
                                    NSTreatment treatment = new NSTreatment(jsonTreatment);

                                    // remove from upload queue if Ack is failing
                                    UploadQueue.removeID(jsonTreatment);
                                    //Find latest date in treatment
                                    if (treatment.getMills() != null && treatment.getMills() < System.currentTimeMillis())
                                        if (treatment.getMills() > latestDateInReceivedData)
                                            latestDateInReceivedData = treatment.getMills();

                                    if (treatment.getAction() == null) {
                                        addedTreatments.put(jsonTreatment);
                                    } else if (treatment.getAction().equals("update")) {
                                        updatedTreatments.put(jsonTreatment);
                                    } else if (treatment.getAction().equals("remove")) {
                                        if (treatment.getMills() != null && treatment.getMills() > System.currentTimeMillis() - 24 * 60 * 60 * 1000L) // handle 1 day old deletions only
                                            removedTreatments.put(jsonTreatment);
                                    }
                                }
                                if (removedTreatments.length() > 0) {
                                    BroadcastTreatment.handleRemovedTreatment(removedTreatments, isDelta);
                                }
                                if (updatedTreatments.length() > 0) {
                                    BroadcastTreatment.handleChangedTreatment(updatedTreatments, isDelta);
                                }
                                if (addedTreatments.length() > 0) {
                                    BroadcastTreatment.handleNewTreatment(addedTreatments, isDelta);
                                }
                            }
                            if (data.has("devicestatus")) {
                                JSONArray devicestatuses = data.getJSONArray("devicestatus");
                                if (devicestatuses.length() > 0) {
                                    MainApp.bus().post(new EventNSClientNewLog("DATA", "received " + devicestatuses.length() + " devicestatuses"));
                                    for (Integer index = 0; index < devicestatuses.length(); index++) {
                                        JSONObject jsonStatus = devicestatuses.getJSONObject(index);
                                        // remove from upload queue if Ack is failing
                                        UploadQueue.removeID(jsonStatus);
                                    }
                                    BroadcastDeviceStatus.handleNewDeviceStatus(devicestatuses, MainApp.instance().getApplicationContext(), isDelta);
                                }
                            }
                            if (data.has("food")) {
                                JSONArray foods = data.getJSONArray("food");
                                JSONArray removedFoods = new JSONArray();
                                JSONArray updatedFoods = new JSONArray();
                                JSONArray addedFoods = new JSONArray();
                                if (foods.length() > 0)
                                    MainApp.bus().post(new EventNSClientNewLog("DATA", "received " + foods.length() + " foods"));
                                for (Integer index = 0; index < foods.length(); index++) {
                                    JSONObject jsonFood = foods.getJSONObject(index);

                                    // remove from upload queue if Ack is failing
                                    UploadQueue.removeID(jsonFood);

                                    String action = JsonHelper.safeGetString(jsonFood, "action");

                                    if (action == null) {
                                        addedFoods.put(jsonFood);
                                    } else if (action.equals("update")) {
                                        updatedFoods.put(jsonFood);
                                    } else if (action.equals("remove")) {
                                        removedFoods.put(jsonFood);
                                    }
                                }
                                if (removedFoods.length() > 0) {
                                    BroadcastFood.handleRemovedFood(removedFoods, MainApp.instance().getApplicationContext(), isDelta);
                                }
                                if (updatedFoods.length() > 0) {
                                    BroadcastFood.handleChangedFood(updatedFoods, MainApp.instance().getApplicationContext(), isDelta);
                                }
                                if (addedFoods.length() > 0) {
                                    BroadcastFood.handleNewFood(addedFoods, MainApp.instance().getApplicationContext(), isDelta);
                                }
                            }
                            if (data.has("mbgs")) {
                                JSONArray mbgs = data.getJSONArray("mbgs");
                                if (mbgs.length() > 0)
                                    MainApp.bus().post(new EventNSClientNewLog("DATA", "received " + mbgs.length() + " mbgs"));
                                for (Integer index = 0; index < mbgs.length(); index++) {
                                    JSONObject jsonMbg = mbgs.getJSONObject(index);
                                    // remove from upload queue if Ack is failing
                                    UploadQueue.removeID(jsonMbg);
                                }
                                BroadcastMbgs.handleNewMbg(mbgs, MainApp.instance().getApplicationContext(), isDelta);
                            }
                            if (data.has("cals")) {
                                JSONArray cals = data.getJSONArray("cals");
                                if (cals.length() > 0)
                                    MainApp.bus().post(new EventNSClientNewLog("DATA", "received " + cals.length() + " cals"));
                                // Retreive actual calibration
                                for (Integer index = 0; index < cals.length(); index++) {
                                    // remove from upload queue if Ack is failing
                                    UploadQueue.removeID(cals.optJSONObject(index));
                                }
                                BroadcastCals.handleNewCal(cals, MainApp.instance().getApplicationContext(), isDelta);
                            }
                            if (data.has("sgvs")) {
                                JSONArray sgvs = data.getJSONArray("sgvs");
                                if (sgvs.length() > 0)
                                    MainApp.bus().post(new EventNSClientNewLog("DATA", "received " + sgvs.length() + " sgvs"));
                                for (Integer index = 0; index < sgvs.length(); index++) {
                                    JSONObject jsonSgv = sgvs.getJSONObject(index);
                                    // MainApp.bus().post(new EventNSClientNewLog("DATA", "svg " + sgvs.getJSONObject(index).toString());
                                    NSSgv sgv = new NSSgv(jsonSgv);
                                    // Handle new sgv here
                                    // remove from upload queue if Ack is failing
                                    UploadQueue.removeID(jsonSgv);
                                    //Find latest date in sgv
                                    if (sgv.getMills() != null && sgv.getMills() < System.currentTimeMillis())
                                        if (sgv.getMills() > latestDateInReceivedData)
                                            latestDateInReceivedData = sgv.getMills();
                                }
                                // Was that sgv more less 15 mins ago ?
                                boolean lessThan15MinAgo = false;
                                if ((System.currentTimeMillis() - latestDateInReceivedData) / (60 * 1000L) < 15L)
                                    lessThan15MinAgo = true;
                                if (Notification.isAlarmForStaleData() && lessThan15MinAgo) {
                                    MainApp.bus().post(new EventDismissNotification(Notification.NSALARM));
                                }
                                BroadcastSgvs.handleNewSgv(sgvs, MainApp.instance().getApplicationContext(), isDelta);
                            }
                            MainApp.bus().post(new EventNSClientNewLog("LAST", DateUtil.dateAndTimeString(latestDateInReceivedData)));
                        } catch (JSONException e) {
                            log.error("Unhandled exception", e);
                        }
                        //MainApp.bus().post(new EventNSClientNewLog("NSCLIENT", "onDataUpdate end");
                    } finally {
                        if (wakeLock.isHeld()) wakeLock.release();
                    }
                }
            });
        }
    };


    private Emitter.Listener onAnnouncement = new Emitter.Listener() {
        /*
        {
        "level":0,
        "title":"Announcement",
        "message":"test",
        "plugin":{"name":"treatmentnotify","label":"Treatment Notifications","pluginType":"notification","enabled":true},
        "group":"Announcement",
        "isAnnouncement":true,
        "key":"9ac46ad9a1dcda79dd87dae418fce0e7955c68da"
        }
         */
        @Override
        public void call(final Object... args) {
            JSONObject data;
            try {
                data = (JSONObject) args[0];
            } catch (Exception e) {
                FabricPrivacy.log("Wrong Announcement from NS: " + args[0]);
                return;
            }
            if (Config.detailedLog)
                try {
                    MainApp.bus().post(new EventNSClientNewLog("ANNOUNCEMENT", JsonHelper.safeGetString(data, "message", "received")));
                } catch (Exception e) {
                    FabricPrivacy.logException(e);
                }
            BroadcastAnnouncement.handleAnnouncement(data, mNSClientService.getApplicationContext());
            log.debug(data.toString());
        }
    };
}

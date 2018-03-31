package info.nightscout.androidaps.plugins.NSClientInternal.services;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.j256.ormlite.dao.CloseableIterator;
import com.squareup.otto.Subscribe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.sql.SQLException;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.plugins.NSClientInternal.NSClientPlugin;
import info.nightscout.androidaps.plugins.NSClientInternal.UploadQueue;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAddAck;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSAuthAck;
import info.nightscout.androidaps.plugins.NSClientInternal.acks.NSUpdateAck;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastAlarm;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastAnnouncement;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastCals;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastClearAlarm;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastDeviceStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastFood;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastMbgs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastProfile;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastSgvs;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.broadcasts.BroadcastUrgentAlarm;
import info.nightscout.androidaps.plugins.NSClientInternal.data.AlarmAck;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSConfiguration;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSettingsStatus;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSSgv;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSTreatment;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientNewLog;
import info.nightscout.androidaps.plugins.NSClientInternal.events.EventNSClientStatus;
import info.nightscout.androidaps.plugins.Overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
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

public class WebsocketTransportService extends AbstractTransportService {
    private static Logger log = LoggerFactory.getLogger(WebsocketTransportService.class);

    private Socket mSocket;
    private String nsAPIhashCode = "";
    private ProfileStore profileStore;
    private static Integer dataCounter = 0;
    private static Integer connectCounter = 0;
    private long latestDateInReceivedData = 0;
    private long lastResendTime = 0;

    public WebsocketTransportService(NSConfiguration nsConfig, NSClientService nsClientService, Handler handler, UploadQueue uploadQueue)
    {
        registerBus();
        this.nsConfig = nsConfig;
        this.mNSClientService = nsClientService;
        this.mHandler = handler;
        this.mUploadQueue = uploadQueue;
    }


    @Override
    public void initialize() {

        dataCounter = 0;
        latestDateInReceivedData = 0;

        if (!nsConfig.apiSecret.equals(""))
            nsAPIhashCode = Hashing.sha1().hashString(nsConfig.apiSecret, Charsets.UTF_8).toString();

        EventNSClientStatus.emit("Initializing");
        if (MainApp.getSpecificPlugin(NSClientPlugin.class).paused) {
            EventNSClientNewLog.emit("NSCLIENT", "paused");
            EventNSClientStatus.emit("Paused");
        } else if (!nsConfig.enabled) {
            EventNSClientNewLog.emit("NSCLIENT", "disabled");
            EventNSClientStatus.emit("Disabled");
        } else if (!nsConfig.url.equals("")) {
            try {
                EventNSClientStatus.emit("Connecting ...");
                IO.Options opt = new IO.Options();
                opt.forceNew = true;
                opt.reconnection = true;
                mSocket = IO.socket(nsConfig.url, opt);
                mSocket.on(Socket.EVENT_CONNECT, onConnect);
                mSocket.on(Socket.EVENT_DISCONNECT, onDisconnect);
                mSocket.on(Socket.EVENT_PING, onPing);
                EventNSClientNewLog.emit("NSCLIENT", "do connect");
                mSocket.connect();
                mSocket.on("dataUpdate", onDataUpdate);
                mSocket.on("announcement", onAnnouncement);
                mSocket.on("alarm", onAlarm);
                mSocket.on("urgent_alarm", onUrgentAlarm);
                mSocket.on("clear_alarm", onClearAlarm);
            } catch (URISyntaxException | RuntimeException e) {
                EventNSClientNewLog.emit("NSCLIENT", "Wrong URL syntax");
                EventNSClientStatus.emit("Wrong URL syntax");
            }
        } else {
            EventNSClientNewLog.emit("NSCLIENT", "No NS URL specified");
            EventNSClientStatus.emit("Not configured");
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

            EventNSClientNewLog.emit("NSCLIENT", "destroy");
            isConnected = false;
            hasWriteAuth = false;
            mSocket.disconnect();
            mSocket = null;
        }
    }


    @Override
    public void resend(String reason, boolean startNow) {

        if (mUploadQueue.size() == 0)
            return;

        if (!isConnected || !hasWriteAuth) return;

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mSocket == null || !mSocket.connected()) return;

                if (lastResendTime > System.currentTimeMillis() - 10 * 1000L) {
                    log.debug("Skipping resend by lastResendTime: " + ((System.currentTimeMillis() - lastResendTime) / 1000L) + " sec");
                    return;
                }
                lastResendTime = System.currentTimeMillis();

                EventNSClientNewLog.emit("QUEUE", "Resend started: " + reason);

                CloseableIterator<DbRequest> iterator = null;
                int maxcount = 30;
                try {
                    iterator = MainApp.getDbHelper().getDbRequestInterator();
                    try {
                        while (iterator.hasNext() && maxcount > 0) {
                            DbRequest dbr = iterator.next();
                            if (dbr.action.equals("dbAdd")) {
                                NSAddAck addAck = new NSAddAck();
                                dbAdd(dbr, addAck);
                            } else if (dbr.action.equals("dbRemove")) {
                                NSUpdateAck removeAck = new NSUpdateAck(dbr.action, dbr._id);
                                dbRemove(dbr, removeAck);
                            } else if (dbr.action.equals("dbUpdate")) {
                                NSUpdateAck updateAck = new NSUpdateAck(dbr.action, dbr._id);
                                dbUpdate(dbr, updateAck);
                            } else if (dbr.action.equals("dbUpdateUnset")) {
                                NSUpdateAck updateUnsetAck = new NSUpdateAck(dbr.action, dbr._id);
                                dbUpdateUnset(dbr, updateUnsetAck);
                            }
                            maxcount--;
                        }
                    } finally {
                        iterator.close();
                    }
                } catch (SQLException e) {
                    log.error("Unhandled exception", e);
                }

                EventNSClientNewLog.emit("QUEUE", "Resend ended: " + reason);
            }
        });
    }

    public void dbAdd(DbRequest dbr, NSAddAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("data", new JSONObject(dbr.data));
            mSocket.emit("dbAdd", message, ack);
            EventNSClientNewLog.emit("DBADD " + dbr.collection, "Sent " + dbr.nsClientID);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }

    public void dbRemove(DbRequest dbr, NSUpdateAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("_id", dbr._id);
            mSocket.emit("dbRemove", message, ack);
            EventNSClientNewLog.emit("DBREMOVE " + dbr.collection, "Sent " + dbr._id);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }

    public void dbUpdate(DbRequest dbr, NSUpdateAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("_id", dbr._id);
            message.put("data", new JSONObject(dbr.data));
            mSocket.emit("dbUpdate", message, ack);
            EventNSClientNewLog.emit("DBUPDATE " + dbr.collection, "Sent " + dbr._id);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }

    public void dbUpdateUnset(DbRequest dbr, NSUpdateAck ack) {
        try {
            if (!isConnected || !hasWriteAuth) return;
            JSONObject message = new JSONObject();
            message.put("collection", dbr.collection);
            message.put("_id", dbr._id);
            message.put("data", new JSONObject(dbr.data));
            mSocket.emit("dbUpdateUnset", message, ack);
            EventNSClientNewLog.emit("DBUPDATEUNSET " + dbr.collection, "Sent " + dbr._id);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }


    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            connectCounter++;
            EventNSClientNewLog.emit("NSCLIENT", "connect #" + connectCounter + " event. ID: " + mSocket.id());
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
        EventNSClientNewLog.emit("AUTH", "requesting auth");
        mSocket.emit("authorize", authMessage, ack);
    }

    private Emitter.Listener onDisconnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            EventNSClientNewLog.emit("NSCLIENT", "disconnect event");
        }
    };

    private Emitter.Listener onPing = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            if (Config.detailedLog)
                EventNSClientNewLog.emit("PING", "received");
            // send data if there is something waiting
            resend("Ping received", true);
        }
    };

    private Emitter.Listener onDataUpdate = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            mHandler.post(new Runnable() {
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
                            EventNSClientNewLog.emit("DATA", "Data packet #" + dataCounter++ + (isDelta ? " delta" : " full"));

                            if (data.has("profiles")) {
                                JSONArray profiles = data.getJSONArray("profiles");
                                if (profiles.length() > 0) {
                                    JSONObject profile = (JSONObject) profiles.get(profiles.length() - 1);
                                    profileStore = new ProfileStore(profile);
                                    broadcastProfile = true;
                                    EventNSClientNewLog.emit("PROFILE", "profile received");
                                }
                            }

                            if (data.has("status")) {
                                JSONObject status = data.getJSONObject("status");
                                handleStatus(status, isDelta);
                            } else if (!isDelta) {
                                EventNSClientNewLog.emit("ERROR", "Unsupported Nightscout version !!!!");
                            }

                            // If new profile received or change detected broadcast it
                            if (broadcastProfile && profileStore != null) {
                                BroadcastProfile.handleNewTreatment(profileStore, MainApp.instance().getApplicationContext(), isDelta);
                                EventNSClientNewLog.emit("PROFILE", "broadcasting");
                            }

                            if (data.has("treatments")) {
                                JSONArray treatments = data.getJSONArray("treatments");
                                JSONArray removedTreatments = new JSONArray();
                                JSONArray updatedTreatments = new JSONArray();
                                JSONArray addedTreatments = new JSONArray();
                                if (treatments.length() > 0)
                                    EventNSClientNewLog.emit("DATA", "received " + treatments.length() + " treatments");
                                for (Integer index = 0; index < treatments.length(); index++) {
                                    JSONObject jsonTreatment = treatments.getJSONObject(index);
                                    NSTreatment treatment = new NSTreatment(jsonTreatment);

                                    // remove from upload queue if Ack is failing
                                    mUploadQueue.removeID(jsonTreatment);
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
                                    EventNSClientNewLog.emit("DATA", "received " + devicestatuses.length() + " devicestatuses");
                                    for (Integer index = 0; index < devicestatuses.length(); index++) {
                                        JSONObject jsonStatus = devicestatuses.getJSONObject(index);
                                        // remove from upload queue if Ack is failing
                                        mUploadQueue.removeID(jsonStatus);
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
                                    EventNSClientNewLog.emit("DATA", "received " + foods.length() + " foods");
                                for (Integer index = 0; index < foods.length(); index++) {
                                    JSONObject jsonFood = foods.getJSONObject(index);

                                    // remove from upload queue if Ack is failing
                                    mUploadQueue.removeID(jsonFood);

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
                                    EventNSClientNewLog.emit("DATA", "received " + mbgs.length() + " mbgs");
                                for (Integer index = 0; index < mbgs.length(); index++) {
                                    JSONObject jsonMbg = mbgs.getJSONObject(index);
                                    // remove from upload queue if Ack is failing
                                    mUploadQueue.removeID(jsonMbg);
                                }
                                BroadcastMbgs.handleNewMbg(mbgs, MainApp.instance().getApplicationContext(), isDelta);
                            }
                            if (data.has("cals")) {
                                JSONArray cals = data.getJSONArray("cals");
                                if (cals.length() > 0)
                                    EventNSClientNewLog.emit("DATA", "received " + cals.length() + " cals");
                                // Retreive actual calibration
                                for (Integer index = 0; index < cals.length(); index++) {
                                    // remove from upload queue if Ack is failing
                                    mUploadQueue.removeID(cals.optJSONObject(index));
                                }
                                BroadcastCals.handleNewCal(cals, MainApp.instance().getApplicationContext(), isDelta);
                            }
                            if (data.has("sgvs")) {
                                JSONArray sgvs = data.getJSONArray("sgvs");
                                if (sgvs.length() > 0)
                                    EventNSClientNewLog.emit("DATA", "received " + sgvs.length() + " sgvs");
                                for (Integer index = 0; index < sgvs.length(); index++) {
                                    JSONObject jsonSgv = sgvs.getJSONObject(index);
                                    // EventNSClientNewLog.emit("DATA", "svg " + sgvs.getJSONObject(index).toString();
                                    NSSgv sgv = new NSSgv(jsonSgv);
                                    // Handle new sgv here
                                    // remove from upload queue if Ack is failing
                                    mUploadQueue.removeID(jsonSgv);
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
                                    EventDismissNotification.emit(Notification.NSALARM);
                                }
                                BroadcastSgvs.handleNewSgv(sgvs, MainApp.instance().getApplicationContext(), isDelta);
                            }
                            EventNSClientNewLog.emit("LAST", DateUtil.dateAndTimeString(latestDateInReceivedData));
                        } catch (JSONException e) {
                            log.error("Unhandled exception", e);
                        }
                        //EventNSClientNewLog.emit("NSCLIENT", "onDataUpdate end");
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
                    EventNSClientNewLog.emit("ANNOUNCEMENT", JsonHelper.safeGetString(data, "message", "received"));
                } catch (Exception e) {
                    FabricPrivacy.logException(e);
                }
            BroadcastAnnouncement.handleAnnouncement(data, mNSClientService.getApplicationContext());
            log.debug(data.toString());
        }
    };

    private Emitter.Listener onAlarm = new Emitter.Listener() {
        /*
        {
        "level":1,
        "title":"Warning HIGH",
        "message":"BG Now: 5 -0.2 → mmol\/L\nRaw BG: 4.8 mmol\/L Čistý\nBG 15m: 4.8 mmol\/L\nIOB: -0.02U\nCOB: 0g",
        "eventName":"high",
        "plugin":{"name":"simplealarms","label":"Simple Alarms","pluginType":"notification","enabled":true},
        "pushoverSound":"climb",
        "debug":{"lastSGV":5,"thresholds":{"bgHigh":180,"bgTargetTop":75,"bgTargetBottom":72,"bgLow":70}},
        "group":"default",
        "key":"simplealarms_1"
        }
         */
        @Override
        public void call(final Object... args) {
            if (Config.detailedLog)
                EventNSClientNewLog.emit("ALARM", "received");
            JSONObject data;
            try {
                data = (JSONObject) args[0];
            } catch (Exception e) {
                FabricPrivacy.log("Wrong alarm from NS: " + args[0]);
                return;
            }
            BroadcastAlarm.handleAlarm(data, mNSClientService.getApplicationContext());
            log.debug(data.toString());
        }
    };

    private Emitter.Listener onUrgentAlarm = new Emitter.Listener() {
        /*
        {
        "level":2,
        "title":"Urgent HIGH",
        "message":"BG Now: 5.2 -0.1 → mmol\/L\nRaw BG: 5 mmol\/L Čistý\nBG 15m: 5 mmol\/L\nIOB: 0.00U\nCOB: 0g",
        "eventName":"high",
        "plugin":{"name":"simplealarms","label":"Simple Alarms","pluginType":"notification","enabled":true},
        "pushoverSound":"persistent",
        "debug":{"lastSGV":5.2,"thresholds":{"bgHigh":80,"bgTargetTop":75,"bgTargetBottom":72,"bgLow":70}},
        "group":"default",
        "key":"simplealarms_2"
        }
         */
        @Override
        public void call(final Object... args) {
            JSONObject data;
            try {
                data = (JSONObject) args[0];
            } catch (Exception e) {
                FabricPrivacy.log("Wrong Urgent alarm from NS: " + args[0]);
                return;
            }
            if (Config.detailedLog)
                EventNSClientNewLog.emit("URGENTALARM", "received");
            BroadcastUrgentAlarm.handleUrgentAlarm(data, mNSClientService.getApplicationContext());
            log.debug(data.toString());
        }
    };

    private Emitter.Listener onClearAlarm = new Emitter.Listener() {
        /*
        {
        "clear":true,
        "title":"All Clear",
        "message":"default - Urgent was ack'd",
        "group":"default"
        }
         */
        @Override
        public void call(final Object... args) {
            JSONObject data;
            try {
                data = (JSONObject) args[0];
            } catch (Exception e) {
                FabricPrivacy.log("Wrong Urgent alarm from NS: " + args[0]);
                return;
            }
            if (Config.detailedLog)
                EventNSClientNewLog.emit("CLEARALARM", "received");
            BroadcastClearAlarm.handleClearAlarm(data, mNSClientService.getApplicationContext());
            log.debug(data.toString());
        }
    };

    public void sendAlarmAck(AlarmAck alarmAck) {
        if (!isConnected || !hasWriteAuth) return;
        mSocket.emit("ack", alarmAck.level, alarmAck.group, alarmAck.silenceTime);
        EventNSClientNewLog.emit("ALARMACK ", alarmAck.level + " " + alarmAck.group + " " + alarmAck.silenceTime);
    }

    @Subscribe
    public void onStatusEvent(NSAuthAck ack) {
        String connectionStatus = "Authenticated (";
        if (ack.read) connectionStatus += "R";
        if (ack.write) connectionStatus += "W";
        if (ack.write_treatment) connectionStatus += "T";
        connectionStatus += ')';
        isConnected = true;
        hasWriteAuth = ack.write && ack.write_treatment;
        EventNSClientStatus.emit(connectionStatus);
        EventNSClientNewLog.emit("AUTH", connectionStatus);
        if (!ack.write) {
            EventNSClientNewLog.emit("ERROR", "Write permission not granted !!!!");
        }
        if (!ack.write_treatment) {
            EventNSClientNewLog.emit("ERROR", "Write treatment permission not granted !!!!");
        }
        if (!hasWriteAuth) {
            Notification noperm = new Notification(Notification.NSCLIENT_NO_WRITE_PERMISSION, MainApp.sResources.getString(R.string.nowritepermission), Notification.URGENT);
            EventNewNotification.emit(noperm);
        } else {
            EventDismissNotification.emit(Notification.NSCLIENT_NO_WRITE_PERMISSION);
        }
    }


    @Subscribe
    public void onStatusEvent(NSUpdateAck ack) {
        if (ack.result) {
            mUploadQueue.removeID(ack.action, ack._id);
            EventNSClientNewLog.emit("DBUPDATE/DBREMOVE", "Acked " + ack._id);
        } else {
            EventNSClientNewLog.emit("ERROR", "DBUPDATE/DBREMOVE Unknown response");
        }
    }


    @Subscribe
    public void onStatusEvent(NSAddAck ack) {
        if (ack.nsClientID != null) {
            mUploadQueue.removeID(ack.json);
            EventNSClientNewLog.emit("DBADD", "Acked " + ack.nsClientID);
        } else {
            EventNSClientNewLog.emit("ERROR", "DBADD Unknown response");
        }
    }
}

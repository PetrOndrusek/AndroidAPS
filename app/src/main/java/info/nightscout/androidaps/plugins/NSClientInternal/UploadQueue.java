package info.nightscout.androidaps.plugins.NSClientInternal;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.j256.ormlite.dao.CloseableIterator;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.db.DatabaseHelper;
import info.nightscout.androidaps.db.DbRequest;
import info.nightscout.androidaps.plugins.NSClientInternal.services.NSClientService;

/**
 * Created by mike on 21.02.2016.
 */
public class UploadQueue {
    private static Logger log = LoggerFactory.getLogger(UploadQueue.class);

    public static String status() {
        return "QUEUE: " + MainApp.getDbHelper().size(DatabaseHelper.DATABASE_DBREQUESTS);
    }

    public static long size() {
        return MainApp.getDbHelper().size(DatabaseHelper.DATABASE_DBREQUESTS);
    }

    private static void startService() {
        if (NSClientService.instance != null && NSClientService.instance.handler == null) {
            Context context = MainApp.instance();
            context.startService(new Intent(context, NSClientService.class));
            SystemClock.sleep(2000);
        }
    }

    public static void add(final DbRequest dbr) {
        startService();
        if (NSClientService.instance != null && NSClientService.instance.handler != null) {
            NSClientService.instance.handler.post(new Runnable() {
                @Override
                public void run() {
                    log.debug("QUEUE adding: " + dbr.data);
                    MainApp.getDbHelper().create(dbr);
                    NSClientPlugin plugin = NSClientPlugin.getPlugin();
                    if (plugin != null) {
                        plugin.resend("newdata", false);
                    }
                }
            });
        }
    }

    public static void clearQueue() {
        startService();
        if (NSClientService.instance != null && NSClientService.instance.handler != null) {
            NSClientService.instance.handler.post(new Runnable() {
                @Override
                public void run() {
                    log.debug("QUEUE ClearQueue");
                    MainApp.getDbHelper().deleteAllDbRequests();
                    log.debug(status());
                }
            });
        }
    }

    public static void removeID(final JSONObject record) {
        startService();
        if (NSClientService.instance != null && NSClientService.instance.handler != null) {
            NSClientService.instance.handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        String id;
                        if (record.has("NSCLIENT_ID")) {
                            id = record.getString("NSCLIENT_ID");
                        } else {
                            return;
                        }
                        if (MainApp.getDbHelper().deleteDbRequest(id) == 1) {
                            log.debug("Removed item from UploadQueue. " + UploadQueue.status());
                        }
                    } catch (JSONException e) {
                        log.error("Unhandled exception", e);
                    }
                }
            });
        }
    }

    public static void removeID(final String action, final String _id) {
        if (_id == null || _id.equals(""))
            return;
        startService();
        if (NSClientService.instance != null && NSClientService.instance.handler != null) {
            NSClientService.instance.handler.post(new Runnable() {
                @Override
                public void run() {
                    MainApp.getDbHelper().deleteDbRequestbyMongoId(action, _id);
                }
            });
        }
    }

    public static void removeNsclientID(final String nsclientID) {
        startService();
        if (NSClientService.instance != null && NSClientService.instance.handler != null) {
            NSClientService.instance.handler.post(new Runnable() {
                @Override
                public void run() {
                    if (MainApp.getDbHelper().deleteDbRequest(nsclientID) == 1) {
                        log.debug("Removed item from UploadQueue. " + UploadQueue.status());
                    }
                }
            });
        }
    }

    public String textList() {
        String result = "";
        CloseableIterator<DbRequest> iterator = null;
        try {
            iterator = MainApp.getDbHelper().getDbRequestInterator();
            try {
                while (iterator.hasNext()) {
                    DbRequest dbr = iterator.next();
                    result += "<br>";
                    result += dbr.action.toUpperCase() + " ";
                    result += dbr.collection + ": ";
                    result += dbr.data;
                }
            } finally {
                iterator.close();
            }
        } catch (SQLException e) {
            log.error("Unhandled exception", e);
        }
        return result;
    }

}

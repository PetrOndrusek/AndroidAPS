package info.nightscout.androidaps.plugins.NSClientInternal.data;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * {"mgdl":105,"mills":1455136282375,"device":"xDrip-BluetoothWixel","direction":"Flat","filtered":98272,"unfiltered":98272,"noise":1,"rssi":100}
 */
public class NSSgv {
    private static Logger log = LoggerFactory.getLogger(NSSgv.class);

    private JSONObject data;

    public NSSgv(JSONObject obj) {
        this.data = obj;
    }

    private String getStringOrNull(String key) {
        String ret = null;
        if (data.has(key)) {
            try {
                ret = data.getString(key);
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }
        return ret;
    }

    private Integer getIntegerOrNull(String key) {
        Integer ret = null;
        if (data.has(key)) {
            try {
                ret = data.getInt(key);
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }
        return ret;
    }

    private Long getLongOrNull(String key) {
        Long ret = null;
        if (data.has(key)) {
            try {
                ret = data.getLong(key);
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }
        return ret;
    }

    public JSONObject getData () { return data; }
    public Integer getMgdl () {
        return data.has("mgdl")
            ? getIntegerOrNull("mgdl")
            : getIntegerOrNull("sgv");
    }
    public Integer getFiltered () { return getIntegerOrNull("filtered"); }
    public Integer getUnfiltered () { return getIntegerOrNull("unfiltered"); }
    public Integer getNoise () { return getIntegerOrNull("noise"); }
    public Integer getRssi () { return getIntegerOrNull("rssi"); }
    public Long getMills () {
        if (data.has("mills"))
            return getLongOrNull("mills");

        if (data.has("date"))
            return getLongOrNull("date");

        return getLongOrNull("modified");
    }
    public String getDevice () { return getStringOrNull("device"); }
    public String getDirection () { return getStringOrNull("direction"); }
    public String getId () { return getStringOrNull("_id"); }

}

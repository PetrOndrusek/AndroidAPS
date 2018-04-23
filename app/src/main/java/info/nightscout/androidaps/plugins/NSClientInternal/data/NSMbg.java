package info.nightscout.androidaps.plugins.NSClientInternal.data;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NSMbg {
    private static Logger log = LoggerFactory.getLogger(NSMbg.class);
    public long date;
    public double mbg;
    public String json;

    public NSMbg(JSONObject json) {
        try {
            date = json.has("mills")
                ? json.getLong("mills")
                : json.getLong("modified");

            mbg = json.has("mgdl")
                ? json.getDouble("mgdl")
                : json.getDouble("mbg");

            this.json = json.toString();
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
            log.debug("Data: " + json.toString());
        }
    }
}

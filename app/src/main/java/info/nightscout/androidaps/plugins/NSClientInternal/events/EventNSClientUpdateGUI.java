package info.nightscout.androidaps.plugins.NSClientInternal.events;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.events.EventUpdateGui;

/**
 * Created by mike on 17.02.2017.
 */

public class EventNSClientUpdateGUI extends EventUpdateGui {

    public static void emit() {
        MainApp.bus().post(new EventNSClientUpdateGUI());
    }
}

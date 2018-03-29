package info.nightscout.androidaps.plugins.Overview.events;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.events.Event;

/**
 * Created by mike on 03.12.2016.
 */

public class EventDismissNotification extends Event {
    public int id;

    public EventDismissNotification(int did) {
        id = did;
    }

    public static void emit(int id) {
        MainApp.bus().post(new EventDismissNotification(id));
    }
}

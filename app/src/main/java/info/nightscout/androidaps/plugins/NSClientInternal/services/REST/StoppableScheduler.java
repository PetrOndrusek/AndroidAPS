package info.nightscout.androidaps.plugins.NSClientInternal.services.REST;

import java.util.Date;

/**
 * Created by PetrOndrusek on 29.03.2018.
 */

public class StoppableScheduler implements Runnable {

    public enum State {
        NOT_STARTED,
        RUNNING,
        FINISHED
    }

    private State state = State.NOT_STARTED;
    private long nextActivation = System.currentTimeMillis();
    private Runnable action = null;
    private long sleepForMillis = 0;
    private long cycleCheckMillis = 1000;
    private long initialSleepMillis = 0;

    public StoppableScheduler(Runnable action, long sleepForMillis, long initialSleepMillis) {
        this.action = action;
        this.sleepForMillis = sleepForMillis;
        this.initialSleepMillis = initialSleepMillis;
    }

    public synchronized boolean start() {
        if (state == State.NOT_STARTED) {
            state = State.RUNNING;
            return true;
        }
        return false;
    }

    public synchronized boolean stop() {
        if (state == State.RUNNING) {
            state = State.FINISHED;
            return true;
        }
        return false;
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    public synchronized void activateAfter(long millis) {
        nextActivation = System.currentTimeMillis() + millis;
    }

    public void run() {

        try {
            Thread.sleep(initialSleepMillis);
        } catch (InterruptedException ex) { }

        State lastState;
        long lastNextActivation;
        synchronized (this)
        {
            lastState = this.state;
        }

        while (lastState != State.FINISHED)
        {
            long now = System.currentTimeMillis();

            synchronized (this) {
                lastNextActivation = nextActivation;
            }

            if (now > lastNextActivation && action != null)
            {
                if (lastState == State.RUNNING) {
                    action.run();
                }

                synchronized (this) {
                    nextActivation = System.currentTimeMillis() + sleepForMillis;
                }
            }

            synchronized (this)
            {
                lastState = this.state;
            }

            if (lastState != State.FINISHED) {
                try {
                    Thread.sleep(cycleCheckMillis);
                } catch (InterruptedException ex) {
                }
            }

            synchronized (this)
            {
                lastState = this.state;
            }
        }
    }
}

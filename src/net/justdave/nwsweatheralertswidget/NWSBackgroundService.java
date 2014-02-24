package net.justdave.nwsweatheralertswidget;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Xml;

public class NWSBackgroundService extends Service {

    private String url;
    private NWSAlertList nwsData = new NWSAlertList();
    private String nwsRawData = "";
    private final Object NWSDataLock = new Object();

    private static final String TAG = NWSBackgroundService.class.getSimpleName();

    private Timer timer;

    private TimerTask updateTask = new TimerTask() {
        @Override
        public void run() {
            url = getSharedPreferences(getApplicationContext().getPackageName().concat("_preferences"), Context.MODE_MULTI_PROCESS)
                    .getString("feed_county", "http://alerts.weather.gov/cap/us.php?x=0");
            Log.i(TAG, "Timer task fetching ".concat(url));
            SendHttpRequestTask task = new SendHttpRequestTask();
            String[] params = new String[]{url};
            task.execute(params);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (NWSBackgroundService.class.getName().equals(intent.getAction())) {
            Log.d(TAG, "Bound by intent " + intent);
            return apiEndpoint;
        } else {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service creating");
        timer = new Timer("NWSServiceTimer");
        timer.schedule(updateTask, 100L, 300 * 1000L);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service destroying");

        timer.cancel();
        timer = null;
    }
    private List<NWSServiceListener> listeners = new ArrayList<NWSServiceListener>();

    private NWSServiceApi.Stub apiEndpoint = new NWSServiceApi.Stub() {

        @Override
        public NWSAlertList getFeedData() throws RemoteException {
            synchronized (NWSDataLock) {
                Log.i(TAG, "returning parsed data:");
                Log.i(TAG, nwsData.toString());
                return nwsData;
            }
        }

        @Override
        public String getRawData() throws RemoteException {
            synchronized (NWSDataLock) {
                return nwsRawData;
            }
        }

        @Override
        public void addListener(NWSServiceListener listener) throws RemoteException {

            synchronized (listeners) {
                Log.i(TAG, "Adding listener ".concat(listener.toString()));
                listeners.add(listener);
            }
        }

        @Override
        public void removeListener(NWSServiceListener listener) throws RemoteException {

            synchronized (listeners) {
                listeners.remove(listener);
            }
        }

    };

    private class SendHttpRequestTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            String url = params[0];
            String data = sendHttpRequest(url);

            return data;
        }

        @Override
        protected void onPostExecute(String result) {
            NWSFeedHandler myXMLHandler = new NWSFeedHandler();
            synchronized (NWSDataLock) {
                nwsRawData = result;
            }
            try {

                /**
                 * Create the Handler to handle each of the XML tags.
                 **/
                Xml.parse(result, myXMLHandler);
                synchronized (NWSDataLock) {
                    NWSAlertList nwsDataTemp = myXMLHandler.getXMLData();
                    if (nwsDataTemp.equals(nwsData)) {
                        Log.i(TAG, "Newly downloaded data hasn't changed since last time we grabbed it, ignoring.");
                    } else {
                        Log.i(TAG, "New data since last time we grabbed it, notifying listeners.");
                        nwsData = myXMLHandler.getXMLData();
                        synchronized (listeners) {
                            for (NWSServiceListener listener : listeners) {
                                try {
                                    listener.handleFeedUpdated();
                                } catch (RemoteException e) {
                                    Log.w(TAG, "Failed to notify listener " + listener, e);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }

    private String sendHttpRequest(String url) {
        StringBuffer buffer = new StringBuffer();
        try {
            HttpURLConnection con = (HttpURLConnection) (new URL(url)).openConnection();
            con.setRequestMethod("GET");
            con.setDoInput(true);
            con.setDoOutput(false);
            con.connect();

            InputStream is = con.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = r.readLine()) != null) {
                buffer.append(line);
                buffer.append('\n');
            }
            con.disconnect();
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return buffer.toString();
    }
}

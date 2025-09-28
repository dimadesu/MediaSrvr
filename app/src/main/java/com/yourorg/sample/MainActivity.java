package com.yourorg.sample;

import android.os.AsyncTask;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import java.net.*;
import java.io.*;
import java.util.LinkedList;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("node");
    }

    //We just want one instance of node running in the background.
    public static boolean _startedNodeAlready=false;

    // If we need to request POST_NOTIFICATIONS, store nodeDir here until the user responds.
    private String pendingNodeDir = null;
    private static final int REQ_POST_NOTIFICATIONS = 1001;
    // When true, we should request POST_NOTIFICATIONS when the activity is resumed
    private boolean pendingRequestNotification = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if( !_startedNodeAlready ) {
            _startedNodeAlready=true;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    //The path where we expect the node project to be at runtime.
                    String nodeDir=getApplicationContext().getFilesDir().getAbsolutePath()+"/nodejs-project";
                    if (wasAPKUpdated()) {
                        //Recursively delete any existing nodejs-project.
                        File nodeDirReference=new File(nodeDir);
                        if (nodeDirReference.exists()) {
                            deleteFolderRecursively(new File(nodeDir));
                        }
                        //Copy the node project from assets into the application's data path.
                        copyAssetFolder(getApplicationContext().getAssets(), "nodejs-project", nodeDir);

                        saveLastUpdateTime();
                    }
                    // Permission requests and service starts must happen on the UI thread.
                    final String _nodeDirForUi = nodeDir;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Before starting the foreground service, ensure we have notification permission (Android 13+/API 33+)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                    // permission already granted -> start service then node
                                    startServiceAndNode(_nodeDirForUi);
                                } else {
                                    // mark that we need to request permission when the activity is resumed
                                    pendingNodeDir = _nodeDirForUi;
                                    pendingRequestNotification = true;
                                }
                            } else {
                                // Older Android: no runtime notification permission required
                                startServiceAndNode(_nodeDirForUi);
                            }
                        }
                    });
                }
            }).start();
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingRequestNotification && pendingNodeDir != null) {
            pendingRequestNotification = false;
            // Request notifications permission now that the activity is resumed and in foreground
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
        }
    }

    // Helper to start the foreground service and then the node process
    private void startServiceAndNode(String nodeDir) {
        try {
            Intent svcIntent = new Intent(getApplicationContext(), NodeForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(svcIntent);
            } else {
                getApplicationContext().startService(svcIntent);
            }
        } catch (Exception e) {
            // Ignore failures starting the service; node can still be started.
            e.printStackTrace();
        }

        // Start Node on a background thread so we don't block the UI thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                startNodeWithArguments(new String[]{"node", nodeDir + "/main.js"});
            }
        }).start();

    final Button buttonVersions = findViewById(R.id.btVersions);
    final android.widget.ListView listViewLogs = findViewById(R.id.lvLogs);
    final java.util.ArrayList<String> logItems = new java.util.ArrayList<String>();
    final android.widget.ArrayAdapter<String> logAdapter = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, logItems);
    listViewLogs.setAdapter(logAdapter);

    // Automatically poll and show nms.log without requiring button clicks
    final android.os.Handler logHandler = new android.os.Handler();
    final int LOG_POLL_MS = 1000;
    final boolean[] loggingActive = {true};
    final Runnable[] logPoller = new Runnable[1];

    logPoller[0] = new Runnable() {
        @Override
        public void run() {
            // Read log file in background
            new AsyncTask<Void,Void,String>() {
                @Override
                protected String doInBackground(Void... params) {
                    try {
                        String nodeDir = getApplicationContext().getFilesDir().getAbsolutePath()+"/nodejs-project";
                        File logFile = new File(nodeDir + "/nms.log");
                        if (!logFile.exists()) {
                            return "(no nms.log found at " + logFile.getAbsolutePath() + ")";
                        }
                        // Keep only the last N lines to avoid loading very large files into the UI
                        final int MAX_LINES = 10;
                        LinkedList<String> tail = new LinkedList<String>();
                        BufferedReader br = new BufferedReader(new FileReader(logFile));
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line == null) continue;
                            String trimmed = line.trim();
                            if (trimmed.length() == 0) continue; // skip empty lines
                            // Collapse internal whitespace/newlines so each log entry is a single visual line
                            String singleLine = trimmed.replaceAll("\\s+", " ");
                            tail.add(singleLine);
                            if (tail.size() > MAX_LINES) tail.removeFirst();
                        }
                        br.close();
                        StringBuilder sb = new StringBuilder();
                        for (String l : tail) {
                            sb.append(l).append('\n');
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return e.toString();
                    }
                }
                @Override
                protected void onPostExecute(String result) {
                    // result contains up to the last N lines separated by '\n'
                    logItems.clear();
                    if (result != null) {
                        String[] lines = result.split("\\n");
                        for (String l : lines) {
                            if (l != null && l.length() > 0) logItems.add(l);
                        }
                    }
                    logAdapter.notifyDataSetChanged();
                    // Scroll to bottom to show the most recent entries
                    if (!logItems.isEmpty()) {
                        listViewLogs.post(new Runnable() { public void run() { listViewLogs.setSelection(logItems.size() - 1); } });
                    }
                }
            }.execute();

            if (loggingActive[0]) {
                logHandler.postDelayed(logPoller[0], LOG_POLL_MS);
            }
        }
    };

    // start polling immediately
    logHandler.post(logPoller[0]);

    // Keep button available for manual refresh as well
    buttonVersions.setOnClickListener(new View.OnClickListener() {
        public void onClick(View v) {
            // trigger an immediate poll
            logHandler.post(logPoller[0]);
        }
    });

    // Stop polling when activity is destroyed
    this.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(android.app.Activity activity, Bundle savedInstanceState) {}
        @Override public void onActivityStarted(android.app.Activity activity) {}
        @Override public void onActivityResumed(android.app.Activity activity) {}
        @Override public void onActivityPaused(android.app.Activity activity) {}
        @Override public void onActivityStopped(android.app.Activity activity) {}
        @Override
        public void onActivitySaveInstanceState(android.app.Activity activity, Bundle outState) {}
        @Override
        public void onActivityDestroyed(android.app.Activity activity) {
            if (activity == MainActivity.this) {
                loggingActive[0] = false;
                logHandler.removeCallbacks(logPoller[0]);
                getApplication().unregisterActivityLifecycleCallbacks(this);
                try {
                    Intent svcIntent = new Intent(getApplicationContext(), NodeForegroundService.class);
                    getApplicationContext().stopService(svcIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    });

    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native Integer startNodeWithArguments(String[] arguments);

    private boolean wasAPKUpdated() {
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("NODEJS_MOBILE_PREFS", Context.MODE_PRIVATE);
        long previousLastUpdateTime = prefs.getLong("NODEJS_MOBILE_APK_LastUpdateTime", 0);
        long lastUpdateTime = 1;
        try {
            PackageInfo packageInfo = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0);
            lastUpdateTime = packageInfo.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return (lastUpdateTime != previousLastUpdateTime);
    }

    private void saveLastUpdateTime() {
        long lastUpdateTime = 1;
        try {
            PackageInfo packageInfo = getApplicationContext().getPackageManager().getPackageInfo(getApplicationContext().getPackageName(), 0);
            lastUpdateTime = packageInfo.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        SharedPreferences prefs = getApplicationContext().getSharedPreferences("NODEJS_MOBILE_PREFS", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong("NODEJS_MOBILE_APK_LastUpdateTime", lastUpdateTime);
        editor.commit();
    }

    private static boolean deleteFolderRecursively(File file) {
        try {
            boolean res=true;
            for (File childFile : file.listFiles()) {
                if (childFile.isDirectory()) {
                    res &= deleteFolderRecursively(childFile);
                } else {
                    res &= childFile.delete();
                }
            }
            res &= file.delete();
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean copyAssetFolder(AssetManager assetManager, String fromAssetPath, String toPath) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            boolean res = true;

            if (files.length==0) {
                //If it's a file, it won't have any assets "inside" it.
                res &= copyAsset(assetManager,
                        fromAssetPath,
                        toPath);
            } else {
                new File(toPath).mkdirs();
                for (String file : files)
                res &= copyAssetFolder(assetManager,
                        fromAssetPath + "/" + file,
                        toPath + "/" + file);
            }
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean copyAsset(AssetManager assetManager, String fromAssetPath, String toPath) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = assetManager.open(fromAssetPath);
            new File(toPath).createNewFile();
            out = new FileOutputStream(toPath);
            copyFile(in, out);
            in.close();
            in = null;
            out.flush();
            out.close();
            out = null;
            return true;
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            boolean granted = false;
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }

            final String nodeDir = pendingNodeDir;
            pendingNodeDir = null;

            if (nodeDir == null) {
                // nothing pending
                return;
            }

            if (granted) {
                // start foreground service and node
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startServiceAndNode(nodeDir);
                    }
                });
            } else {
                // User denied notifications — show a toast and start node without foreground service
                Toast.makeText(this, "Notification permission denied. Node will run without foreground notification.", Toast.LENGTH_LONG).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        startNodeWithArguments(new String[]{"node", nodeDir + "/main.js"});
                    }
                }).start();
            }
        }
    }

}

package com.iot.simonstone.beaconscanner;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.TaskStackBuilder;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "MainActivity";

    private static final int PERMISSION_REQUEST_COARSE_LOCATION = 1;
    private static final int PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE = 1;
    private static boolean BEACON_ENABLED = false;
    private static boolean FILE_WRITE_ENABLED = true;
    private static String ACTIVE_FILE_NAME = "log.txt";

    private ProgressDialog mProgressDialog;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;
    public ArrayList<BeaconResult> mBeaconScanResults = new ArrayList<BeaconResult>();
    private ArrayList<BeaconResult> mScanResults;

    TextView isbeacon, status;
    ImageView beacon;
    Button startBtn,adjustBtn,closeBtn;

    private BluetoothManager btManager;
    private BluetoothAdapter btAdapter;
    private Handler scanHandler = new Handler();
    private int scan_interval_ms = 10000;

    private int rssGate = -40;
    private String beaconInfo = "293沒有錢";

    private void checkPermissions()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            // Android M Permission check
            if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs location access");
                builder.setMessage("Please grant location access so this app can scan for WiFi.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener()
                {
                    @TargetApi(Build.VERSION_CODES.M)
                    public void onDismiss(DialogInterface dialog)
                    {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST_COARSE_LOCATION);
                    }
                });
                builder.show();
            }
            if (this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            {
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("This app needs external storage access");
                builder.setMessage("Please grant access so this app can write results to file.");
                builder.setPositiveButton(android.R.string.ok, null);
                builder.setOnDismissListener(new DialogInterface.OnDismissListener()
                {
                    @TargetApi(Build.VERSION_CODES.M)
                    public void onDismiss(DialogInterface dialog)
                    {
                        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_WRITE_EXTERNAL_STORAGE);
                    }
                });
                builder.show();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions(); // Check Android M Permissions Dynamically
        setContentView(R.layout.main);

        isbeacon = (TextView) findViewById(R.id.isbeacon);
        beacon = (ImageView) findViewById(R.id.imageView6);
        status = (TextView) findViewById(R.id.status);
        startBtn = (Button) findViewById(R.id.startBtn);
        adjustBtn = (Button) findViewById(R.id.adjustBtn);
        closeBtn = (Button) findViewById(R.id.stopBtn);

        beacon.setVisibility(View.INVISIBLE);
        isbeacon.setVisibility(View.INVISIBLE);

        // init BLE
        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();

        // 檢查手機硬體是否為BLE裝置
        if (!getPackageManager().hasSystemFeature
                (PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "硬體不支援BLE", Toast.LENGTH_SHORT).show();
            finish();
        }

        buttonSetup();

    }

    private void buttonSetup(){
        status.setText("點擊開始偵測。");
        startBtn.setOnClickListener(new View.OnClickListener(){
            public  void onClick(View v){
                if (!btAdapter.isEnabled()) {
                    /*//則打開藍芽
                    Intent enabler=new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivity(enabler);*/

                    //則打開藍芽( 強迫 )
                    btAdapter.enable();
                }
                BEACON_ENABLED =true;

                WebAppInterface collectFingerprint = new WebAppInterface();
                collectFingerprint.initializeFingerprinting("1",10,10);

            }
        });
        adjustBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                adjustPara();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener(){
            public  void onClick(View v){
                BEACON_ENABLED =false;
                btAdapter.stopLeScan(leScanCallback);
                scanHandler.removeCallbacksAndMessages(scanHandler);
                status.setText("停止偵測");
            }
        });

    }

    // Class responsible for interfacing with the HTML files
    // Includes methods:
    //      - initializeWifiScan
    //      - initializeMagneticScan
    //      - intializeFingerprinting
    private class WebAppInterface{
        /**
         * Start the WIFI scan
         */
        private Runnable scanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!BEACON_ENABLED) {
                    if (btAdapter != null) {
                        btAdapter.stopLeScan(leScanCallback);
                    }
                } else {
                    if (btAdapter != null) {
                        btAdapter.startLeScan(leScanCallback);
                    }
                }
                scanHandler.postDelayed(this, scan_interval_ms);
                Log.i("Runnable","I'm still alive");

            }
        };

        private void initializeFingerprinting(final String place_id, final float startX, final float startY)
        {
            if (!BEACON_ENABLED)
            {
                AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this).create();
                alertDialog.setTitle("Action Required");
                alertDialog.setMessage("Please enable WiFi or Magnetic\n" +
                        "fingerprint collection");
                alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                        new DialogInterface.OnClickListener()
                        {
                            public void onClick(DialogInterface dialog, int which)
                            {
                                dialog.dismiss();
                            }
                        });
                alertDialog.show();
                return;
            }
            if (!FILE_WRITE_ENABLED)
            {
                AlertDialog alertDialog = new AlertDialog.Builder(MainActivity.this).create();
                alertDialog.setTitle("Action Required");
                alertDialog.setMessage("Please enable Send to Server\n" +
                        "or Write to File to continue");
                alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                        new DialogInterface.OnClickListener()
                        {
                            public void onClick(DialogInterface dialog, int which)
                            {
                                dialog.dismiss();
                            }
                        });
                alertDialog.show();
                return;
            }

            if (btAdapter.isEnabled())
                initializeBeaconScan(place_id, startX, startY);

        }

        private String stringify(double[] arr, float startX, float startY)
        {
            StringBuilder sb = new StringBuilder();
            for(double d:arr)
            {
                sb.append(d);
                sb.append(",");
            }
            sb.append(startX);
            sb.append(",");
            sb.append(startY);
            return  sb.toString();
        }

        private void initializeBeaconScan(final String place_id, final float startX, final float startY)
        {
            int scanNumber = 1;
//            mProgressDialog = ProgressDialog.show(MainActivity.this, "Beacon Scan",
//                    "Scan " + String.valueOf(scanNumber) + " at: \n" + String.valueOf(startX) + ", " + String.valueOf(startY), true);

            mIntentFilter = new IntentFilter();

            //TODO set a action whcih broadcast can catch during beacon scan
            Log.i(" ACTION", btAdapter.ACTION_STATE_CHANGED);
            mIntentFilter.addAction(btAdapter.ACTION_STATE_CHANGED);
            mIntentFilter.addAction(BluetoothDevice.ACTION_FOUND);

            mBroadcastReceiver = new BroadcastReceiver()
            {

                @Override
                public void onReceive(Context context, Intent intent)
                {
                    Log.d("Beacon", "Receiving Beacon Scan results");
                    Log.d("ACTION", intent.getAction());

                    mScanResults = mBeaconScanResults;

                    ArrayList<JSONObject> allScanResults = new ArrayList<>();
                    double[] rssiVector = new double[3];
                    for (BeaconResult result : mScanResults)
                    {
                        //Adding data to the JSON object finger_print at first
//                            if (result.BSSID.equals(AccessPointMacs.AP1_MAC)
//                                    || result.BSSID.equals(AccessPointMacs.AP2_MAC)
//                                    || result.BSSID.equals(AccessPointMacs.AP3_MAC)
//                                    )
//                            {
//                                rssiVector[AccessPointMacs.keys.get(result.BSSID)] = result.level;
                        if (!result.uuid.isEmpty()
                                )
                        {
                            rssiVector[0] = result.txpower;
                            // JSON Style
                                /*
                                JSONObject finger_print = new JSONObject();
                                try
                                {
                                    finger_print.put("place_id", place_id);
                                    finger_print.put("xcoord", startX);
                                    finger_print.put("ycoord", startY);
                                    finger_print.put("BSSID", result.BSSID);
                                    finger_print.put("SSID", result.SSID);
                                    finger_print.put("RSSI", result.level);
                                    finger_print.put("SD", "");
                                    finger_print.put("mac", result.BSSID);
                                    allScanResults.add(finger_print);
                                } catch (JSONException e)
                                {
                                    e.printStackTrace();
                                }
                                */

                            // 取得NotificationManager物件
//                           NotificationManager manager = (NotificationManager)
//                            getSystemService(Context.NOTIFICATION_SERVICE);

                            if (result.major == 1) {
                                beacon.setVisibility(View.VISIBLE);
                                isbeacon.setVisibility(View.VISIBLE);

                                switch (result.minor) {
                                    case 600:
                                        status.setText("600是我放金幣的地方");
                                        break;
                                    case 283:
                                        status.setText( beaconInfo + " ,目前RSS: " + result.txpower);
                                        break;
                                    default:
                                        status.setText("");
                                        beacon.setVisibility(View.VISIBLE);
                                        isbeacon.setVisibility(View.VISIBLE);
                                        break;
                                }
                            }

//                          if( result.txpower >= rssGate ){
//                             notifyBeacon(manager, minor);
//                          }

                            Log.i("RESULT", result.minor + " " + result.txpower + " (" + startX + "," + startY + ")");
                            if (FILE_WRITE_ENABLED)
                            {
                                saveFile(context, stringify(rssiVector, startX, startY), "wifi");
                            }

                        }
                    }

//                    mProgressDialog.dismiss();
                    btAdapter.startLeScan(leScanCallback);
                    unregisterReceiver(mBroadcastReceiver);
                    Log.i("Broadcast_state:", "unregisterReceiver");
                    //saveResults.run();
                }
            };

            registerReceiver(mBroadcastReceiver, mIntentFilter);
            scanRunnable.run();
            status.setText(" Start!");
        }

        private boolean saveFile(Context context, String mytext, String fpType)
        {
            Log.i("FILE_WRITE", "SAVING");
            try {
                String MEDIA_MOUNTED = "mounted";
                String diskState = Environment.getExternalStorageState();
                if(diskState.equals(MEDIA_MOUNTED))
                {
                    File dir = new File(Environment.getExternalStorageDirectory(), "MazeIn Fingerprints");
                    if(!dir.exists())
                    {
                        dir.mkdirs();
                    }

                    File outFile = new File(dir, ACTIVE_FILE_NAME + fpType + ".txt");

                    //FileOutputStream fos = new FileOutputStream(outFile);
                    //PrintWriter pw =  new PrintWriter(fos);

                    BufferedWriter out = new BufferedWriter(new FileWriter(outFile, true));
                    out.write(mytext + "\n");
                    out.flush();
                    out.close();

                    return true;

                }

            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
            return false;
        }


    }

    private void adjustPara(){
        beaconInfo = "我想吃熱狗<3";
        rssGate = -50;
    }


    //
    public BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {

        @Override
        public void onLeScan(final BluetoothDevice device, final int txpower, final byte[] scanRecord) {
            int startByte = 2;
            boolean patternFound = false;

            // 尋找ibeacon
            // 先依序尋找第2到第8陣列的元素
            while (startByte <= 5) {
                if (((int) scanRecord[startByte + 2] & 0xff) == 0x02 && //Identifies an iBeacon
                        ((int) scanRecord[startByte + 3] & 0xff) == 0x15) { //Identifies correct data length
                    patternFound = true;
                    break;
                }
                startByte++;
            }
            if (patternFound) {
                //Convert to hex String
                byte[] uuidBytes = new byte[16];
                System.arraycopy(scanRecord, startByte + 4, uuidBytes, 0, 16);
                String hexString = bytesToHex(uuidBytes);
                //UUID detection
                String uuid = hexString.substring(0, 8) + "-" +
                        hexString.substring(8, 12) + "-" +
                        hexString.substring(12, 16) + "-" +
                        hexString.substring(16, 20) + "-" +
                        hexString.substring(20, 32);
                // major
                final int major = (scanRecord[startByte + 20] & 0xff) * 0x100 + (scanRecord[startByte + 21] & 0xff);
                // minor
                final int minor = (scanRecord[startByte + 22] & 0xff) * 0x100 + (scanRecord[startByte + 23] & 0xff);

                mBeaconScanResults.add(new BeaconResult(uuid, major, minor, txpower));

                Log.i(LOG_TAG, "UUID: " + uuid + "\\nmajor: " + major + "\\nminor" + minor+ "\\txpower" + txpower);
            }
        }
    };
    /**
     * bytesToHex method
     */
    static final char[] hexArray = "0123456789ABCDEF".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

//    private void notifyBeacon(NotificationManager m, int minor){
//        //setup notification
//        // 建立NotificationCompat.Builder物件
//        NotificationCompat.Builder builder =
//                new NotificationCompat.Builder(this);
//
//        // 準備設定通知效果用的變數
//        int defaults = 0; // 準備設定通知效果用的變數
//        defaults |= Notification.DEFAULT_VIBRATE; // 加入震動效果
//        defaults |= Notification.DEFAULT_SOUND; // 加入音效效果
//        defaults |= Notification.DEFAULT_LIGHTS; // 加入閃燈效果
//
//        // 設定通知效果
//        builder.setDefaults(defaults);
//
//        // 設定小圖示、大圖示、狀態列文字、時間、內容標題、內容訊息和內容額外資訊
//        builder.setSmallIcon(R.drawable.notify_icon)
//                .setWhen(System.currentTimeMillis())
//                .setContentTitle("Beacon Notification")
//                .setContentText("野生的光頭出現了");
//
//        //設定點記通知事件
//        // Creates an explicit intent for an Activity in your app
//        Intent resultIntent = new Intent(this, MainActivity.class);
//
//        // The stack builder object will contain an artificial back stack for the
//        // started Activity.
//        // This ensures that navigating backward from the Activity leads out of
//        // your application to the Home screen.
//        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
//        // Adds the back stack for the Intent (but not the Intent itself)
//        stackBuilder.addParentStack(MainActivity.class);
//        // Adds the Intent that starts the Activity to the top of the stack
//        stackBuilder.addNextIntent(resultIntent);
//        PendingIntent resultPendingIntent =
//                stackBuilder.getPendingIntent(
//                        0,
//                        PendingIntent.FLAG_UPDATE_CURRENT
//                );
//        builder.setContentIntent(resultPendingIntent);
//
//        // 使用BASIC_ID為編號發出通知
//        m.notify(minor, builder.build());
//    }
    class BeaconResult{
        private String uuid;
        private int major;
        private int minor;
        private int txpower;

        public BeaconResult(String data1, int data2, int data3, int data4){
            uuid = data1;
            major = data2;
            minor = data3;
            txpower = data4;
        }
    }
}

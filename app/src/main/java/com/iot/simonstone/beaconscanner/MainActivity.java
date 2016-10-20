package com.iot.simonstone.beaconscanner;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "MainActivity";

    TextView isbeacon, status;
    ImageView beacon;
    Button startBtn,adjustBtn,closeBtn;

    private BluetoothManager btManager;
    private BluetoothAdapter btAdapter;
    private Handler scanHandler = new Handler();
    private int scan_interval_ms = 10000;
    private boolean isScanning = false;

    private int rssGate = -40;
    private String beaconInfo = "293沒有錢";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        isbeacon = (TextView) findViewById(R.id.isbeacon);
        beacon = (ImageView) findViewById(R.id.imageView6);
        status = (TextView) findViewById(R.id.status);
        startBtn = (Button) findViewById(R.id.startBtn);
        adjustBtn = (Button) findViewById(R.id.adjustBtn);
        closeBtn = (Button) findViewById(R.id.stopBtn);


        // init BLE
        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();

        beacon.setVisibility(View.INVISIBLE);
        isbeacon.setVisibility(View.INVISIBLE);

        // 檢查手機硬體是否為BLE裝置
        if (!getPackageManager().hasSystemFeature
                (PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "硬體不支援BLE", Toast.LENGTH_SHORT).show();
            finish();
        }


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

                scanHandler.post(scanRunnable);
            }
        });
        adjustBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                adjustPara();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener(){
            public  void onClick(View v){
                btAdapter.isEnabled();
                btAdapter.stopLeScan(leScanCallback);
                isScanning = false;
                scanHandler.removeCallbacksAndMessages(scanHandler);
                status.setText("停止偵測");
            }
        });

    }

    private Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning) {
                if (btAdapter != null) {
                    btAdapter.stopLeScan(leScanCallback);
                }
            } else {
                if (btAdapter != null) {
                    btAdapter.startLeScan(leScanCallback);
                }
            }
            isScanning = !isScanning;
            scanHandler.postDelayed(this, scan_interval_ms);

        }
    };

    public BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
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

                // 取得NotificationManager物件
                NotificationManager manager = (NotificationManager)
                        getSystemService(Context.NOTIFICATION_SERVICE);

                if (major == 1) {
                    beacon.setVisibility(View.VISIBLE);
                    isbeacon.setVisibility(View.VISIBLE);

                    switch (minor) {
                        case 600:
                            status.setText("600是我放金幣的地方");
                            break;
                        case 283:
                            status.setText( beaconInfo + " ,目前RSS: " + rssi);
                            break;
                        default:
                            status.setText("");
                            beacon.setVisibility(View.VISIBLE);
                            isbeacon.setVisibility(View.VISIBLE);
                            break;
                    }
                }

                if(rssi >= rssGate ){
                   notifyBeacon(manager, minor);
                }

                Log.i(LOG_TAG, "UUID: " + uuid + "\\nmajor: " + major + "\\nminor" + minor);
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

    private void notifyBeacon(NotificationManager m, int minor){
        //setup notification
        // 建立NotificationCompat.Builder物件
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this);

        // 準備設定通知效果用的變數
        int defaults = 0; // 準備設定通知效果用的變數
        defaults |= Notification.DEFAULT_VIBRATE; // 加入震動效果
        defaults |= Notification.DEFAULT_SOUND; // 加入音效效果
        defaults |= Notification.DEFAULT_LIGHTS; // 加入閃燈效果

        // 設定通知效果
        builder.setDefaults(defaults);

        // 設定小圖示、大圖示、狀態列文字、時間、內容標題、內容訊息和內容額外資訊
        builder.setSmallIcon(R.drawable.notify_icon)
                .setWhen(System.currentTimeMillis())
                .setContentTitle("Beacon Notification")
                .setContentText("野生的光頭出現了");

        //設定點記通知事件
        // Creates an explicit intent for an Activity in your app
        Intent resultIntent = new Intent(this, MainActivity.class);

        // The stack builder object will contain an artificial back stack for the
        // started Activity.
        // This ensures that navigating backward from the Activity leads out of
        // your application to the Home screen.
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        builder.setContentIntent(resultPendingIntent);

        // 使用BASIC_ID為編號發出通知
        m.notify(minor, builder.build());
    }

    private void adjustPara(){
        beaconInfo = "我想吃熱狗<3";
        rssGate = -50;
    }
}

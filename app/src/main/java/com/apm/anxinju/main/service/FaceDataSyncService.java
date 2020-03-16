package com.apm.anxinju.main.service;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.apm.anxinju.main.activity.FaceAuthActivity;
import com.apm.anxinju.main.activity.PreviewActivity;
import com.apm.anxinju.main.listener.SdkInitListener;
import com.apm.anxinju.main.manager.FaceSDKManager;
import com.apm.anxinju.main.utils.FileUtils;
import com.apm.anxinju_baidufacesdk30.R;
import com.apm.data.api.Api;
import com.apm.data.model.BaseResponse;
import com.apm.data.model.FaceModel;
import com.apm.data.model.RetrofitManager;
import com.baidu.idl.main.facesdk.FaceAuth;

import org.reactivestreams.Subscription;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.FlowableSubscriber;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.Job;


public class FaceDataSyncService extends Service {

    private static final String TAG = "FaceDataSyncService";
    public static final String CHANNEL_ID = "FACE_DATA_SYNC-1";
    private String deviceId;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "service created");
        initLicense();
        deviceId = new FaceAuth().getDeviceId(this);
        //deviceId = "19BC95DC053A2A4D130FC17C9B4E6EED43";
        startSyncLoop();
    }


    private void initLicense() {
        if (FaceSDKManager.initStatus != FaceSDKManager.SDK_MODEL_LOAD_SUCCESS) {
            FaceSDKManager.getInstance().init(this, new SdkInitListener() {
                @Override
                public void initStart() {

                }

                public void initLicenseSuccess() {
                }

                public void initLicenseFail(int errorCode, String msg) {
                    // 如果授权失败，跳转授权页面
                    startActivity(new Intent(FaceDataSyncService.this, FaceAuthActivity.class));
                }

                public void initModelSuccess() {
                }

                public void initModelFail(int errorCode, String msg) {

                }
            });
        }
    }


    Job job;
    /**
     * 30s间隔,更新数据
     */
    @SuppressLint("CheckResult")
    private void startSyncLoop() {
        if(job==null||job.isCompleted()){
            job = SyncHelper.INSTANCE.startSync(this, deviceId, new Function0<Unit>() {
                @Override
                public Unit invoke() {
                    stopForeground(true);
                    return null;
                }
            });
        }else {
            Log.e(TAG,"同步进行中，无需更新JOB");
        }
    }




    public static String ACTION_NOTIFY_REGISTER = "ACTION_NOTIFY_REGISTER";
    public static String KEY_NOTIFY_REGISTER_MODEL = "KEY_NOTIFY_REGISTER_MODEL";
    public static String KEY_NOTIFY_REGISTER_SUCCESS = "KEY_NOTIFY_REGISTER_SUCCESS";



    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "service onStartCommand");
        startSyncLoop();
        // 在API11之后构建Notification的方式
        Notification.Builder builder = new Notification.Builder(this.getApplicationContext()); //获取一个Notification构造器
        Intent nfIntent = new Intent(this, PreviewActivity.class);

        builder.setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, 0))
                .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_image_attrs)) // 设置下拉列表中的图标(大图标)
                .setContentTitle("数据同步服务") // 设置下拉列表里的标题
                .setSmallIcon(R.mipmap.ic_check) // 设置状态栏内的小图标
                .setContentText("人脸库数据同步服务") // 设置上下文内容
                .setWhen(System.currentTimeMillis()); // 设置该通知发生的时间

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "人脸库数据同步服务", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);
            NotificationCompat.Builder builderCompat
                    = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setLargeIcon(BitmapFactory.decodeResource(this.getResources(), R.mipmap.ic_image_attrs)) // 设置下拉列表中的图标(大图标)
                    .setContentTitle("数据同步服务") // 设置下拉列表里的标题
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setSmallIcon(R.mipmap.ic_check) // 设置状态栏内的小图标
                    .setContentText("人脸库数据同步服务") // 设置上下文内容
                    .setWhen(System.currentTimeMillis()); // 设置该通知发生的时间
            Notification notification = builderCompat.build(); // 获取构建好的Notification
            notification.defaults = Notification.DEFAULT_SOUND; //设置为默认的声音
            startForeground(110, notification);
            return super.onStartCommand(intent, flags, startId);
        }

        Notification notification = builder.build(); // 获取构建好的Notification
        notification.defaults = Notification.DEFAULT_SOUND; //设置为默认的声音
        startForeground(110, notification);
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("on bind not implemented");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "service onDestroy");
        SyncHelper.INSTANCE.stopSync();
        stopForeground(true);// 停止前台服务--参数：表示是否移除之前的通知
    }


}

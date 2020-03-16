package com.apm.dahuaipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Picture
import android.os.Environment
import android.util.Log
import com.company.NetSDK.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


/**
 *  author : ciih
 *  date : 2019-10-21 13:19
 *  description :
 */
object INetSDKHelper : CoroutineScope {
    private val singleThreadDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main

    private var initialized = false
    private var runDownloadQueue = true
    private var mLoginHandle = 0L
    private var mDownloadHandle = 0L
    private var mDeviceInfo: NET_DEVICEINFO_Ex? = null
    const val TAG = "INetSDKHelper"

    private val downloadQueue = ArrayBlockingQueue<DownloadInfo>(20)
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    //Channel个数
    val mChannelCount: Int
        get() {
            return mDeviceInfo?.nChanNum ?: 0
        }

    @Synchronized
    fun initSDK() {
        if (initialized) {
            return
        }
        initialized = true

        INetSDK.LoadLibrarys()
        val init = INetSDK.Init { l, s, i ->
            Log.e(TAG, "Disconnect:l = [${l}], s = [${s}], i = [${i}]")
        }
        if (!init) {
            Log.e(TAG, "INetSDK initSDK Failed")
            return
        }

        INetSDK.SetAutoReconnect { l, s, i ->
            Log.e(TAG, "Reconnect:l = [${l}], s = [${s}], i = [${i}]")
        }

        INetSDK.SetDVRMessCallBack { i, l, any, s, i2 ->
            Log.e(TAG, "i = [${i}], l = [${l}], any = [${any}], s = [${s}], i2 = [${i2}]")
            false
        }

        val stNetParam = NET_PARAM()
        stNetParam.nConnectTime = 10_000
        stNetParam.nWaittime = 10_000 // Time out of common Interface.
        stNetParam.nSearchRecordTime = 30_000 // Time out of Playback interface.
        INetSDK.SetNetworkParam(stNetParam)
    }

    data class DownloadInfo(
        val chn: Int = 0, val stream: Int = 0, val startDate: Date, val endDate: Date
    )


    fun loginAsync(
        address: String,
        port: Int = 37777,
        username: String = "admin",
        password: String = "admin123",
        mLoginType: Int = EM_LOGIN_SPAC_CAP_TYPE.EM_LOGIN_SPEC_CAP_MOBILE
    ) = async {
        Log.w(TAG, "正在登陆 $address")
        val err = 0
        mDeviceInfo = NET_DEVICEINFO_Ex()
        mLoginHandle = INetSDK.LoginEx2(
            address,
            port,
            username,
            password,
            mLoginType,
            null,
            mDeviceInfo,
            err
        )
        Log.w(TAG, "登陆句柄：$mLoginHandle")
        if (0L == mLoginHandle) {
            return@async false
        }
        return@async true
    }

    fun logout(): Boolean {
        runDownloadQueue = false
        mDeviceInfo = null
        if (0L == mLoginHandle) {
            return false
        }
        val retLogout = INetSDK.Logout(mLoginHandle)
        if (retLogout) {
            mLoginHandle = 0
        }
        return retLogout
    }

    private fun zoomBitmap(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val matrix = Matrix()

        val scaleWidth = w.toFloat() / width
        val scaleHeight = h.toFloat() / height
        matrix.postScale(scaleWidth, scaleHeight)

        return Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    /**
     * 抓图
     */
    suspend fun snapPicture(
        chn: Int = 0,
        mode: Int = 0,
        interval: Int = 0,
        onRawPicture: (Bitmap) -> Unit
    ) =
        suspendCoroutine<Bitmap> {
            // 发送抓图命令给前端设备，抓图的信息
            try {
                if (mLoginHandle == 0L) {
                    it.resumeWithException(IllegalStateException("设备未登录"))
                    return@suspendCoroutine
                }
                val stuSnapParams = SNAP_PARAMS()
                stuSnapParams.Channel = chn            // 抓图通道
                stuSnapParams.mode = mode                // 抓图模式
                stuSnapParams.Quality = 3                // 画质
                stuSnapParams.InterSnap = interval    // 定时抓图时间间隔
                stuSnapParams.CmdSerial = 0            // 请求序列号，有效值范围 0~65535，超过范围会被截断为
                INetSDK.SetSnapRevCallBack { l: Long, data: ByteArray, i: Int, i1: Int, i2: Int ->
                    Log.w(
                        TAG,
                        "l = [${l}], data = [${data}], i = [${i}], i1 = [${i1}], i2 = [${i2}]"
                    )
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    onRawPicture(bitmap)
                    it.resume(zoomBitmap(bitmap, 640, 480))

                }
                if (!INetSDK.SnapPictureEx(mLoginHandle, stuSnapParams)) {
                    it.resumeWithException(IllegalStateException("抓取图片失败"))
                }
            } catch (e: Exception) {
                it.resumeWithException(e)
            }
        }


    private fun stopDownload() {
        val stop = INetSDK.StopDownload(mDownloadHandle)
        Log.w(TAG, "Stop Download: $stop")
        if (stop) {
            mDownloadHandle = 0L
        }
    }

    fun postDownloadTask(chn: Int = 0, stream: Int = 1, startDate: Date, endDate: Date) = launch {
        setTime()
        downloadQueue.put(
            DownloadInfo(
                chn, stream, startDate, endDate
            )
        )
    }

    ///Set device time
    ///时间同步
    private fun setTime(): Boolean {
        val deviceTime = NET_TIME()
        ///Get the current system time of the phone
        ///获取当前的手机系统时间
        val calendar = Calendar.getInstance()
        calendar.time = Date()
        deviceTime.dwYear = calendar.get(Calendar.YEAR).toLong()
        deviceTime.dwMonth = calendar.get(Calendar.MONTH).toLong() + 1
        deviceTime.dwDay = calendar.get(Calendar.DAY_OF_MONTH).toLong()
        deviceTime.dwHour = calendar.get(Calendar.HOUR_OF_DAY).toLong()
        deviceTime.dwMinute = calendar.get(Calendar.MINUTE).toLong()
        deviceTime.dwSecond = calendar.get(Calendar.SECOND).toLong()
        return INetSDK.SetupDeviceTime(mLoginHandle, deviceTime)
    }


    init {
        startDownloadQueue()
    }

    private fun startDownloadQueue() = launch(singleThreadDispatcher) {
        Log.w(TAG, "开始下载录像循环")
        while (runDownloadQueue) {
            try {
                delay(3000)
                val (chn, stream, startDate, endDate) = downloadQueue.take()
                downloadRecord(chn, stream, startDate, endDate)
                stopDownload()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private suspend fun downloadRecord(
        chn: Int = 0,
        stream: Int = 0,
        startDate: Date,
        endDate: Date
    ) =
        suspendCancellableCoroutine<File> {
            //设置模式
            try {
                if (mLoginHandle == 0L) {
                    it.resumeWithException(IllegalStateException("设备未登录"))
                    return@suspendCancellableCoroutine
                }
                if (mDownloadHandle != 0L) {
                    it.resumeWithException(IllegalStateException("下载进行中"))
                    return@suspendCancellableCoroutine
                }
                if (INetSDK.SetDeviceMode(
                        mLoginHandle,
                        EM_USEDEV_MODE.SDK_RECORD_STREAM_TYPE,
                        stream
                    )
                ) {
                    Log.w(TAG, "开始下载 $startDate - $endDate")
                } else {
                    it.resumeWithException(IllegalStateException("下载进行中"))
                    return@suspendCancellableCoroutine
                }


                val startDateString = format.format(startDate).replace(":", "_")
                val endDateString = format.format(endDate).replace(":", "_")
                val recFileName =
                    Environment.getExternalStorageDirectory().absolutePath + "/PlaySDK/${startDateString}_${endDateString}.dav"


                val startTime = NET_TIME().apply {
                    val calendar = Calendar.getInstance()
                    calendar.time = startDate
                    this.dwYear = calendar.get(Calendar.YEAR).toLong()
                    this.dwMonth = calendar.get(Calendar.MONTH).toLong() + 1
                    this.dwDay = calendar.get(Calendar.DAY_OF_MONTH).toLong()
                    this.dwHour = calendar.get(Calendar.HOUR_OF_DAY).toLong()
                    this.dwMinute = calendar.get(Calendar.MINUTE).toLong()
                    this.dwSecond = calendar.get(Calendar.SECOND).toLong()
                }
                val endTime = NET_TIME().apply {
                    val calendar = Calendar.getInstance()
                    calendar.time = endDate
                    this.dwYear = calendar.get(Calendar.YEAR).toLong()
                    this.dwMonth = calendar.get(Calendar.MONTH).toLong() + 1
                    this.dwDay = calendar.get(Calendar.DAY_OF_MONTH).toLong()
                    this.dwHour = calendar.get(Calendar.HOUR_OF_DAY).toLong()
                    this.dwMinute = calendar.get(Calendar.MINUTE).toLong()
                    this.dwSecond = calendar.get(Calendar.SECOND).toLong()
                }
                mDownloadHandle = INetSDK.DownloadByTimeEx(
                    mLoginHandle,
                    chn,
                    EM_QUERY_RECORD_TYPE.EM_RECORD_TYPE_ALL,
                    startTime,
                    endTime,
                    recFileName,
                    { playHandle, totalSize, downloadSize, index, fileInfo: NET_RECORDFILE_INFO ->

                        Log.w(
                            TAG,
                            "下载大小/文件总大小 : $downloadSize / $totalSize , index:$index ,play handle:$playHandle"
                        )
                        if (downloadSize == -1) {
                            it.resume(File(recFileName))
                            Log.w(TAG, "下载完成 $startDate - $endDate")
                        }
                    },
                    null,
                    null
                )

                if (mDownloadHandle == 0L) {
                    it.resumeWithException(IllegalStateException("下载失败,${INetSDK.GetLastError()}"))
                }
            } catch (e: Exception) {
                it.resumeWithException(e)
            }
        }

    fun getChannel(): Unit {

    }
}
package com.apm.anxinju.main.activity

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.*
import android.os.*
import android.renderscript.*
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import com.apm.anxinju.main.RegisterReceiver
import com.apm.anxinju.main.api.FaceApi
import com.apm.anxinju.main.callback.FaceDetectCallBack
import com.apm.anxinju.main.camera.AutoTexturePreviewView
import com.apm.anxinju.main.camera.CameraPreviewManager
import com.apm.anxinju.main.manager.FaceSDKManager
import com.apm.anxinju.main.model.LivenessModel
import com.apm.anxinju.main.model.SingleBaseConfig
import com.apm.anxinju.main.service.FaceDataSyncService
import com.apm.anxinju.main.service.KeepAliveService
import com.apm.anxinju.main.utils.*
import com.apm.anxinju_baidufacesdk30.R
import com.apm.dahuaipc.INetSDKHelper
import com.apm.data.api.Api
import com.apm.data.api.ApiKt
import com.apm.data.db.FaceDBManager
import com.apm.data.db.entity.PassageLog
import com.apm.data.model.BaseResponse
import com.apm.data.model.RetrofitManager
import com.apm.data.persistence.PropertiesUtils
import com.apm.nfc.NFCDetectorManager
import com.apm.rs485reader.service.DataSyncService
import com.apm.rs485reader.service.IPictureCaptureInterface
import com.baidu.idl.main.facesdk.FaceAuth
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon
import com.bumptech.glide.Glide
import com.common.pos.api.util.PosUtil
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_preview.*
import kotlinx.coroutines.*
import me.drakeet.support.toast.ToastCompat
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Runnable
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.measureTimeMillis

class PreviewActivity : BaseActivity(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main


    companion object {
        // 图片越大，性能消耗越大，也可以选择640*480， 1280*720
        private const val PREFER_WIDTH = 640
        private const val PREFER_HEIGHT = 480
        private const val TAG = "PreviewActivity"
    }

    var `in`: Allocation? = null
    var out: Allocation? = null
    var yuvType: Type.Builder? = null
    var rgbaType: Type.Builder? = null
    private val rs: RenderScript by lazy {
        RenderScript.create(this@PreviewActivity)
    }
    private val yuvToRgbIntrinsic: ScriptIntrinsicYuvToRGB by lazy {
        ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    }
    private var mBinder: IPictureCaptureInterface? = null
    private val mFaceTextureView: TextureView by lazy {
        findViewById<TextureView>(R.id.textureView)
    }
    private val mAutoTexturePreviewView: AutoTexturePreviewView by lazy {
        findViewById<AutoTexturePreviewView>(R.id.autoTexturePreview)
    }
    private val mLiveType by lazy {
        SingleBaseConfig.getBaseConfig().type
    }
    private val rectF = RectF()
    private val paint = Paint().apply {
        this.color = Color.GREEN
        this.style = Paint.Style.STROKE
    }
    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var detectQrCode = false
    private val handler = Handler()
    private val imageHandler = Handler()
    private val qrHandler = Handler()
    private val faceAuth by lazy {
        FaceAuth().apply {
            setActiveLog(BDFaceSDKCommon.BDFaceLogInfo.BDFACE_LOG_ERROR_MESSAGE)
        }
    }
    private val deviceId by lazy {
        faceAuth.getDeviceId(this)
        //"19BC95DC053A2A4D130FC17C9B4E6EED43"
    }
    private var lastOpenTime = 0L
    private var disposable: Disposable? = null
    //上一个追踪到的faceId,来自LivenessModel
    private var lastFaceId = -1
    var lastToast: Toast? = null
    //上次记录时间
    private var lastRecordTimeMillis: Long = 0
    private val threadContext: CoroutineContext =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val keyboardHandler = Handler()
    val width by lazy { this@PreviewActivity.resources.displayMetrics.widthPixels }
    val height by lazy { this@PreviewActivity.resources.displayMetrics.heightPixels }
    private val mFramingRect by lazy {
        RectF().apply {

            val widthFactor = 0.6f
            val frameWidth = width * widthFactor
            val horizontalOffset = width * (1 - widthFactor) / 2
            val verticalOffset = (height - frameWidth) / 2
            left = horizontalOffset
            right = horizontalOffset + frameWidth
            top = verticalOffset
            bottom = verticalOffset + frameWidth
        }
    }
    private val mPaintBorder by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
        }
    }

    private val mNFCDetectorManager by lazy{
        NFCDetectorManager(
            this,this,this::class.java
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            mBinder = IPictureCaptureInterface.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {

        }
    }


    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        initView()


        //人脸同步
        val service = Intent(this, FaceDataSyncService::class.java)
        startService(service)

        //射频id同步
        val syncService = Intent(this, DataSyncService::class.java)
        syncService.putExtra(DataSyncService.DEVICE_ID, FaceAuth().getDeviceId(this))
        startService(syncService)


        val keepAliveService = Intent(this, KeepAliveService::class.java)
        startService(keepAliveService)

        //设置注册回调
        RegisterReceiver.onReceiveRegister = { faceModel, success ->
            try {
                registerLL.visibility = View.VISIBLE
                registerTv.text =
                    "${if (success) "注册成功" else "注册失败"}: ${faceModel?.id}( ${if (faceModel?.delFlag == "1") "删除" else "注册"} ) "
                val prop = PropertiesUtils.getInstance()
                prop.open()
                val picUrl = faceModel?.absolutePicUrl(
                    prop.readString(
                        "fileBaseUrl",
                        "http://axj.ciih.net/"
                    )
                )
                Glide.with(this@PreviewActivity)
                    .load(picUrl ?: "")
                    .into(registerIv)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    registerLL.visibility = View.GONE
                }, 5000)

                // 数据变化，更新内存
                FaceApi.getInstance().initDatabases(true)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        //nfc
        mNFCDetectorManager.startNFCDetect()

    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideUi()
        intent?.apply {
            mNFCDetectorManager.processIntent(this)
        }
    }




    @SuppressLint("SetTextI18n")
    private fun initView() {
        mFaceTextureView.isOpaque = false
        mFaceTextureView.keepScreenOn = true
        mAutoTexturePreviewView.setPreviewSize(
            PREFER_WIDTH,
            PREFER_HEIGHT
        )
        hideUi()
        mDetectText.text =
            "视频方向：${SingleBaseConfig.getBaseConfig().videoDirection}\n" +
                    "检测方向：${SingleBaseConfig.getBaseConfig().detectDirection}\n"

        qrCode.setOnClickListener {
            detectQrCode = true
            displayTip("请将二维码对准扫描区域")
            qrHandler.removeCallbacksAndMessages(null)
            qrHandler.postDelayed({
                detectQrCode = false
            }, 30000)
        }

        configKeyBoard()
    }

    private fun hideUi() {
        val decorView = window.decorView

        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.decorView.systemUiVisibility = flags
        decorView
            .setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    decorView.systemUiVisibility = flags
                }
            }
    }

    override fun onResume() {
        super.onResume()
        startPreview()
    }

    override fun onStart() {
        super.onStart()
        val syncService = Intent(this, DataSyncService::class.java)
        syncService.putExtra(DataSyncService.DEVICE_ID, deviceId)
        bindService(syncService, serviceConnection, Service.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
    }


    private fun startPreview() {
        CameraPreviewManager.getInstance().cameraFacing = CameraPreviewManager.CAMERA_FACING_FRONT
        CameraPreviewManager.getInstance().startPreview(
            mAutoTexturePreviewView,
            PREFER_WIDTH,
            PREFER_HEIGHT
        ) { data, _, width, height ->
            //将图像数据放入binder
            saveBitmapForRFID(data)
            if (detectQrCode) {
                val qrcode = QRCodeUtils.decodeWithImage(data, width, height)
                hideKeyboard(0)
                if (qrcode != null) {
                    sendKeyPassInfo(qrcode)
                }
            }

            // 摄像头预览数据进行人脸检测
            FaceSDKManager.getInstance().onDetectCheck(data, null, null,
                height, width, mLiveType, object :
                    FaceDetectCallBack {
                    override fun onFaceDetectCallback(livenessModel: LivenessModel?) {
                        // 输出结果
                        checkResult(livenessModel)

                    }

                    override fun onTip(code: Int, msg: String) {
                        displayTip(msg)
                    }

                    override fun onFaceDetectDarwCallback(livenessModel: LivenessModel) {
                        // 绘制人脸框
                        showFrame(livenessModel)
                    }
                })
        }
    }

    private fun saveBitmapForRFID(data: ByteArray) {
        if (yuvType == null) {
            yuvType = Type.Builder(rs, Element.U8(rs)).setX(data.size)
            `in` = Allocation.createTyped(rs, yuvType!!.create(), Allocation.USAGE_SCRIPT)

            rgbaType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(PREFER_HEIGHT)
                .setY(PREFER_WIDTH)
            out = Allocation.createTyped(rs, rgbaType!!.create(), Allocation.USAGE_SCRIPT)
        }
        `in`!!.copyFrom(data)

        yuvToRgbIntrinsic.setInput(`in`)
        yuvToRgbIntrinsic.forEach(out)

        val bmpout =
            Bitmap.createBitmap(
                PREFER_HEIGHT,
                PREFER_WIDTH, Bitmap.Config.ARGB_8888
            )
        out!!.copyTo(bmpout)
        if (mBinder != null) {
            try {
                mBinder?.setPicture(bmpout)
            } catch (e: Throwable) {

            }

        }
    }

    private fun displayTip(tip: String) {
        runOnUiThread {
            if (tip.isEmpty()) {
                return@runOnUiThread
            }
            mDetectText.text = tip
            lastToast?.cancel()
            val toast = ToastCompat.makeText(this, tip, Toast.LENGTH_LONG)
            val view = LayoutInflater.from(this).inflate(R.layout.layout_toast_message, null)
            view.findViewById<TextView>(R.id.tv_message).text = tip
            toast.view = view
            toast.setGravity(Gravity.CENTER, 0, 0)
            lastToast = toast
            toast.show()
        }
    }

    private fun showErrorTip(tip: String) {
        runOnUiThread {
            if (tip.isEmpty()) {
                return@runOnUiThread
            }
            lastToast?.cancel()
            val toast = ToastCompat.makeText(this, tip, Toast.LENGTH_LONG)
            val view = LayoutInflater.from(this).inflate(R.layout.layout_toast_message, null)
            view.findViewById<TextView>(R.id.tv_message).text = tip
            view.findViewById<LinearLayout>(R.id.layout_toast)
                .setBackgroundColor(ActivityCompat.getColor(this, R.color.red))
            toast.view = view
            toast.setGravity(Gravity.TOP, 0, 0)
            lastToast = toast
            toast.show()
        }
    }


    private fun checkResult(livenessModel: LivenessModel?) {
        // 当未检测到人脸UI显示
        runOnUiThread(Runnable {
            if (livenessModel == null) {
                mDetectText.text = "未检测到人脸"
                return@Runnable
            }
            val user = livenessModel.user
            if (user == null) {
                mDetectText.visibility = View.VISIBLE
                mDetectText.text = "搜索不到用户"
                mFaceDetectImageView.setImageBitmap(null)
                val faceInfo = livenessModel.faceInfo
                if (faceInfo != null
                    && faceInfo.faceID != lastFaceId
                    && afterSeconds(2)
                ) {
                    lastFaceId = faceInfo.faceID

                    if (!detectQrCode) {

                        launch(threadContext) {
                            val bitmap =
                                BitmapUtils.getInstaceBmp(livenessModel.bdFaceImageInstance)
                            recordTempVisitorFace(
                                FaceImageSynchronizeSavingUtils.saveNewImage(bitmap)
                            )
                        }
                    }
                    launch(threadContext) {
                        if (isAllPass()) {
                            letGo()
                        }
                    }
                    if (!detectQrCode) {
                        showKeyCodeIME()
                    }


                }

            } else {
                imageHandler.removeCallbacksAndMessages(null)
                val imageName = if (livenessModel.user.imageName.startsWith("http")) {
                    livenessModel.user.imageName
                } else {
                    PropertiesUtils.getInstance().also {
                        it.init()
                        it.open()
                    }.readString(
                        "fileBaseUrl",
                        "http://axj.ciih.net/"
                    ) + livenessModel.user.imageName
                }
                Glide.with(mFaceDetectImageView)
                    .load(imageName)
                    .into(mFaceDetectImageView)
                imageHandler.postDelayed({
                    mFaceDetectImageView.setImageBitmap(null)
                }, 4000)
                letGo()
                if (!livenessModel.isChecked) {
                    displayTip("欢迎：${user.userName}")
                    launch(threadContext) {
                        sendEnterLog(livenessModel)
                    }
                    // sendLogInfo(livenessModel)

                }
                hideKeyboard(0)
            }
        })
    }

    @SuppressLint("CheckResult")
    private fun letGo() = launch {
        val now = System.currentTimeMillis()
        val instance = PropertiesUtils.getInstance()
        instance.init()
        instance.open()
        val relayCount = instance.readInt("relayCount", 2)
        val relayDelay = instance.readInt("relayDelay", 2000)

        if ((now - lastOpenTime) > (relayCount * relayDelay + 1000L)) {
            if (disposable?.isDisposed == false) {
                disposable?.dispose()
            }
            lastOpenTime = now
            disposable = Observable.interval(0, relayDelay.toLong(), TimeUnit.MILLISECONDS)
                .take(relayCount.toLong())
                .subscribeOn(Schedulers.io())
                .subscribe({
                    println("YJW:open gate")
                    val relayPowerSuccess = PosUtil.setRelayPower(1)
                    println("继电器:$relayPowerSuccess")
                }, {
                    it.printStackTrace()
                }, {
                    PosUtil.setRelayPower(0)
                })
        }

    }

    private suspend fun sendEnterLog(livenessModel: LivenessModel) {
        println("YJW:${Thread.currentThread()}")
        val tempFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "image_captured_${System.currentTimeMillis()}.jpg"
        )
        val image = saveImageFile(tempFile, livenessModel) ?: return
        val apiKt = RetrofitManager.getInstance().retrofit.create(ApiKt::class.java)
        try {
            val uploadResp = apiKt.uploadFile(
                MultipartBody.Builder()
                    .addFormDataPart(
                        "file",
                        image.name,
                        RequestBody.create(MediaType.parse("multipart/form-data"), image)
                    )
                    .build()
            )
            if (uploadResp.success()) {
                val addLogResp = apiKt.passByFaceId(
                    livenessModel.user.userId,
                    deviceId,
                    uploadResp.data.filePath
                )
                if (addLogResp.success()) {
                    Log.e(TAG, "上传日志成功")
                } else {
                    Log.e(TAG, "上传日志失败:${addLogResp.text}")
                }

            } else {
                Log.e(TAG, "上传图片失败")
            }

            val logFile = if (uploadResp.success()) {
                null
            } else {
                val logFile = File(
                    Environment.getExternalStorageDirectory(),
                    "${System.currentTimeMillis()}_log.jpg"
                )
                tempFile.copyTo(logFile, overwrite = true)
            }

            FaceDBManager.getInstance(this).getLogDao().addLog(
                PassageLog(
                    uploadTime = Date(),
                    isUploaded = uploadResp.success(),
                    personId = livenessModel.user.userId,
                    personName = livenessModel.user.userName,
                    imageUrl = if (uploadResp.success()) uploadResp.data.toString() else logFile?.absolutePath
                        ?: "NO_FILE_RECORD"
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "上传日志失败")
            e.printStackTrace()
        } finally {
            if (tempFile.exists())
                tempFile.delete()
        }



    }


    private fun saveImageFile(file: File?, livenessModel: LivenessModel): File? {
        val bitmap = BitmapUtils.getInstaceBmp(livenessModel.bdFaceImageInstance)
        var fileOutputStream: FileOutputStream? = null
        val directory =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val image: File
        image = file ?: File(directory, "img_captured.jpg")
        try {

            fileOutputStream = FileOutputStream(image)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream)
            fileOutputStream.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }
        }
        return image
    }

    private fun showKeyCodeIME() {
        val tvKeyCode: EditText = findViewById(R.id.tv_key_code)
        if (tvKeyCode.visibility == View.GONE) {
            tvKeyCode.text = null
        }
        layout_keyboard.visibility = View.VISIBLE
        hideKeyboard()
    }

    private fun hideKeyboard(delay: Long = 30000) {
        keyboardHandler.postDelayed({
            layout_keyboard.visibility = View.GONE
        }, delay)

    }

    private fun configKeyBoard() {
        val btn0: Button = findViewById(R.id.btn_code_0)
        val btn1: Button = findViewById(R.id.btn_code_1)
        val btn2: Button = findViewById(R.id.btn_code_2)
        val btn3: Button = findViewById(R.id.btn_code_3)
        val btn4: Button = findViewById(R.id.btn_code_4)
        val btn5: Button = findViewById(R.id.btn_code_5)
        val btn6: Button = findViewById(R.id.btn_code_6)
        val btn7: Button = findViewById(R.id.btn_code_7)
        val btn8: Button = findViewById(R.id.btn_code_8)
        val btn9: Button = findViewById(R.id.btn_code_9)
        val btnDelete: Button = findViewById(R.id.btn_code_delete)
        val btnConfirm: Button = findViewById(R.id.btn_code_confirm)
        val tvKeyCode: EditText = findViewById(R.id.tv_key_code)
        val numberListener = View.OnClickListener { v ->
            if (v is Button) {
                val origin = tvKeyCode.text
                if (origin.length < 10) {
                    tvKeyCode.append(v.text)
                }
            }
            keyboardHandler.removeCallbacksAndMessages(null)
            hideKeyboard()
        }
        btn0.setOnClickListener(numberListener)
        btn1.setOnClickListener(numberListener)
        btn2.setOnClickListener(numberListener)
        btn3.setOnClickListener(numberListener)
        btn4.setOnClickListener(numberListener)
        btn5.setOnClickListener(numberListener)
        btn6.setOnClickListener(numberListener)
        btn7.setOnClickListener(numberListener)
        btn8.setOnClickListener(numberListener)
        btn9.setOnClickListener(numberListener)
        btnDelete.setOnClickListener {
            val origin = tvKeyCode.text
            if (origin.isNotEmpty()) {
                tvKeyCode.setText(origin.subSequence(0, origin.length - 1))
            }
        }

        btnConfirm.setOnClickListener {
            val passCode = tvKeyCode.text.toString().trim { it <= ' ' }
            RetrofitManager.getInstance().retrofit.create(Api::class.java)
                .passByKeyCode(deviceId, passCode)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe({ baseResponse ->
                    ToastUtils.toast(tvKeyCode.context, baseResponse.text)
                    if (baseResponse.success()) {
                        letGo()
                        layout_keyboard.visibility = View.GONE
                        sendKeyPassInfo(passCode)
                    }
                    tvKeyCode.text = null
                    println(baseResponse)
                }, { throwable ->
                    throwable.printStackTrace()
                    ToastUtils.toast(tvKeyCode.context, "ERROR:" + throwable.localizedMessage)
                })
        }
    }


    //上传通行码通行的信息
    @SuppressLint("CheckResult")
    private fun sendKeyPassInfo(passCode: String) {
        FaceImageSynchronizeSavingUtils.getImageCopy { copy ->
            val api = RetrofitManager.getInstance().retrofit.create(ApiKt::class.java)

            val uploadResponse = api.uploadImageSync(
                MultipartBody.Builder()
                    .addFormDataPart(
                        "pic",
                        copy.name,
                        RequestBody.create(
                            MediaType.parse("multipart/form-data"),
                            copy
                        )
                    )
                    .build()
            )

            try {
                if (uploadResponse.success()) {
                    val passResponse = api.addKeyPassRecord(
                        deviceId,
                        passCode,
                        uploadResponse.data.orginPicPath
                    )


                    if (passResponse.success()) {
                        displayTip(passResponse.text)
                    } else {
                        showErrorTip(passResponse.text)
                    }
                } else {
                    showErrorTip(uploadResponse.text)
                }
            } catch (e: Exception) {
                showErrorTip(e.message ?: "通行码通行发生错误")
            } finally {
                detectQrCode = false
            }
            val logFile = if (uploadResponse.success()) {
                ToastUtils.toast(this, "上传日志成功")
                null
            } else {
                val logFile = File(
                    Environment.getExternalStorageDirectory(),
                    "${System.currentTimeMillis()}_log.jpg"
                )
                copy.copyTo(logFile, overwrite = true)
            }

            FaceDBManager.getInstance(this).getLogDao().addLog(
                PassageLog(
                    uploadTime = Date(),
                    isUploaded = uploadResponse.success(),
                    personId = "UNKNOWN",
                    personName = "VISITOR",
                    imageUrl = if (uploadResponse.success()) uploadResponse.data.toString() else logFile?.absolutePath
                        ?: "NO_FILE_RECORD"
                )
            )
        }

    }


    @SuppressLint("CheckResult")
    private suspend fun recordTempVisitorFace(image: File) {
        println("YJW:record face")
        println("RESPONSE:${Thread.currentThread()}")
        val api = RetrofitManager.getInstance().retrofit.create(ApiKt::class.java)
        val upload = try {
            val response = api.addTempVisitorRecord(
                MultipartBody.Builder()
                    .addFormDataPart(
                        "pic",
                        image.name,
                        RequestBody.create(MediaType.parse("multipart/form-data"), image)
                    )
                    .addFormDataPart("gateId", deviceId)
                    .build()
            )
            response

        } catch (e: Exception) {
            e.printStackTrace()
            showErrorTip(e.message ?: "")
            BaseResponse.fail(e)
        }
        try {

            //抓图
            val bitmap = withContext(threadContext) {
                INetSDKHelper.snapPicture {
                    val capturedFile = File(
                        Environment.getExternalStorageDirectory(),
                        "NVR_Captured_image_${System.currentTimeMillis()}.jpeg"
                    )
                    val fos = FileOutputStream(capturedFile)
                    it.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                    fos.flush()
                    fos.close()
                }
            }
            runOnUiThread {
                mFaceTempImageView.setImageBitmap(bitmap)
            }

            //下载录像

            //2分钟
            //val calendar = Calendar.getInstance()
            //calendar.time = Date()
            //calendar.set(Calendar.SECOND, 0)
            //calendar.set(Calendar.MILLISECOND, 0)
            //calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) - 1)
            //val start = calendar.time
            //calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) + 2)
            //val end = calendar.time
            //
            //INetSDKHelper.postDownloadTask(startDate = start,endDate = end)

            //前后十秒
            INetSDKHelper.postDownloadTask(startDate = Date().apply {
                this.time = this.time - 10_000
            }, endDate = Date().apply {
                this.time = this.time + 10_000
            })

        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val logFile = if (upload.success()) {
                ToastUtils.toast(this, "上传日志成功")
                null
            } else {
                val logFile = File(
                    Environment.getExternalStorageDirectory(),
                    "${System.currentTimeMillis()}_log.jpg"
                )
                image.copyTo(logFile, overwrite = true)
            }
            FaceDBManager.getInstance(this).getLogDao().addLog(
                PassageLog(
                    uploadTime = Date(),
                    isUploaded = upload.success(),
                    personId = "UNKNOWN",
                    personName = "VISITOR",
                    imageUrl = if (upload.success()) upload.data.toString() else logFile?.absolutePath
                        ?: "NO_FILE_RECORD"
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    private suspend fun isAllPass(): Boolean = suspendCoroutine {
        val instance = PropertiesUtils.getInstance()
        instance.init()
        instance.open()
        it.resume(instance.readBoolean("allPass", false))
    }

    private fun afterSeconds(second: Int): Boolean {
        return System.currentTimeMillis() - lastRecordTimeMillis > 1000 * second
    }


    /**
     * 绘制人脸框
     */
    private fun showFrame(model: LivenessModel?) {
        runOnUiThread(Runnable {

            mHandler.removeCallbacksAndMessages(null)

            val canvas = mFaceTextureView.lockCanvas()
            if (canvas == null) {
                mFaceTextureView.unlockCanvasAndPost(canvas)
                return@Runnable
            }
            if (model == null) {
                // 清空canvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                mFaceTextureView.unlockCanvasAndPost(canvas)
                return@Runnable
            }
            val faceInfos = model.trackFaceInfo
            if (faceInfos == null || faceInfos.isEmpty()) {
                // 清空canvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                mFaceTextureView.unlockCanvasAndPost(canvas)
                return@Runnable
            }
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val faceInfo = faceInfos[0]

            rectF.set(FaceOnDrawTexturViewUtil.getFaceRectTwo(faceInfo))
            // 检测图片的坐标和显示的坐标不一样，需要转换。
            FaceOnDrawTexturViewUtil.mapFromOriginalRect(
                rectF,
                mAutoTexturePreviewView, model.bdFaceImageInstance
            )


            //1s后清除人脸框
            mHandler.postDelayed({
                clearFaceDetectRect()
            }, 1000)


            //绘制二维码框框
            if (detectQrCode) {
                mPaintBorder.style = Paint.Style.FILL
                mPaintBorder.color = Color.argb(0x66, 0x00, 0x00, 0x00)
                canvas.drawRect(0f, 0f, width.toFloat(), mFramingRect.top, mPaintBorder)
                canvas.drawRect(
                    0f,
                    mFramingRect.top,
                    mFramingRect.left,
                    mFramingRect.bottom + 1,
                    mPaintBorder
                )
                canvas.drawRect(
                    mFramingRect.right + 1,
                    mFramingRect.top,
                    width.toFloat(),
                    mFramingRect.bottom + 1,
                    mPaintBorder
                )
                canvas.drawRect(
                    0f,
                    mFramingRect.bottom + 1,
                    width.toFloat(),
                    height.toFloat(),
                    mPaintBorder
                )
            }

            // 绘制框
            canvas.drawRect(rectF, paint)
            mFaceTextureView.unlockCanvasAndPost(canvas)
        })
    }

    private fun clearFaceDetectRect() {
        val canvas = mFaceTextureView.lockCanvas()
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        mFaceTextureView.unlockCanvasAndPost(canvas)
    }


}

package com.apm.anxinju.main.activity

import android.annotation.SuppressLint
import android.app.Service
import android.content.*
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
import com.apm.anxinju_baidufacesdk30.R
import com.apm.data.api.Api
import com.apm.data.model.BaseResponse
import com.apm.data.model.RetrofitManager
import com.apm.data.persistence.PropertiesUtils
import com.apm.rs485reader.service.DataSyncService
import com.apm.rs485reader.service.IPictureCaptureInterface
import com.baidu.idl.main.facesdk.FaceAuth
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon
import com.apm.anxinju.main.service.KeepAliveService
import com.apm.anxinju.main.utils.*
import com.apm.data.api.ApiKt
import com.bumptech.glide.Glide
import com.common.pos.api.util.PosUtil
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_preview.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PreviewActivity : BaseActivity() {


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
    private val qrHandler = Handler()
    private val faceAuth by lazy {
        FaceAuth().apply {
            setActiveLog(BDFaceSDKCommon.BDFaceLogInfo.BDFACE_LOG_ERROR_MESSAGE)
        }
    }
    private val deviceId by lazy {
        faceAuth.getDeviceId(this)
        "19BC95DC053A2A4D130FC17C9B4E6EED43"
    }
    private var lastOpenTime = 0L
    private var disposable: Disposable? = null
    //上一个追踪到的faceId,来自LivenessModel
    private var lastFaceId = -1
    var lastToast: Toast? = null
    //上次记录时间
    private var lastRecordTimeMillis: Long = 0
    private val mainContext: CoroutineContext =
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
        FaceDataSyncService.GlobalVars.keepInApp = service
        FaceDataSyncService.GlobalVars.taskID = taskId
        startService(service)

        //射频id同步
        val syncService = Intent(this, DataSyncService::class.java)
        syncService.putExtra(DataSyncService.DEVICE_ID, FaceAuth().getDeviceId(this))
        startService(syncService)

        bindService(syncService, serviceConnection, Service.BIND_AUTO_CREATE)

        val keepAliveService = Intent(this, KeepAliveService::class.java)
        startService(keepAliveService)

        //设置注册回调
        RegisterReceiver.onReceiveRegister = { faceModel, success ->
            try {
                registerLL.visibility = View.VISIBLE
                registerTv.text =
                    "${if (success) "注册成功" else "注册失败"}: ${faceModel?.id}( ${if (faceModel?.delFlag == "1") "删除" else "注册"} ) "
                Glide.with(this@PreviewActivity)
                    .load(faceModel?.personPic ?: "")
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

    }


    @SuppressLint("SetTextI18n")
    private fun initView() {
        mFaceTextureView.isOpaque = false
        mFaceTextureView.keepScreenOn = true
        mAutoTexturePreviewView.setPreviewSize(
            PREFER_WIDTH,
            PREFER_HEIGHT
        )
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
        mDetectText.text =
            "视频方向：${SingleBaseConfig.getBaseConfig().videoDirection}\n" +
                    "检测方向：${SingleBaseConfig.getBaseConfig().detectDirection}\n"

        qrCode.setOnClickListener {
            detectQrCode = true
            displayTip("请将二维码对准扫描区域")
            qrHandler.removeCallbacksAndMessages(null)
            qrHandler.postDelayed({
                detectQrCode = false
            },30000)
        }

        configKeyBoard()
    }

    override fun onResume() {
        super.onResume()
        startPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
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
                    e.printStackTrace()
                }

            }
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

    private fun displayTip(tip: String) {
        runOnUiThread {
            mDetectText.text = tip
            lastToast?.cancel()
            val toast = Toast.makeText(this, tip, Toast.LENGTH_LONG)
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
            val toast = Toast.makeText(this, tip, Toast.LENGTH_LONG)
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
            if (mLiveType == 1) {
                val user = livenessModel.user
                if (user == null) {
                    mDetectText.visibility = View.VISIBLE
                    mDetectText.text = "搜索不到用户"

                    val imageInstance = livenessModel.bdFaceImageInstance
                    val bitmap = BitmapUtils.getInstaceBmp(imageInstance)
                    FaceImageSynchronizeSavingUtils.saveNewImage(bitmap)

                    val faceInfo = livenessModel.faceInfo
                    if (faceInfo != null && faceInfo.faceID != lastFaceId && afterSeconds(1)) {
                        if (!detectQrCode) {
                            FaceImageSynchronizeSavingUtils.getImage {
                                recordTempVisitorFace(it)
                            }
                        }
                        lastFaceId = faceInfo.faceID
                        runBlocking {
                            val deferred = async(mainContext) {
                                return@async isAllPass()
                            }
                            if (deferred.await()) {
                                letGo()
                            }
                        }
                        if (!detectQrCode) {
                            showKeyCodeIME()
                        }

                    }

                } else {
                    val bdFaceImageInstance = livenessModel.bdFaceImageInstance
                    if (bdFaceImageInstance != null) {
                        mFaceDetectImageView.setImageBitmap(
                            BitmapUtils.getInstaceBmp(
                                bdFaceImageInstance
                            )
                        )
                    }
                    letGo()
                    if (!livenessModel.isChecked) {
                        displayTip("欢迎：${user.userName}")
                        sendLogInfo(livenessModel)
                    }
                    hideKeyboard(0)
                }
            }
        })
    }

    @SuppressLint("CheckResult")
    private fun letGo() = runBlocking(mainContext) {
        val now = System.currentTimeMillis()
        val deferred = async(mainContext) {
            val instance = PropertiesUtils.getInstance()
            instance.init()
            instance.open()
            val relayCount = instance.readInt("relayCount", 2)
            val relayDelay = instance.readInt("relayDelay", 2000)
            return@async relayCount to relayDelay
        }

        val relayCount = deferred.await().first
        val relayDelay = deferred.await().second

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

    @SuppressLint("CheckResult")
    private fun sendLogInfo(livenessModel: LivenessModel) {
        val image = saveImageFile(null, livenessModel) ?: return
        val api = RetrofitManager.getInstance().retrofit.create(Api::class.java)
        api.uploadImage(
            MultipartBody.Builder()
                .addFormDataPart(
                    "pic",
                    image.name,
                    RequestBody.create(MediaType.parse("multipart/form-data"), image)
                )
                .build()
        )
            .flatMap<BaseResponse<Any>> { baseResponse ->
                api.passByFaceId(
                    livenessModel.user.userId,
                    deviceId,
                    baseResponse.data.orginPicPath
                )
            }.subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { baseResponse ->
                    println(baseResponse.toString())
                },
                { throwable ->
                    Log.d(TAG, "sendLogInfo() called with: throwable = [$throwable]")
                    throwable.printStackTrace()
                }
            )
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

        btnConfirm.setOnClickListener { v ->
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
        FaceImageSynchronizeSavingUtils.getImage { copy ->
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

        }

    }


    @SuppressLint("CheckResult")
    private suspend fun recordTempVisitorFace(image: File) {
        println("YJW:record face")
        val api = RetrofitManager.getInstance().retrofit.create(ApiKt::class.java)
        try {
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
            println(response.toString())
        } catch (e: Exception) {
            showErrorTip(e.message ?: "")
        } finally {

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
                mPaintBorder.color = Color.argb(0x66,0x00,0x00,0x00)
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

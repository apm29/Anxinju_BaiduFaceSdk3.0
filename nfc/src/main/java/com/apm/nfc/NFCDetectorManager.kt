package com.apm.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or


/**
 *  author : ciih
 *  date : 2019-12-16 14:18
 *  description :
 */
class NFCDetectorManager (
    private val context: Activity,
    private val lifecycleOwner: LifecycleOwner,
    notifyClass: Class<*>
){
    companion object{
        val TAG = NFCDetectorManager::class.java.simpleName
    }

    private val nfcManager = context.getSystemService(Context.NFC_SERVICE) as NfcManager
    private val nfcAdapter = nfcManager.defaultAdapter
    private val pendingIntent = PendingIntent.getActivity(context, 0, Intent(context, notifyClass), 0)

    fun startNFCDetect() {
        lifecycleOwner.lifecycle.addObserver(NFCLifecycleObserver(::onResume, ::onPause))
    }

    fun processIntent(intent: Intent){
        var data: String?
        val tag:Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val techList = tag?.techList?: arrayOf()
        val id: ByteArray?= tag?.id
        data = tag.toString()
        val uid =bytesToHexString(id)
        val idString = byteArray2Str(
            hexStringToBytes(uid?.substring(2, uid.length)),
            0,
            4,
            10
        )
        data += "\n\nUID:\n$uid"
        data += "\n\nID:\n$idString"
        data += "\nData format:"
        for (tech in techList) {
            data += "\n" + tech
        }
        Log.d(TAG,data)
    }

    private fun bytesToHexString(src: ByteArray?): String? {
        val stringBuilder = StringBuilder("0x")
        if (src == null || src.isEmpty()) {
            return null
        }
        val buffer = CharArray(2)
        for (i in src.indices) {
            buffer[0] = Character.forDigit((src[i].toInt()).ushr(4) and 0x0F, 16)
            buffer[1] = Character.forDigit(src[i].toInt() and 0x0F, 16)
            stringBuilder.append(buffer)
        }
        return stringBuilder.toString()
    }

    private fun byteArray2Str(data: ByteArray?, start: Int, length: Int, targetLength: Int): String {
        var number: Long = 0
        if (data?.size?:0 < start + length) {
            return ""
        }
        for (i in 1..length) {
            number *= 0x100
            number += (data!![start + length - i] and 0xFF.toByte()).toLong()
        }
        return String.format("%0" + targetLength + "d", number)
    }

    private fun hexStringToBytes(hexString: String?): ByteArray? {
        var temp = hexString
        if (temp == null || temp == "") {
            return null
        }
        temp = temp.toUpperCase(Locale.getDefault())
        val length = temp.length / 2
        val hexChars = temp.toCharArray()
        val d = ByteArray(length)
        for (i in 0 until length) {
            val pos = i * 2
            d[i] = ((charToByte(hexChars[pos]).toInt() shl 4).toByte() or charToByte(hexChars[pos + 1]))
        }
        return d
    }

    private fun charToByte(c: Char): Byte {
        return "0123456789ABCDEF".toCharArray().indexOf(c).toByte()
    }

    private fun onResume() {
        nfcAdapter.enableForegroundDispatch(context, pendingIntent, null, null)
    }

    private fun onPause() {
        nfcAdapter.disableForegroundDispatch(context)
    }



    class NFCLifecycleObserver(
        private val onResume: () -> Unit,
        private val onPause: () -> Unit
    ) :
        LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        fun onResumeCall() {
            onResume.invoke()
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        fun onPauseCall() {
            onPause.invoke()
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        fun onDestroy() {

        }
    }

}
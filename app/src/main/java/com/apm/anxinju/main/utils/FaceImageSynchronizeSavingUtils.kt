package com.apm.anxinju.main.utils

import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 *  author : ciih
 *  date : 2019-09-28 17:16
 *  description :
 */

object FaceImageSynchronizeSavingUtils {

    private val faceFile = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "image_captured.jpg"
    )

    private val fileLock = Any()

    fun saveNewImage(image: Bitmap) {
        synchronized(fileLock) {
            var fileOutputStream: FileOutputStream? = null
            try {
                fileOutputStream = FileOutputStream(faceFile)
                image.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream)
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
        }
    }

     fun getImage(block: suspend (File) -> Unit) {
        synchronized(fileLock) {
            runBlocking {
                launch {
                    block(faceFile)
                }
            }
        }
    }
}
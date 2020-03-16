package com.apm.anxinju.main.server

import android.util.Log
import com.apm.anxinju.main.App
import com.apm.anxinju.main.service.SyncHelper
import com.apm.data.db.FaceDBManager
import com.apm.data.model.BaseResponse
import com.apm.data.model.FaceModel
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream

/**
 *  author : ciih
 *  date : 2019-08-21 15:33
 *  description :
 */
class AppServer(port: Int) : NanoHTTPD(port) {

    companion object {
        const val TAG = "AppServer"
        const val MIME_CSS = "text/css"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PNG = "image/png"
        const val MIME_JSON = "application/json;charset=utf8"
    }

    init {
        Log.d(TAG, "Start AppServer on Port $port")
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    override fun serve(session: IHTTPSession): Response {
        Log.e(TAG, session.uri)
        return when (session.uri) {
            //Main
            "/", "/index_home.html" -> {
                val data: InputStream = App.contextGlobal.assets.open("index_home.html")
                newChunkedResponse(Response.Status.OK, MIME_HTML, data)
            }
            // data interfaces
            "/databaseList" -> {
                newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_PLAINTEXT,
                    gson.toJson(arrayListOf<Any>())
                )
            }
            "/logList" -> {
                val page = session.parms["page"]?.toInt() ?: 0
                val pageSize = session.parms["pageCount"]?.toInt() ?: 50
                val pagedList: List<Any> = arrayListOf<Any>()
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, gson.toJson(pagedList))
            }
            "/logListPageCount" -> {
                val pageSize = session.parms["pageCount"]?.toInt() ?: 50
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, gson.toJson(0))
            }

            "/resetFaceSyncTime" -> {
                val lastSyncTime = session.parms["lastSyncTime"]
                if (lastSyncTime != null) {
                    val time = try {
                        java.lang.Long.parseLong(lastSyncTime)
                    } catch (e: Exception) {
                        0L
                    }
                    val edit = FaceDBManager.getInstance(App.contextGlobal)
                        .getSyncDao().resetFaceLastSyncTime(time)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_JSON,
                        gson.toJson(BaseResponse.success(edit))
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_JSON,
                        gson.toJson(BaseResponse.error<Any>())
                    )
                }
            }

            "/resetRFIDSyncTime" -> {
                val lastSyncTime = session.parms["lastSyncTime"]
                if (lastSyncTime != null) {
                    val time = try {
                        java.lang.Long.parseLong(lastSyncTime)
                    } catch (e: Exception) {
                        0L
                    }
                    val edit = FaceDBManager.getInstance(App.contextGlobal)
                        .getSyncDao().resetRFIDLastSyncTime(time)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_JSON,
                        gson.toJson(BaseResponse.success(edit))
                    )
                } else {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_JSON,
                        gson.toJson(BaseResponse.error<Any>())
                    )
                }
            }

            "/registerFace" -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val facePath = files["face"]
                val name = session.parms["uid"]
                val idStr = session.parms["id"]
                println("files = $files")
                println("parms = ${session.parms}")
                if (facePath == null || name == null) {
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_JSON,
                        gson.toJson(
                            BaseResponse<Any>(
                                "500",
                                "face 或者 uid 不可为空",
                                null,
                                null
                            )
                        )
                    )
                }
                val id = try {
                    idStr?.toLong()
                } catch (e: Exception) {
                    Long.MIN_VALUE
                }
                if (id == Long.MIN_VALUE) {
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        MIME_JSON,
                        gson.toJson(
                            BaseResponse<Any>(
                                "500",
                                "id解析失败",
                                null,
                                null
                            )
                        )
                    )
                }

                val faceFile = File(facePath)
                val resultPair = register(faceFile, name, id)
                return newFixedLengthResponse(
                    Response.Status.OK,
                    MIME_JSON,
                    gson.toJson(
                        BaseResponse<Any>(
                            resultPair.first,
                            resultPair.second,
                            null,
                            null
                        )
                    )
                )
            }


            //file interfaces
            else -> {
                val start = session.uri.indexOf('/') + 1
                val end = session.uri.length
                val route = session.uri.substring(start, end)
                val data: InputStream = App.contextGlobal.assets.open(route)
                val mimeType = when (route.substring(route.lastIndexOf("."))) {
                    ".css" -> MIME_CSS
                    ".html" -> MIME_HTML
                    ".png" -> MIME_PNG
                    ".jpg" -> MIME_JPEG
                    else -> MIME_PLAINTEXT
                }
                newChunkedResponse(Response.Status.OK, mimeType, data)
            }
        }
    }

    private fun register(faceFile: File, name: String, registerId: Long?): Pair<String, String> {
        val success = SyncHelper.registerByFile(faceFile, FaceModel().apply {
            this.uid = name
            this.personPic = faceFile.path
            if (registerId != null) {
                this.id = registerId.toInt()
            }
        }, "origin_${registerId}_id.jpg")
        return (if (success) "200" else "500") to (if (success) "OK" else "REGISTER FAIL")
    }


}
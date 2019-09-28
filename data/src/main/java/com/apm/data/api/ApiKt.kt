package com.apm.data.api

import com.apm.data.model.BaseResponse
import com.apm.data.model.ImageDetail
import io.reactivex.Observable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface ApiKt {

    /**
     * pic - image
     */
    @POST("/business/upload/uploadPic")
    suspend fun uploadImageSync(
            @Body image: MultipartBody
    ): BaseResponse<ImageDetail>


    @FormUrlEncoded
    @POST("/business/visitor/addVisitorGate")
    suspend fun addKeyPassRecord(
        @Field("gateId") gateId: String,
        @Field("passCode") passCode: String,
        @Field("visitorAvatar") imageUrl: String
    ): BaseResponse<*>


    //电动车通行日志
    @FormUrlEncoded
    @POST("/business/ebikeLog/addLog")
    suspend fun addEBikePassLog(
            @Field("recogId") id: String,
            @Field("doorNo") deviceId: String,
            @Field("imageUrl") image: String?
    ): BaseResponse<*>


    //临时访客记录
    @POST("/business/gateImage/uploadGateImage")
    suspend fun addTempVisitorRecord(
        @Body data: MultipartBody
    ): BaseResponse<*>

}
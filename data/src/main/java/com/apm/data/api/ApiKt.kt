package com.apm.data.api

import com.apm.data.model.BaseResponse
import com.apm.data.model.ImageDetail
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


    //电动车通行日志
    @FormUrlEncoded
    @POST("/business/ebikeLog/addLog")
    suspend fun addEBikePassLog(
            @Field("recogId") id: String,
            @Field("doorNo") deviceId: String,
            @Field("imageUrl") image: String?
    ): BaseResponse<*>

}
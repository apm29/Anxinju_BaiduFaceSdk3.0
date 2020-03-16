package com.spark.zj.comcom

import com.spark.zj.comcom.serial.*

import org.junit.Test
import java.util.concurrent.TimeUnit

import io.reactivex.Observable
import io.reactivex.functions.Consumer
import io.reactivex.functions.Predicate

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see [Testing documentation](http://d.android.com/tools/testing)
 */
class ExampleUnitTest {
    internal var last: Long = 0
    @Test
    fun addition_isCorrect() {

        val bytes = byteArrayOf(
            0x11.toByte(),
            0x0e.toByte(),
            0xe0.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x13,
            0x66,
            0x74,
            0x74
        )
        //110EE000000000001317925
        ByteCRC16.verifyCRC16Data(bytes).apply {
            println(this)
        }

        val bytes2 = byteArrayOf(
            0x11.toByte(),
            0x0e.toByte(),
            0xe0.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x13,
            0x66
        )

        ByteCRC16.getCRC16(bytes2).toUpperCase()
            .apply {
                println(this)
            }


    }

}
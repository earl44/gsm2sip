package com.callagent.gateway.gsm

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log

/** Keeps API 26 USSD types out of classes loaded by Android 7's verifier. */
@androidx.annotation.RequiresApi(26)
object UssdCompat {
    private const val TAG = "GsmCallManager"

    @SuppressLint("MissingPermission")
    fun send(context: Context, code: String, onResult: (String) -> Unit) {
        val telephony =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephony.sendUssdRequest(
            code,
            object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    tm: TelephonyManager, request: String, response: CharSequence
                ) {
                    Log.i(TAG, "USSD $request → $response")
                    onResult(response.toString())
                }

                override fun onReceiveUssdResponseFailed(
                    tm: TelephonyManager, request: String, failureCode: Int
                ) {
                    Log.w(TAG, "USSD $request failed (code $failureCode)")
                    onResult("USSD $request failed (code $failureCode)")
                }
            },
            Handler(Looper.getMainLooper())
        )
    }
}

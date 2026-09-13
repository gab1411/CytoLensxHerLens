package com.jiangdg.demo

import android.content.Context
import androidx.multidex.MultiDex
import com.jiangdg.ausbc.base.BaseApplication
import com.jiangdg.utils.MMKVUtils
import com.tencent.bugly.crashreport.CrashReport

class DemoApplication: BaseApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // init bugly library
        CrashReport.initCrashReport(this, "9baa0e3fac", true)
        MMKVUtils.init(this)
    }
}
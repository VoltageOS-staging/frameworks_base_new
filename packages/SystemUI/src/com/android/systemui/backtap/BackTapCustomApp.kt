/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.backtap

import android.content.pm.ActivityInfo
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.ListView
import com.android.systemui.statusbar.phone.AppPicker

class BackTapCustomApp : AppPicker() {
    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        if (!mIsActivitiesList) {
            val packageName: String = applist[position].packageName
            val friendlyAppString: String = applist[position].loadLabel(packageManager).toString()
            setPackage(packageName, friendlyAppString)
            setPackageActivity(null)
        } else {
            setPackageActivity(mActivitiesList[position])
        }
        mIsActivitiesList = false
        finish()
    }

    override fun onLongClick(position: Int) {
        if (mIsActivitiesList) return
        val packageName: String = applist[position].packageName
        val friendlyAppString: String = applist[position].loadLabel(packageManager).toString()
        setPackage(packageName, friendlyAppString)
        setPackageActivity(null)
        showActivitiesDialog(packageName)
    }

    private fun setPackage(packageName: String, friendlyAppString: String) {
        Settings.System.putStringForUser(contentResolver, Settings.System.BACK_TAP_APP_ACTION, packageName, UserHandle.USER_CURRENT)
        Settings.System.putStringForUser(contentResolver, Settings.System.BACK_TAP_APP_FR_ACTION, friendlyAppString, UserHandle.USER_CURRENT)
    }

    private fun setPackageActivity(ai: ActivityInfo?) {
        Settings.System.putStringForUser(contentResolver, Settings.System.BACK_TAP_APP_ACTIVITY_ACTION, ai?.name ?: "NONE", UserHandle.USER_CURRENT)
    }
}

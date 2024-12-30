/*
 * SPDX-FileCopyrightText: 2024 MDP43140
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.mdp43140.superfreeze
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import io.mdp43140.superfreeze.util.CertUtil.isInstalledByFDroid
import io.mdp43140.superfreeze.util.CertUtil.isSignedByFDroid
//import io.mdp43140.superfreeze.badPackages
//import io.mdp43140.superfreeze.goodPackages
class AppListItems(private val ctx: Context){
	var appList: List<AppItem> = emptyList()
	// TODO: should sync across instances, but putting this on companion fucked with compile error :(
	var usageStatsMap: Map<String, UsageStats>? = null
	abstract class AbstractItem {
		abstract var flags: Int
		abstract var icon: Drawable?
		abstract var isInstalledByFDroid: Boolean
		abstract var isItemSelected: Boolean
		abstract var isSignedByFDroid: Boolean
		abstract var ignoreRunning: Boolean
		abstract var ignoreBgFree: Boolean
		abstract var label: String
		abstract var pkg: String
		abstract var selected: Boolean
		abstract var stopMode: Int
		abstract fun loadIcon()
	}
	class AppItem(val appInfo: ApplicationInfo, val ctx: Context): AbstractItem(){
		override var flags: Int = appInfo.flags
		override var icon: Drawable? = null
		override var ignoreRunning: Boolean = false
		override var ignoreBgFree: Boolean = true
		override var isInstalledByFDroid: Boolean = isInstalledByFDroid(ctx,appInfo.packageName)
		override var isItemSelected: Boolean = false
		override var isSignedByFDroid: Boolean = isSignedByFDroid(ctx,appInfo.packageName)
		override var label: String = appInfo.loadLabel(ctx.packageManager).toString()
		override var pkg: String = appInfo.packageName
		override var selected: Boolean = false
		override var stopMode: Int = -1
		override fun loadIcon(){
			icon = appInfo.loadIcon(ctx.packageManager)
		}
		init {
			val isBadPkg = badPackages.contains(pkg)
		//ignoreRunning = false
			ignoreBgFree = isBadPkg
			stopMode =
				if (isBadPkg) 1
				else if (CommonFunctions.isFlagSet(flags,ApplicationInfo.FLAG_SYSTEM)) 0
				else if (pkg == ctx.packageName || pkg == launcherPkg) 0
				else if (goodPackages.contains(pkg) || isInstalledByFDroid || isSignedByFDroid) 0
				else 1
		}
	}
	class LabelItem(override var label: String): AbstractItem(){
		// TODO: remove below
		override var flags: Int = -1
		override var icon: Drawable? = null
		override var ignoreRunning: Boolean = false
		override var ignoreBgFree: Boolean = false
		override var isInstalledByFDroid: Boolean  = false
		override var isItemSelected: Boolean = false
		override var isSignedByFDroid: Boolean = false
		override var pkg: String = ""
		override var selected: Boolean = false
		override var stopMode: Int = -1
		override fun loadIcon(){}
	}
	fun getPendingStopApps(): List<AppItem>{
		val list = mutableListOf<AppItem>()
		for (app in appList)
			if (isAppPendingStop(app))
				list += app
		// Always freeze itself last
		list.sortBy { it.pkg == ctx.packageName }
		return list
	}
	fun isAppPendingStop(app: AppItem): Boolean {
		val isBgFree = false
		val isBgFreeEnforced = false
		val isForeground = false // fgApps.contains(app.pkg)
		val hasPersistentNotification = NotificationService.persistNotificationApps.contains(app.pkg)
		val isPlayingMedia = NotificationService.mediaPlaybackApps.contains(app.pkg)
		val isRecentlyUnused = isPkgRecentlyUnused(app.pkg)
		val isStopped = CommonFunctions.isFlagSet(app.flags,ApplicationInfo.FLAG_STOPPED)
		// Stopping not disabled (normal/inactive) &
		// not stopped (important) &
		// - Ignore running / not playing media & not having persistent notification (foreground service) & not foreground (TODO: fix fg app detection)
		// - Mode is not root but inactive stop mode
		// - Ignore background free / bg free not enforced/cached (temporarily set to false. TODO. hint: GET_APP_OPS_STATS, ...greenify.utils.Hacks)
		// - recently unused (TODO: detected as pending stop even if the app is running)
		// - detect apps with battery optimization disabled
		val ret =
			(app.stopMode == 1 || app.stopMode == 2) &&
			(App.workMode != "root" && app.stopMode == 2) == false &&
			!isStopped && (
				(app.ignoreRunning || (!isPlayingMedia && !hasPersistentNotification && !isForeground)) &&
				(app.ignoreBgFree || (!isBgFree && !isBgFreeEnforced && isRecentlyUnused))
			)
		return ret
	}
	fun getAggregatedUsageStats(hours: Int){
		val usm = ctx.getSystemService(UsageStatsManager::class.java)
		val now = System.currentTimeMillis()
		usageStatsMap = usm.queryAndAggregateUsageStats(
			now - 1000L * 60 * 60 * hours,
			now
		)
	}
	fun isPkgRecentlyUnused(pkg: String): Boolean {
		if (pkg == ctx.packageName) return false // The user is just using SFNext right now... :)
		val usageStats = usageStatsMap?.get(pkg)
		return usageStats == null   || // There are no usagestats of this package -> it wasn't used recently
			isPkgInactive(pkg)        || // App is inactive
			usageStats.lastTimeUsed > 1L // last time used is more than 1 second, considered not running (workaround)
	};
	fun isPkgInactive(pkg: String): Boolean {
		return ctx.getSystemService(UsageStatsManager::class.java).isAppInactive(pkg)
	};
	fun isIgnoringBatteryOptimizations(pkg: String): Boolean {
		return ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
	}
	fun loadPrefs(){
		if (prefs == null) prefs = ctx.getSharedPreferences("apps",Context.MODE_PRIVATE)
	}
	fun loadApps(){
		setFromApplicationInfo(ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA))
	}
	fun setFromApplicationInfo(appList2: List<ApplicationInfo>){
		appList = appList2.map { AppItem(it,ctx) }
	}
	fun getDataFromPrefs(){
		prefs?.let {
			val emptySet2: Set<String> = emptySet()
			val equalSign = '='
			val trueStr = "true"
			val ignoreRunning = "ignoreRunning"
			val ignoreBgFree = "ignoreBgFree"
			val stopMode = "stopMode"
			for (app in appList){
				for (data in it.getStringSet(app.pkg,emptySet2)!!){
					val equalIndex = data.indexOf(equalSign)
					if (equalIndex != -1) {
						val k = data.substring(0,equalIndex)
						val v = data.substring(equalIndex + 1)
						when (k){
							ignoreRunning -> app.ignoreRunning = v == trueStr
							ignoreBgFree  -> app.ignoreBgFree  = v == trueStr
							stopMode      -> app.stopMode      = v.toInt()
						}
					}
				}
			}
		}
	}
	fun storeDataToPrefs(appInfo: AppItem){
		prefs!!.edit().putStringSet("${appInfo.pkg}",mutableSetOf<String>(
			"ignoreRunning=${appInfo.ignoreRunning}",
			"ignoreBgFree=${appInfo.ignoreBgFree}",
			"stopMode=${appInfo.stopMode}"
		)).apply()
	}
	init {
		getAggregatedUsageStats(7 * 24)
		launcherPkg = ctx.packageManager.resolveActivity(
			Intent("android.intent.action.MAIN").apply { addCategory("android.intent.category.HOME") },
			PackageManager.MATCH_DEFAULT_ONLY
		)?.activityInfo?.packageName
	}
	companion object {
		var prefs: SharedPreferences? = null // apps.xml
		var launcherPkg: String? = null
	}
}

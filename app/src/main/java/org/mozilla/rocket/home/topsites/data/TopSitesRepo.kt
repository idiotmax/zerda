package org.mozilla.rocket.home.topsites.data

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.preference.PreferenceManager
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.mozilla.fileutils.FileUtils
import org.mozilla.focus.history.BrowsingHistoryManager
import org.mozilla.focus.history.model.Site
import org.mozilla.focus.provider.HistoryContract
import org.mozilla.focus.provider.HistoryDatabaseHelper
import org.mozilla.focus.provider.QueryHandler
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.utils.DimenUtils
import org.mozilla.focus.utils.TopSitesUtils
import org.mozilla.icon.FavIconUtils
import org.mozilla.rocket.home.pinsite.PinSiteManager
import org.mozilla.rocket.persistance.History.HistoryDatabase
import java.lang.ref.WeakReference
import java.util.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

open class TopSitesRepo(
    private val appContext: Context,
    private val pinSiteManager: PinSiteManager
) {

    private var needToCheckDbVersion = true

    fun getFixedSites(): List<Site> {
        val jsonArray = TopSitesUtils.getFixedSitesJsonArrayFromAssets(appContext)

        return TopSitesUtils.paresJsonToList(appContext, jsonArray)
    }

    fun getPinnedSites(): List<Site> = pinSiteManager.getPinSites()

    fun getHistorySitesAsync(callback: (List<Site>) -> Unit) {
        if (needToCheckDbVersion) {
            needToCheckDbVersion = false
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
            if (sharedPreferences.contains(TOP_SITES_V2_PREF)) {
                queryHistorySitesAsync(callback)
            } else {
                startDbMigrationAndGetHistorySitesAsync(callback)
            }
        } else {
            queryHistorySitesAsync(callback)
        }
    }

    private fun queryHistorySitesAsync(callback: (List<Site>) -> Unit) {
        BrowsingHistoryManager.getInstance()
                .queryTopSites(TOP_SITES_QUERY_LIMIT, TOP_SITES_QUERY_MIN_VIEW_COUNT) {
                    callback(it.filterIsInstance<Site>())
                }
    }

    private fun startDbMigrationAndGetHistorySitesAsync(callback: (List<Site>) -> Unit) {
        Thread(MigrateHistoryRunnable(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_ID_REFRESH) {
                    queryHistorySitesAsync(callback)
                }
            }
        }, appContext)).start()
    }

    fun getDefaultSites(): List<Site> {
        val jsonArray = getDefaultTopSitesJsonArrayFromSharedPref()
                ?: TopSitesUtils.getDefaultSitesJsonArrayFromAssets(appContext)

        return TopSitesUtils.paresJsonToList(appContext, jsonArray)
    }

    // open for mocking during testing
    open fun getDefaultTopSitesJsonString(): String? {
        return PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(TOP_SITES_PREF, null)
    }

    private fun getDefaultTopSitesJsonArrayFromSharedPref(): JSONArray? {
        val jsonString = getDefaultTopSitesJsonString()

        return if (jsonString != null) {
            try {
                JSONArray(jsonString)
            } catch (e: JSONException) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }

    fun isPinEnabled(): Boolean = pinSiteManager.isEnabled()

    fun pin(site: Site) {
        pinSiteManager.pin(site)
    }

    suspend fun remove(site: Site) {
        pinSiteManager.unpinned(site)
        val isDefaultSite = site.id < 0
        TelemetryWrapper.removeTopSite(isDefaultSite)
        if (isDefaultSite) {
            removeDefaultSite(site)
        }
        withContext(Dispatchers.IO) {
            updateTopSiteToDb(site.apply { viewCount = 1 })
        }
    }

    private suspend fun updateTopSiteToDb(site: Site) {
        suspendCoroutine<Unit> { continuation ->
            BrowsingHistoryManager.getInstance().updateLastEntry(site) { continuation.resume(Unit) }
        }
    }

    fun removeDefaultSite(site: Site) {
        val defaultSitesJsonArray = getDefaultTopSitesJsonArrayFromSharedPref()
        if (defaultSitesJsonArray != null) {
            try {
                defaultSitesJsonArray.apply {
                    for (i in 0 until this.length()) {
                        val jsonObject = this.get(i) as JSONObject
                        if (site.id == jsonObject.getLong("id")) {
                            this.remove(i)
                            break
                        }
                    }
                }
                TopSitesUtils.saveDefaultSites(appContext, defaultSitesJsonArray)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }

    fun isPinned(site: Site): Boolean = pinSiteManager.isPinned(site)

    private class MigrateHistoryRunnable(handler: Handler, context: Context) : Runnable {

        private val handlerWeakReference: WeakReference<Handler> = WeakReference(handler)
        private val contextWeakReference: WeakReference<Context> = WeakReference(context)

        override fun run() {
            val context = contextWeakReference.get() ?: return

            val helper = HistoryDatabase.getInstance(context).openHelper
            val db = helper.writableDatabase
            // We can't differentiate if this is a new install or upgrade given the db version will
            // already become the latest version here. We create a temp table if no migration is
            // needed and later delete it to prevent crashing.
            db.execSQL(HistoryDatabase.CREATE_LEGACY_IF_NOT_EXIST)
            val builder = SupportSQLiteQueryBuilder.builder(HistoryDatabaseHelper.Tables.BROWSING_HISTORY_LEGACY)
            val columns = arrayOf(HistoryContract.BrowsingHistory._ID, HistoryContract.BrowsingHistory.URL, HistoryContract.BrowsingHistory.FAV_ICON)
            builder.columns(columns)
            val query = builder.create()
            val faviconFolder = FileUtils.getFaviconFolder(context)
            val urls = ArrayList<String>()
            val icons = ArrayList<ByteArray>()
            db.query(query).use { cursor ->
                if (cursor.moveToFirst()) {
                    parseCursorToSite(cursor, urls, icons)
                }
                while (cursor.moveToNext()) {
                    parseCursorToSite(cursor, urls, icons)
                }
            }
            val handler = handlerWeakReference.get() ?: return
            if (icons.size == 0) {
                scheduleRefresh(handler)
            } else {
                // Refresh is still scheduled implicitly in SaveBitmapsTask
                FavIconUtils.SaveBitmapsTask(faviconFolder, urls, icons, UpdateHistoryWrapper(urls, handlerWeakReference),
                        Bitmap.CompressFormat.PNG, DimenUtils.PNG_QUALITY_DONT_CARE).execute()
            }
            db.execSQL("DROP TABLE " + HistoryDatabaseHelper.Tables.BROWSING_HISTORY_LEGACY)
            PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(TOP_SITES_V2_PREF, true).apply()
        }

        private fun parseCursorToSite(cursor: Cursor, urls: MutableList<String>, icons: MutableList<ByteArray>) {
            val url = cursor.getString(cursor.getColumnIndex(HistoryContract.BrowsingHistory.URL))
            val icon = cursor.getBlob(cursor.getColumnIndex(HistoryContract.BrowsingHistory.FAV_ICON))
            urls.add(url)
            icons.add(icon)
        }

        private fun scheduleRefresh(handler: Handler) {
            val message = handler.obtainMessage(MSG_ID_REFRESH)
            handler.dispatchMessage(message)
        }
    }

    private class UpdateHistoryWrapper(
        private val urls: List<String>,
        private val handlerWeakReference: WeakReference<Handler>
    ) : FavIconUtils.Consumer<List<String>> {

        override fun accept(fileUris: List<String>) {
            val listener = QueryHandler.AsyncUpdateListener {
                val handler = handlerWeakReference.get() ?: return@AsyncUpdateListener
                scheduleRefresh(handler)
            }
            for (i in fileUris.indices) {
                if (i == fileUris.size - 1) {
                    BrowsingHistoryManager.updateHistory(null, urls[i], fileUris[i], listener)
                } else {
                    BrowsingHistoryManager.updateHistory(null, urls[i], fileUris[i])
                }
            }
        }

        private fun scheduleRefresh(handler: Handler) {
            val message = handler.obtainMessage(MSG_ID_REFRESH)
            handler.dispatchMessage(message)
        }
    }

    companion object {
        private const val TOP_SITES_PREF = "topsites_pref"
        private const val TOP_SITES_V2_PREF = "top_sites_v2_complete"
        private const val TOP_SITES_QUERY_LIMIT = 12
        private const val TOP_SITES_QUERY_MIN_VIEW_COUNT = 2
        private const val MSG_ID_REFRESH = 8269
    }
}

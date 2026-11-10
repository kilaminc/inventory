package com.healthytiffin.inventory

import android.content.Context
import android.util.Base64
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class Api(private val store:Store) {
    val base get()=store.prefs.getString("url","")!!.trim().trimEnd('/')
    val ready get()=base.isNotBlank() && store.prefs.getString("token","")!!.isNotBlank()
    fun request(method:String,path:String,body:JSONObject?=null):String {
        val uri=URL(base+path)
        require(uri.protocol=="https" || (uri.protocol=="http" && uri.host in listOf("10.0.2.2","localhost"))) { "Use an HTTPS backend URL." }
        val c=uri.openConnection() as HttpURLConnection
        try {
            c.requestMethod=method;c.connectTimeout=12000;c.readTimeout=85000
            c.setRequestProperty("Authorization","Bearer "+store.prefs.getString("token",""))
            if(body!=null) {
                c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code=c.responseCode
            val text=(if(code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if(code !in 200..299) {
                val detail=runCatching {JSONObject(text).opt("detail")?.toString() ?: "Connection error $code"}.getOrDefault("Connection error $code")
                throw IllegalStateException(detail.take(300))
            }
            return text
        } finally {c.disconnect()}
    }
}

object SyncEngine {
    private val mutex=Mutex()
    suspend fun run(store:Store):String=withContext(Dispatchers.IO) {mutex.withLock {
        val api=Api(store);if(!api.ready) return@withLock "Saved on phone. Connect the backend in Settings to upload."
        // Upload evidence before catalogue references or count commits.
        for(c in store.all("capture").filter {!it.optBoolean("uploaded")}) {
            val body=JSONObject(c.toString())
            listOf("path","uploaded","status","analysis","error").forEach {body.remove(it)}
            body.put("image_base64",Base64.encodeToString(File(c.s("path")).readBytes(),Base64.NO_WRAP))
            api.request("POST","/api/captures",body)
            c.put("uploaded",true).put("status","awaiting_analysis");store.put("capture",c.s("id"),c)
        }
        for(i in store.items().filter {it.optBoolean("dirty")}) {
            val b=JSONObject(i.toString());b.remove("dirty")
            api.request("PUT","/api/items/"+i.s("sku"),b)
            i.put("dirty",false);store.put("item",i.s("sku"),i)
        }
        for(c in store.all("commit").filter {!it.optBoolean("uploaded")}.sortedBy {it.s("counted_at")}) {
            val body=JSONObject(c.toString());body.remove("uploaded");body.remove("error")
            try {api.request("POST","/api/commits",body);c.put("uploaded",true);c.remove("error")}
            catch(e:Exception) {c.put("error",e.message);store.put("commit",c.s("id"),c);throw e}
            store.put("commit",c.s("id"),c)
        }
        var deferred=0
        for(c in store.all("capture").filter {it.optBoolean("uploaded") && it.s("status")!="needs_review"}) {
            try {
                val result=JSONObject(api.request("POST","/api/captures/"+c.s("id")+"/analyze"))
                c.put("analysis",result).put("status",result.s("status"));c.remove("error")
            } catch(e:Exception) {c.put("error",e.message);deferred++}
            store.put("capture",c.s("id"),c)
        }
        val remoteItems=JSONArray(api.request("GET","/api/items"))
        for(i in remoteItems.objects()) if(store.get("item",i.s("sku"))?.optBoolean("dirty")!=true) store.put("item",i.s("sku"),i)
        for(s in JSONArray(api.request("GET","/api/stock")).objects()) {
            val key=s.s("location")+":"+s.s("sku");val local=store.get("stock",key)
            if(local==null || local.s("counted_at")<=s.s("counted_at") || local.s("commit_id")==s.s("commit_id")) {
                s.put("name",store.get("item",s.s("sku"))?.s("name") ?: s.s("sku")).put("server_confirmed",true)
                store.put("stock",key,s)
            }
        }
        val status=JSONObject(api.request("GET","/api/status"));store.prefs.edit().putString("server_status",status.toString()).apply()
        var sheets="Google Sheets is not connected yet."
        if(status.optBoolean("sheets_ready")) {
            try {api.request("POST","/api/sheets/sync");sheets="Google Sheets updated."}
            catch(e:Exception) {sheets="Counts uploaded. Sheets sync needs attention: "+e.message}
        }
        store.prefs.edit().putString("last_sync",stamp()).apply()
        "Counts and photos uploaded. $sheets" + if(deferred>0) " $deferred photos await analysis." else ""
    }}
}

class UploadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val store=Store(applicationContext)
        if(!Api(store).ready) return Result.success()
        return try {SyncEngine.run(store);Result.success()} catch(e:Exception) {
            store.prefs.edit().putString("sync_error",e.message).apply();Result.retry()
        }
    }
    companion object {
        fun schedule(context:Context) {
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork("inventory-upload",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}

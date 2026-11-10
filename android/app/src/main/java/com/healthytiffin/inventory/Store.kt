package com.healthytiffin.inventory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

fun JSONObject.s(key:String, default:String=""):String = if (isNull(key)) default else optString(key,default)
fun JSONObject.number(key:String):Double? = if (!has(key)||isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
fun JSONArray.objects():List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun uid() = UUID.randomUUID().toString()
fun stamp() = Instant.now().toString()

class Store(context:Context):SQLiteOpenHelper(context.applicationContext,"inventory.db",null,1) {
    val appContext=context.applicationContext
    val prefs=appContext.getSharedPreferences("device",Context.MODE_PRIVATE)
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(kind TEXT,id TEXT,body TEXT NOT NULL, PRIMARY KEY(kind,id))")
    }
    override fun onUpgrade(db:SQLiteDatabase, old:Int, new:Int) {}
    fun all(kind:String):List<JSONObject> = readableDatabase.rawQuery("SELECT body FROM records WHERE kind=? ORDER BY rowid DESC",arrayOf(kind)).use { c ->
        buildList { while(c.moveToNext()) add(JSONObject(c.getString(0))) }
    }
    fun get(kind:String,id:String):JSONObject? = readableDatabase.rawQuery("SELECT body FROM records WHERE kind=? AND id=?",arrayOf(kind,id)).use { c ->
        if(c.moveToFirst()) JSONObject(c.getString(0)) else null
    }
    @Synchronized fun put(kind:String,id:String,body:JSONObject) {
        writableDatabase.insertWithOnConflict("records",null,ContentValues().apply {
            put("kind",kind);put("id",id);put("body",body.toString())
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun remove(kind:String,id:String) { writableDatabase.delete("records","kind=? AND id=?",arrayOf(kind,id)) }
    fun seed() {
        if(prefs.getBoolean("seeded",false)) return
        val catalog=JSONArray(appContext.assets.open("catalog.json").bufferedReader().readText())
        catalog.objects().forEach { put("item",it.s("sku"),it) }
        prefs.edit().putBoolean("seeded",true).apply()
    }
    fun items()=all("item").sortedBy { it.s("name") }
    fun startSession(location:String,operator:String):JSONObject {
        val x=JSONObject().put("id",uid()).put("location",location.trim()).put("operator",operator.trim())
            .put("started_at",stamp()).put("status","active")
        put("session",x.s("id"),x);return x
    }
    fun sessionCaptures(id:String)=all("capture").filter {it.s("session_id")==id}.sortedBy {it.s("captured_at")}
    fun sessionLines(id:String)=all("line").filter {it.s("session_id")==id}.sortedBy {it.s("name")}
    fun evidenceFile(id:String)=File(appContext.filesDir,"evidence/$id.jpg").apply { parentFile?.mkdirs() }
    fun addCapture(session:JSONObject,path:String,ocr:String,barcodes:List<String>,id:String):JSONObject {
        val x=JSONObject().put("id",id).put("session_id",session.s("id")).put("location",session.s("location"))
            .put("operator",session.s("operator")).put("captured_at",stamp()).put("path",path)
            .put("ocr",ocr).put("barcodes",JSONArray(barcodes)).put("uploaded",false).put("status","saved_on_phone")
        put("capture",id,x);return x
    }
    fun saveLine(sessionId:String,item:JSONObject,qty:Double,unit:String,source:String,evidenceIds:List<String>,note:String) {
        require(qty.isFinite() && qty>=0 && unit.isNotBlank() && evidenceIds.isNotEmpty())
        val key=sessionId+":"+item.s("sku")
        val x=JSONObject().put("id",key).put("session_id",sessionId).put("sku",item.s("sku")).put("name",item.s("name"))
            .put("quantity",qty).put("unit",unit.trim()).put("source",source).put("evidence_ids",JSONArray(evidenceIds)).put("note",note)
        put("line",key,x)
    }
    @Synchronized fun finishSession(session:JSONObject) {
        val lines=sessionLines(session.s("id"));require(lines.isNotEmpty())
        val existing=all("commit").firstOrNull {it.s("session_id")==session.s("id")}
        if(existing!=null) return
        val time=stamp();val id=uid()
        val clean=JSONArray(lines.map { line -> JSONObject().apply {
            listOf("sku","quantity","unit","source","evidence_ids","note").forEach {put(it,line.get(it))}
        } })
        val commit=JSONObject().put("id",id).put("session_id",session.s("id")).put("location",session.s("location"))
            .put("operator",session.s("operator")).put("counted_at",time).put("lines",clean).put("review_confirmed",true).put("uploaded",false)
        val db=writableDatabase;db.beginTransaction()
        try {
            put("commit",id,commit)
            for(line in lines) {
                val stock=JSONObject(line.toString()).put("location",session.s("location")).put("counted_at",time)
                    .put("operator",session.s("operator")).put("commit_id",id).put("server_confirmed",false)
                put("stock",session.s("location")+":"+line.s("sku"),stock)
            }
            session.put("status","finished");put("session",session.s("id"),session);db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun exportCsv():String {
        fun cell(s:String):String { val safe=if(s.firstOrNull() in listOf('=','+','-','@')) "'$s" else s;return "\""+safe.replace("\"","\"\"")+"\"" }
        val header=listOf("Location","SKU","Item","Quantity","Unit","Method","Counted at","Operator","Server confirmed")
        return "\uFEFF"+header.joinToString(",",transform=::cell)+"\r\n"+all("stock").joinToString("\r\n") { x ->
            listOf(x.s("location"),x.s("sku"),x.s("name"),x.optDouble("quantity").toString(),x.s("unit"),x.s("source"),
                x.s("counted_at"),x.s("operator"),x.optBoolean("server_confirmed").toString()).joinToString(",",transform=::cell)
        }
    }
}

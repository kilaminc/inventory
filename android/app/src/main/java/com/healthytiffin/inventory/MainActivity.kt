package com.healthytiffin.inventory

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Forest=Color(0xFF173F32)
private val Lime=Color(0xFFD8EF9C)
private val Cream=Color(0xFFF6F7F1)
private val Muted=Color(0xFF647269)
private val Amber=Color(0xFF8D570D)

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {MaterialTheme(colorScheme=lightColorScheme(primary=Forest,secondary=Color(0xFF526C43),
            background=Cream,surface=Color.White,onPrimary=Color.White)) {InventoryApp()}}
    }
}

@Composable fun InventoryApp() {
    val context=LocalContext.current;val store=remember {Store(context).apply {seed()}}
    var page by remember {mutableStateOf("Home")};var revision by remember {mutableIntStateOf(0)}
    var sessionId by remember {mutableStateOf<String?>(null)}
    var message by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    var editItem by remember {mutableStateOf<JSONObject?>(null)}
    val scope=rememberCoroutineScope()
    fun refresh(){revision++}
    fun sync(){if(busy)return;busy=true;scope.launch {
        message=try {SyncEngine.run(store)}catch(e:Exception){"Upload paused: "+e.message};busy=false;refresh()
    }}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {uri->
        if(uri!=null)runCatching {context.contentResolver.openOutputStream(uri)?.use {it.write(store.exportCsv().toByteArray())}}
            .onSuccess {message="Excel-compatible CSV saved."}.onFailure {message="Could not save export: "+it.message}
    }
    Scaffold(containerColor=Cream,topBar={
        Column(Modifier.fillMaxWidth().background(Cream).statusBarsPadding().padding(horizontal=22.dp,vertical=12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Forest),contentAlignment=Alignment.Center){
                    Icon(Icons.Outlined.Inventory2,null,tint=Lime)}
                Column(Modifier.padding(start=12.dp).weight(1f)) {
                    Text("HEALTHY TIFFIN",fontSize=10.sp,letterSpacing=2.sp,color=Muted,fontWeight=FontWeight.Bold)
                    Text("Inventory",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Forest)
                }
                Text("PILOT",fontSize=10.sp,color=Forest,modifier=Modifier.background(Lime,RoundedCornerShape(20.dp)).padding(9.dp,5.dp))
            }
        }
    },bottomBar={
        NavigationBar(containerColor=Color.White) {
            listOf("Home" to Icons.Outlined.Home,"Walk" to Icons.Outlined.CenterFocusStrong,
                "Items" to Icons.Outlined.Category,"Stock" to Icons.Outlined.Inventory2,"Setup" to Icons.Outlined.Settings).forEach {(name,icon)->
                NavigationBarItem(selected=page==name,onClick={page=name;refresh()},icon={Icon(icon,name)},label={Text(name,fontSize=11.sp)})
            }
        }
    }) {pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if(message.isNotBlank())Row(Modifier.fillMaxWidth().background(Color(0xFFE7EDDF)).padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                Text(message,Modifier.weight(1f),fontSize=12.sp,color=Forest)
                IconButton(onClick={message=""}){Icon(Icons.Outlined.Close,"Dismiss")}
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            key(page) {
                when(page) {
                    "Home"->Home(store,revision,onStart={page="Walk"},onResume={sessionId=it;page="Walk"},onSync={sync()},onReview={sessionId=it;page="Review"})
                    "Walk"->{val session=sessionId?.let {store.get("session",it)}
                        if(session==null || session.s("status")=="finished")StartWalk(store,revision,onStart={sessionId=it;refresh()},onResume={sessionId=it;refresh()})
                        else Walk(store,session,revision,onReview={page="Review"},onRefresh={refresh()},onMessage={message=it})}
                    "Review"->{val session=sessionId?.let {store.get("session",it)}
                        if(session==null)Text("Start a walk first.",Modifier.padding(24.dp))
                        else Review(store,session,revision,onBack={page="Walk"},onRefresh={refresh()},onFinish={
                            store.finishSession(session);UploadWorker.schedule(context);sessionId=null;page="Home";refresh();message="Shelf saved on phone. Upload will run when connected."})}
                    "Items"->Catalog(store,revision,onEdit={editItem=it},onNew={editItem=JSONObject().put("sku","HT-"+uid().take(8)).put("name","").put("category","Other")})
                    "Stock"->Stock(store,revision,onExport={export.launch("HT_Inventory.csv")},onSync={sync()})
                    "Setup"->Setup(store,onSync={sync()},onMessage={message=it})
                }
            }
        }
    }
    if(editItem!=null) ItemDialog(store,editItem!!,onDismiss={editItem=null},onSaved={editItem=null;refresh();UploadWorker.schedule(context)})
}

@Composable fun Heading(title:String,subtitle:String) {
    Text(title,fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=Forest)
    Text(subtitle,fontSize=14.sp,color=Muted,modifier=Modifier.padding(top=6.dp,bottom=18.dp))
}
@Composable fun Notice(text:String) {Text(text,fontSize=13.sp,color=Amber,modifier=Modifier.fillMaxWidth().background(Color(0xFFFFF2D9),RoundedCornerShape(12.dp)).padding(14.dp))}
@Composable fun Metric(number:String,label:String,modifier:Modifier=Modifier) {
    Card(modifier,colors=CardDefaults.cardColors(containerColor=Color.White)) {Column(Modifier.padding(18.dp)){
        Text(number,fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=Forest);Text(label,fontSize=12.sp,color=Muted)}}
}

@Composable fun Home(store:Store,revision:Int,onStart:()->Unit,onResume:(String)->Unit,onSync:()->Unit,onReview:(String)->Unit) {
    val sessions=remember(revision){store.all("session")};val captures=remember(revision){store.all("capture")}
    val pending=remember(revision){store.all("commit").count {!it.optBoolean("uploaded")}}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
        Heading("A clearer count.","Walk the shelves. Keep the evidence.")
        Card(colors=CardDefaults.cardColors(containerColor=Forest),shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Icon(Icons.Outlined.CenterFocusStrong,null,tint=Lime,modifier=Modifier.size(32.dp))
                Text("See it. Count it. Save it.",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=Color.White,modifier=Modifier.padding(top=16.dp))
                Text("Start with a shelf location. Clear views are captured automatically; you review the counts.",color=Color(0xFFCFDDD3),fontSize=14.sp,modifier=Modifier.padding(vertical=12.dp))
                Button(onClick=onStart,colors=ButtonDefaults.buttonColors(containerColor=Lime,contentColor=Forest),modifier=Modifier.fillMaxWidth().height(50.dp)){
                    Text("Start inventory walk",fontWeight=FontWeight.SemiBold);Spacer(Modifier.width(12.dp));Icon(Icons.Outlined.ArrowForward,null)}
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {Metric(store.items().size.toString(),"catalogue items",Modifier.weight(1f));Metric(pending.toString(),"shelves to upload",Modifier.weight(1f))}
        Spacer(Modifier.height(20.dp))
        if(!Api(store).ready)Notice("Ready for offline capture. Connect your backend in Setup for AI analysis and Google Sheets.")
        Text("YOUR WALKS",fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,color=Muted,modifier=Modifier.padding(top=24.dp,bottom=12.dp))
        if(sessions.isEmpty())Text("Your first shelf count will appear here.",fontSize=14.sp,color=Muted)
        sessions.take(8).forEach {s->
            Card(Modifier.fillMaxWidth().padding(bottom=8.dp).clickable {if(s.s("status")=="active")onResume(s.s("id"))},colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                    Icon(if(s.s("status")=="active")Icons.Outlined.PlayCircle else Icons.Outlined.CheckCircle,null,tint=Forest)
                    Column(Modifier.weight(1f).padding(start=12.dp)){
                        Text(s.s("location"),fontWeight=FontWeight.SemiBold,color=Forest)
                        Text(s.s("operator")+" · "+if(s.s("status")=="active")"In progress" else "Saved on phone",fontSize=12.sp,color=Muted)
                    }
                    if(s.s("status")=="active")IconButton(onClick={onReview(s.s("id"))}){Icon(Icons.Outlined.FactCheck,"Review")}
                }
            }
        }
        OutlinedButton(onClick=onSync,modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Icon(Icons.Outlined.Sync,null);Spacer(Modifier.width(8.dp));Text("Upload & sync")}
        Text("Photos stay on this device until you connect a backend. Unvisited stock is never set to zero.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=10.dp,bottom=12.dp))
    }
}

@Composable fun StartWalk(store:Store,revision:Int,onStart:(String)->Unit,onResume:(String)->Unit) {
    var location by remember {mutableStateOf("")};var operator by remember {mutableStateOf(store.prefs.getString("operator","")!!)}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
        Heading("Start a shelf.","Give each shelf a unique location so repeat views never become extra stock.")
        OutlinedTextField(location,{location=it},label={Text("Shelf location")},placeholder={Text("e.g. Dry storage / A1")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(operator,{operator=it},label={Text("Your name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Button(enabled=location.isNotBlank()&&operator.isNotBlank(),onClick={
            store.prefs.edit().putString("operator",operator.trim()).apply()
            onStart(store.startSession(location,operator).s("id"))
        },modifier=Modifier.fillMaxWidth().padding(vertical=20.dp).height(52.dp)){Text("Open camera")}
        Notice("Count one shelf at a time. Finish and review this shelf before moving to a new location.")
        val active=remember(revision){store.all("session").filter {it.s("status")=="active"}}
        if(active.isNotEmpty()) {
            Text("Resume a walk",fontSize=20.sp,color=Forest,modifier=Modifier.padding(top=22.dp,bottom=12.dp))
            active.forEach {s->OutlinedButton(onClick={onResume(s.s("id"))},modifier=Modifier.fillMaxWidth()){Text(s.s("location"))}}
        }
    }
}

@Composable fun Walk(store:Store,session:JSONObject,revision:Int,onReview:()->Unit,onRefresh:()->Unit,onMessage:(String)->Unit) {
    val context=LocalContext.current
    var permitted by remember {mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permitted=it}
    var auto by remember {mutableStateOf(true)};var request by remember {mutableIntStateOf(0)}
    var torch by remember {mutableStateOf(false)}
    var cameraStatus by remember {mutableStateOf("Opening camera…")}
    val captures=remember(revision){store.sessionCaptures(session.s("id"))}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=18.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){
            Text(session.s("location"),fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Forest)
            Text(session.s("operator")+" · "+captures.size+" photos",fontSize=12.sp,color=Muted)
        };IconButton(onClick={torch=!torch}){Icon(if(torch)Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,"Toggle light")};Text("Auto",fontSize=12.sp);Switch(auto,{auto=it})}
        Spacer(Modifier.height(10.dp))
        if(permitted)Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
            CameraFeed(store,auto,request,torch,Modifier.fillMaxSize(),onStatus={cameraStatus=it},onCaptured={id,file,text,codes->
                store.addCapture(session,file.absolutePath,text,codes,id);UploadWorker.schedule(context);onRefresh()
                codes.firstOrNull {it.startsWith("HTLOC:") && it.removePrefix("HTLOC:")!=session.s("location") }?.let {
                    onMessage("Location marker "+it.removePrefix("HTLOC:")+" spotted. Finish this shelf before starting that location.")}
            })
            Box(Modifier.align(Alignment.Center).fillMaxWidth(.78f).height(210.dp).border(1.dp,Color.White.copy(alpha=.7f),RoundedCornerShape(12.dp)))
            Text("FRAME THE LABELS AND PACKS",color=Color.White,fontSize=10.sp,letterSpacing=1.sp,
                modifier=Modifier.align(Alignment.TopCenter).padding(18.dp).background(Color.Black.copy(alpha=.4f),RoundedCornerShape(8.dp)).padding(8.dp))
        } else Card(Modifier.fillMaxWidth().height(240.dp)){Column(Modifier.padding(24.dp)){
            Text("Camera access is needed to capture visual evidence.",color=Forest)
            Button(onClick={permission.launch(Manifest.permission.CAMERA)},modifier=Modifier.padding(top=16.dp)){Text("Allow camera")}}}
        Text(cameraStatus,fontSize=13.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={request++},enabled=permitted,modifier=Modifier.weight(1f).height(50.dp)){Icon(Icons.Outlined.PhotoCamera,null);Spacer(Modifier.width(6.dp));Text("Capture")}
            Button(onClick=onReview,enabled=captures.isNotEmpty(),modifier=Modifier.weight(1f).height(50.dp)){Text("Review shelf")}
        }
        Text("RECENT EVIDENCE",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=1.sp,modifier=Modifier.padding(top=22.dp,bottom=10.dp))
        captures.takeLast(3).reversed().forEach {c->
            Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
                Photo(c.s("path"),Modifier.size(62.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.padding(start=12.dp).weight(1f)){
                    Text(if(c.s("ocr").isBlank())"Shelf photo" else c.s("ocr").replace('\n',' ').take(55),fontSize=13.sp,color=Forest,maxLines=2)
                    Text(statusLabel(c.s("status")),fontSize=11.sp,color=Muted)
                }
            }
        }
    }
}
fun statusLabel(s:String)=when(s){"saved_on_phone"->"Saved on phone";"needs_review"->"AI suggestions ready";"pending_configuration"->"AI connection needed";else->"Awaiting AI analysis"}

@Composable fun Photo(path:String,modifier:Modifier=Modifier) {
    val bitmap=remember(path){photoBitmap(path)}
    if(bitmap!=null)Image(bitmap.asImageBitmap(),"Evidence photo",modifier,contentScale=androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(Color(0xFFE2E8DD)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Image,null,tint=Muted)}
}

@Composable fun Review(store:Store,session:JSONObject,revision:Int,onBack:()->Unit,onRefresh:()->Unit,onFinish:()->Unit) {
    val captures=remember(revision){store.sessionCaptures(session.s("id"))}
    val lines=remember(revision){store.sessionLines(session.s("id"))}
    var choose by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<Pair<JSONObject,JSONObject?>?>(null)}
    var confirm by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf(captures.lastOrNull()?.s("id") ?: "")}
    val capture=captures.firstOrNull {it.s("id")==selected} ?: captures.lastOrNull()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Heading("Review this shelf.",session.s("location")+" · Confirm quantities before updating stock.")
        if(capture!=null) {
            Photo(capture.s("path"),Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(16.dp)))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                captures.forEachIndexed {index,c->FilterChip(selected==c.s("id"),onClick={selected=c.s("id")},label={Text("Photo ${index+1}")})}
            }
            val analysis=capture.optJSONObject("analysis")
            if(analysis==null || analysis.s("status")!="needs_review")Notice("This photo has no AI count yet. You can inspect it and add a count, or sync after connecting AI.")
            else {
                Text(analysis.s("scene_note"),fontSize=13.sp,color=Muted)
                analysis.optJSONArray("proposals")?.objects()?.forEach {p->
                    val item=store.get("item",p.s("sku"))
                    Card(Modifier.fillMaxWidth().padding(top=8.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                        Column(Modifier.padding(14.dp)){
                            Text(item?.s("name") ?: p.s("label"),fontWeight=FontWeight.SemiBold,color=Forest)
                            Text(p.s("evidence_text"),fontSize=12.sp,color=Muted)
                            val packs=p.number("visible_packs")
                            Text(if(packs!=null)"Suggested visible packs: ${packs.toInt()}" else "Quantity needs checking",fontSize=13.sp,color=Amber)
                            if(p.s("question").isNotBlank())Text(p.s("question"),fontSize=12.sp,color=Amber)
                            TextButton(onClick={if(item!=null)editing=item to p else choose=true}){Text("Check & enter final quantity")}
                        }
                    }
                }
            }
            val candidates=analysis?.optJSONArray("candidates")?.objects() ?: localCandidates(store,capture)
            if(candidates.isNotEmpty()) {
                Text("LABEL MATCHES — IDENTITY ONLY",fontSize=10.sp,color=Muted,modifier=Modifier.padding(top=15.dp))
                candidates.take(4).forEach {item->TextButton(onClick={editing=item to null}){Text(item.s("name"),maxLines=1)}}
            }
        }
        OutlinedButton(onClick={choose=true},enabled=captures.isNotEmpty(),modifier=Modifier.fillMaxWidth().padding(vertical=12.dp)) {Icon(Icons.Outlined.Add,null);Text("Add / correct an item")}
        Text("FINAL SHELF COUNTS",fontSize=11.sp,letterSpacing=1.sp,fontWeight=FontWeight.Bold,color=Muted)
        if(lines.isEmpty())Text("No confirmed quantities yet.",fontSize=14.sp,color=Muted,modifier=Modifier.padding(vertical=14.dp))
        lines.forEach {line->
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f).clickable {editing=(store.get("item",line.s("sku")) ?: JSONObject()) to line}) {
                    Text(line.s("name"),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Forest)
                    Text("${line.optDouble("quantity")} ${line.s("unit")} · ${line.s("source").replace('_',' ')}",fontSize=12.sp,color=Muted)
                }
                IconButton(onClick={store.remove("line",line.s("id"));onRefresh()}){Icon(Icons.Outlined.Close,"Remove count")}
            }
        }
        Button(onClick={confirm=true},enabled=lines.isNotEmpty(),modifier=Modifier.fillMaxWidth().height(52.dp).padding(top=4.dp)){Text("Finish shelf · ${lines.size} items")}
        TextButton(onClick=onBack,modifier=Modifier.fillMaxWidth()){Text("Back to camera")}
    }
    if(choose)ItemPicker(store,onDismiss={choose=false},onChoose={choose=false;editing=it to null})
    if(editing!=null && capture!=null)CountDialog(store,session,editing!!.first,editing!!.second,capture,lines,
        onDismiss={editing=null},onSaved={editing=null;onRefresh()})
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Save this shelf count?")},text={Text("These ${lines.size} quantities replace previous counts for the same items at ${session.s("location")}. Items you did not count stay unchanged. Confirm you checked all visible packs and any hidden stock for each entered quantity.")},
        confirmButton={TextButton(onClick={confirm=false;onFinish()}){Text("Confirm & save")}},dismissButton={TextButton(onClick={confirm=false}){Text("Keep reviewing")}})
}

fun localCandidates(store:Store,capture:JSONObject):List<JSONObject> {
    val words=capture.s("ocr").lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>2}.toSet()
    val codes=capture.optJSONArray("barcodes")?.let {a->(0 until a.length()).map {a.optString(it)}} ?: emptyList()
    return store.items().map {item->
        val tokens=item.s("name").lowercase().split(Regex("[^a-z0-9]+")).filter {it.length>2}
        val score=if(item.s("barcode").isNotBlank() && item.s("barcode") in codes)100.0 else tokens.count {it in words}.toDouble()/tokens.size.coerceAtLeast(1)
        item to score
    }.filter {it.second>=.5}.sortedByDescending {it.second}.take(5).map {it.first}
}

@Composable fun CountDialog(store:Store,session:JSONObject,item:JSONObject,proposal:JSONObject?,capture:JSONObject,lines:List<JSONObject>,onDismiss:()->Unit,onSaved:()->Unit) {
    val old=lines.firstOrNull {it.s("sku")==item.s("sku")}
    val suggested=proposal?.number("visible_packs")?.let {n->
        val size=item.number("pack_size");if(size!=null && item.s("pack_unit")==item.s("unit"))n*size
        else if(item.s("unit").lowercase() in listOf("pcs","packs","bags","boxes","cases"))n else null
    }
    var quantity by remember {mutableStateOf(old?.number("quantity")?.toString() ?: suggested?.toString() ?: "")}
    var unit by remember {mutableStateOf(old?.s("unit") ?: item.s("unit"))}
    var source by remember {mutableStateOf(old?.s("source") ?: "visual_confirmed")}
    var note by remember {mutableStateOf(old?.s("note") ?: "")};var speechText by remember {mutableStateOf("")}
    val context=LocalContext.current
    val voice=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){r->
        if(r.resultCode==Activity.RESULT_OK){val text=r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            speechText=text;val parsed=VoiceQuantity.parse(text)
            if(parsed!=null){quantity=parsed.toString();source="operator_reported";note="Voice clarification: $text"}
            else note=text
        }
    }
    val valid=quantity.toDoubleOrNull()?.let {it.isFinite()&&it>=0&&it<=1e9}==true && unit.isNotBlank()
    AlertDialog(onDismissRequest=onDismiss,title={Text(item.s("name"),fontSize=19.sp)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Enter the TOTAL for this SKU at this shelf. A new view replaces this quantity; it does not add to it.",fontSize=13.sp,color=Muted)
        if(old!=null)Notice("Already recorded: ${old.optDouble("quantity")} ${old.s("unit")}. This will replace it.")
        OutlinedTextField(quantity,{quantity=it},label={Text("Final quantity")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().padding(top=10.dp))
        OutlinedTextField(unit,{unit=it},label={Text("Unit (kg, L, pcs, boxes…)")},enabled=item.s("unit").isBlank(),modifier=Modifier.fillMaxWidth().padding(top=8.dp))
        listOf("visual_confirmed" to "Counted from visible packs","operator_reported" to "Reported by operator","scale_measured" to "Read from a scale").forEach {(id,label)->
            Row(Modifier.fillMaxWidth().clickable {source=id},verticalAlignment=Alignment.CenterVertically){RadioButton(source==id,{source=id});Text(label,fontSize=12.sp)}
        }
        OutlinedTextField(note,{note=it},label={Text("Note / measurement details")},modifier=Modifier.fillMaxWidth())
        TextButton(onClick={
            try {voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT,"Say the final quantity for "+item.s("name")))}
            catch(_:Exception){speechText="Speech recognition is unavailable on this device. Enter the quantity above."}
        }){Icon(Icons.Outlined.Mic,null);Text("Speak a correction")}
        if(speechText.isNotBlank())Text(speechText,fontSize=12.sp,color=Muted)
        Text("Evidence: selected shelf photo. Spoken amounts are recorded as operator-reported.",fontSize=11.sp,color=Muted)
    }},confirmButton={TextButton(enabled=valid,onClick={
        if(item.s("unit").isBlank()){item.put("unit",unit.trim()).put("dirty",true);store.put("item",item.s("sku"),item)}
        store.saveLine(session.s("id"),item,quantity.toDouble(),unit,source,listOf(capture.s("id")),note);onSaved()
    }){Text("Save final quantity")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable fun ItemPicker(store:Store,onDismiss:()->Unit,onChoose:(JSONObject)->Unit) {
    var query by remember {mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Choose the exact item")},text={Column {
        OutlinedTextField(query,{query=it},placeholder={Text("Search name or SKU")},singleLine=true)
        LazyColumn(Modifier.heightIn(max=320.dp)) {
            items(store.items().filter {it.s("name").contains(query,true)||it.s("sku").contains(query,true)}.take(70)){i->
                TextButton(onClick={onChoose(i)},modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){
                    Text(i.s("name"),fontSize=13.sp);Text(i.s("sku"),fontSize=10.sp,color=Muted)}}
            }
        }
    }},confirmButton={},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable fun Catalog(store:Store,revision:Int,onEdit:(JSONObject)->Unit,onNew:()->Unit) {
    var query by remember {mutableStateOf("")};val all=remember(revision){store.items()}
    Column(Modifier.padding(horizontal=20.dp)) {
        Heading("Know your ingredients.","Teach barcodes and pack sizes once. Existing SKUs are preserved.")
        OutlinedTextField(query,{query=it},placeholder={Text("Search ${all.size} items")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick=onNew){Icon(Icons.Outlined.Add,null);Text("Teach a new item")}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(all.filter {it.s("name").contains(query,true)||it.s("category").contains(query,true)},key={it.s("sku")}) {item->
                Card(Modifier.fillMaxWidth().clickable {onEdit(item)},colors=CardDefaults.cardColors(containerColor=Color.White)){
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)) {Text(item.s("name"),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Forest)
                            Text(item.s("category")+" · "+item.s("sku"),fontSize=11.sp,color=Muted)
                            Text(if(item.s("barcode").isNotBlank())"Barcode taught" else "Barcode / pack size needed",fontSize=11.sp,color=if(item.s("barcode").isNotBlank())Forest else Amber)}
                        Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
                    }
                }
            }
        }
    }
}

@Composable fun ItemDialog(store:Store,item:JSONObject,onDismiss:()->Unit,onSaved:()->Unit) {
    var name by remember {mutableStateOf(item.s("name"))};var unit by remember {mutableStateOf(item.s("unit"))}
    var barcode by remember {mutableStateOf(item.s("barcode"))};var pack by remember {mutableStateOf(item.number("pack_size")?.toString() ?: "")}
    var packUnit by remember {mutableStateOf(item.s("pack_unit"))};var category by remember {mutableStateOf(item.s("category"))}
    var ref by remember {mutableStateOf(item.s("reference_photo"))};var error by remember {mutableStateOf("")}
    val photos=store.all("capture").take(10)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Teach item",fontSize=22.sp)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(item.s("sku"),fontSize=11.sp,color=Muted)
        OutlinedTextField(name,{name=it},label={Text("Item name")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(category,{category=it},label={Text("Category")},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
        OutlinedTextField(unit,{unit=it},label={Text("Stock unit")},placeholder={Text("kg, L, pcs, boxes")},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
        OutlinedTextField(barcode,{barcode=it},label={Text("Barcode")},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
        OutlinedTextField(pack,{pack=it},label={Text("Verified amount per sealed pack")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().padding(top=6.dp))
        OutlinedTextField(packUnit,{packUnit=it},label={Text("Pack amount unit")},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
        Text("Use a recent capture as the reference photo. Tap it to attach and copy a detected barcode.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            photos.forEach {c->Photo(c.s("path"),Modifier.size(70.dp).clip(RoundedCornerShape(8.dp)).border(if(ref==c.s("id"))3.dp else 0.dp,Forest,RoundedCornerShape(8.dp)).clickable {
                ref=c.s("id");c.optJSONArray("barcodes")?.let {a->if(a.length()>0)barcode=a.optString(0)}
            })}
        }
        if(photos.isEmpty())Text("Capture the package in Walk Mode first, then attach it here.",fontSize=12.sp,color=Amber)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    }},confirmButton={TextButton(enabled=name.isNotBlank()&&unit.isNotBlank(),onClick={
        val size=pack.toDoubleOrNull()
        if(pack.isNotBlank() && (size==null || !size.isFinite() || size<=0 || packUnit.isBlank())){error="Enter a positive pack size and its unit.";return@TextButton}
        if(barcode.isNotBlank() && store.items().any {it.s("sku")!=item.s("sku")&&it.s("barcode")==barcode}){error="This barcode belongs to another SKU.";return@TextButton}
        item.put("name",name.trim()).put("unit",unit.trim()).put("category",category.trim()).put("barcode",barcode.trim())
            .put("pack_size",size ?: JSONObject.NULL).put("pack_unit",packUnit.trim()).put("reference_photo",ref).put("dirty",true)
        store.put("item",item.s("sku"),item);onSaved()
    }){Text("Save item")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable fun Stock(store:Store,revision:Int,onExport:()->Unit,onSync:()->Unit) {
    var query by remember {mutableStateOf("")};val stock=remember(revision){store.all("stock")}
    Column(Modifier.padding(horizontal=20.dp)) {
        Heading("Stock, with evidence.","Latest confirmed shelf counts. Unvisited items remain uncounted.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=onExport,enabled=stock.isNotEmpty(),modifier=Modifier.weight(1f)){Icon(Icons.Outlined.Download,null);Text("Export CSV")}
            Button(onClick=onSync,modifier=Modifier.weight(1f)){Icon(Icons.Outlined.Sync,null);Text("Sync")}
        }
        OutlinedTextField(query,{query=it},placeholder={Text("Search item or location")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=10.dp))
        if(stock.isEmpty())Notice("Finish your first shelf to see stock here. Historical workbook quantities were not imported as current counts.")
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(stock.filter {it.s("name").contains(query,true)||it.s("location").contains(query,true)}) {s->
                Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)){
                    Column(Modifier.padding(16.dp)) {
                        Text(s.s("name"),fontWeight=FontWeight.SemiBold,color=Forest)
                        Text("${s.optDouble("quantity")} ${s.s("unit")}",fontSize=25.sp,color=Forest,modifier=Modifier.padding(vertical=5.dp))
                        Text(s.s("location")+" · "+s.s("counted_at").take(16).replace('T',' ')+" UTC",fontSize=11.sp,color=Muted)
                        Text(if(s.optBoolean("server_confirmed"))"Uploaded to backend" else "Saved on phone · upload pending",fontSize=11.sp,color=if(s.optBoolean("server_confirmed"))Forest else Amber)
                    }
                }
            }
        }
    }
}

@Composable fun Setup(store:Store,onSync:()->Unit,onMessage:(String)->Unit) {
    var url by remember {mutableStateOf(store.prefs.getString("url","")!!)}
    var token by remember {mutableStateOf(store.prefs.getString("token","")!!)}
    Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
        Heading("Connect once.","Employees can count without signing in. This phone connects to your inventory backend.")
        OutlinedTextField(url,{url=it},label={Text("Backend HTTPS address")},placeholder={Text("https://your-app.onrender.com")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(token,{token=it},label={Text("Device connection code")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().padding(top=12.dp))
        Button(onClick={
            if(url.isNotBlank() && !(url.startsWith("https://")||url.startsWith("http://10.0.2.2:")||url.startsWith("http://localhost:"))) {
                onMessage("Enter an HTTPS backend address.")
            }else {store.prefs.edit().putString("url",url.trim().trimEnd('/')).putString("token",token.trim()).apply();onSync()}
        },modifier=Modifier.fillMaxWidth().padding(vertical=18.dp).height(50.dp)){Text("Save & test connection")}
        val status=runCatching {JSONObject(store.prefs.getString("server_status","{}")!!)}.getOrDefault(JSONObject())
        Card(colors=CardDefaults.cardColors(containerColor=Color.White)) {Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Connection status",fontWeight=FontWeight.SemiBold,color=Forest)
            Text("AI analysis: "+if(status.optBoolean("vision_ready"))"configured" else "not connected",fontSize=14.sp,modifier=Modifier.padding(top=12.dp))
            Text("Google Sheets: "+if(status.optBoolean("sheets_ready"))"configured" else "not connected",fontSize=14.sp,modifier=Modifier.padding(top=8.dp))
            Text("Last upload: "+(store.prefs.getString("last_sync","")!!.take(16).ifBlank {"none"}),fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
        }}
        Spacer(Modifier.height(18.dp))
        Notice("API keys and Google credentials belong on the backend. Never enter them as the phone’s connection code.")
        Text("HT Inventory · 0.1.0 pilot\nKeep this app installed to retain local photos and pending uploads. Export and sync before replacing the device.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=20.dp))
    }
}

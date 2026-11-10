package com.healthytiffin.inventory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

fun photoBitmap(path:String):Bitmap? = runCatching {
    val options=BitmapFactory.Options().apply {inSampleSize=2}
    val b=BitmapFactory.decodeFile(path,options) ?: return@runCatching null
    val exif=ExifInterface(path);val angle=when(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)) {6->90f;3->180f;8->270f;else->0f}
    if(angle==0f)b else Bitmap.createBitmap(b,0,0,b.width,b.height,Matrix().apply {postRotate(angle)},true)
}.getOrNull()

@Composable
fun CameraFeed(store:Store,auto:Boolean,captureRequest:Int,torch:Boolean=false,modifier:Modifier=Modifier,
               onStatus:(String)->Unit,onCaptured:(String,File,String,List<String>)->Unit) {
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    val preview=remember {PreviewView(context).apply {scaleType=PreviewView.ScaleType.FILL_CENTER}}
    val executor=remember {Executors.newSingleThreadExecutor()}
    val capture=remember {ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()}
    val active=remember {AtomicBoolean(true)};val busy=remember {AtomicBoolean(false)}
    val latestAuto by rememberUpdatedState(auto)
    val latestStatus by rememberUpdatedState(onStatus)
    val latestCaptured by rememberUpdatedState(onCaptured)
    var bound by remember {mutableStateOf(false)}
    var cameraControl by remember {mutableStateOf<CameraControl?>(null)}
    val main=remember {ContextCompat.getMainExecutor(context)}
    fun snap() {
        if(!bound || !active.get() || !busy.compareAndSet(false,true))return
        latestStatus("Saving a clear shelf photo…")
        val id=uid();val file=store.evidenceFile(id)
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object:ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                var text="";var codes=emptyList<String>()
                try {
                    val image=InputImage.fromFilePath(context,Uri.fromFile(file))
                    val reader=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val scanner=BarcodeScanning.getClient()
                    try {text=Tasks.await(reader.process(image)).text;codes=Tasks.await(scanner.process(image)).mapNotNull {it.rawValue}}
                    finally {reader.close();scanner.close()}
                }catch(_:Exception){}
                main.execute {busy.set(false);latestCaptured(id,file,text,codes);if(active.get())latestStatus("Photo saved. Move to the next view.")}
            }
            override fun onError(error:ImageCaptureException) {
                main.execute {busy.set(false);latestStatus("Capture failed. Hold steady and try again.")}
            }
        })
    }
    val latestSnap by rememberUpdatedState(::snap)
    LaunchedEffect(captureRequest) {if(captureRequest>0)latestSnap()}
    LaunchedEffect(torch,cameraControl) {cameraControl?.enableTorch(torch)}
    DisposableEffect(owner) {
        active.set(true)
        val future=ProcessCameraProvider.getInstance(context)
        var provider:ProcessCameraProvider?=null
        var previous:IntArray?=null;var lastSaved:IntArray?=null
        var steady=0;var lastChecked=0L;var lastCapture=0L
        val analyzer=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analyzer.setAnalyzer(executor) { image ->
            try {
                val time=System.currentTimeMillis()
                if(time-lastChecked<400 || !active.get())return@setAnalyzer
                lastChecked=time
                val p=image.planes[0];val b=p.buffer;val grid=IntArray(256)
                for(y in 0..15)for(x in 0..15) {
                    val px=(image.width*(x+.5)/16).toInt();val py=(image.height*(y+.5)/16).toInt()
                    grid[y*16+x]=b.get(py*p.rowStride+px*p.pixelStride).toInt() and 255
                }
                val brightness=grid.average();val diff=previous?.let {g->grid.indices.sumOf {abs(grid[it]-g[it])}/256.0} ?: 100.0
                val detail=(0 until 255).sumOf {abs(grid[it]-grid[it+1])}/255.0
                steady=if(diff<5 && brightness>25 && detail>2)steady+1 else 0
                val changed=lastSaved?.let {g->grid.indices.sumOf {abs(grid[it]-g[it])}/256.0>8} ?: true
                if(latestAuto && steady>=4 && time-lastCapture>9000 && changed && !busy.get()) {
                    lastSaved=grid;lastCapture=time;steady=0;main.execute {latestSnap()}
                }else if(brightness<25)main.execute {if(!busy.get())latestStatus("More light needed. Use the light button or move closer.")}
                previous=grid
            }finally {image.close()}
        }
        future.addListener({
            if(active.get())try {
                provider=future.get();val p=Preview.Builder().build().also {it.surfaceProvider=preview.surfaceProvider}
                provider?.unbindAll();cameraControl=provider?.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,p,capture,analyzer)?.cameraControl
                bound=true;latestStatus("Aim at a shelf and pause. Clear views save automatically.")
            }catch(_:Exception){latestStatus("Camera unavailable. Check permission or close other camera apps.")}
        },main)
        onDispose {active.set(false);bound=false;analyzer.clearAnalyzer();provider?.unbindAll();executor.shutdown()}
    }
    AndroidView(factory={preview},modifier=modifier)
}

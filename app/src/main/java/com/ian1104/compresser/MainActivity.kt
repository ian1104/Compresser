package com.ian1104.compresser

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val selectedUris = mutableListOf<Uri>()
    private lateinit var selectedText: TextView
    private lateinit var qualityBar: SeekBar
    private lateinit var qualityValue: TextView
    private lateinit var detailText: TextView
    private lateinit var estimateText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var shareButton: Button
    private var workId: java.util.UUID? = null
    private var lastOutputUris: Array<String> = emptyArray()

    private val bg = Color.rgb(13,15,18)
    private val card = Color.rgb(23,26,31)
    private val card2 = Color.rgb(29,33,39)
    private val text = Color.rgb(242,244,247)
    private val muted = Color.rgb(157,165,176)
    private val accent = Color.rgb(105,170,255)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) applySelectedUris(uris) }
    private val mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) showRecentVideos() else picker.launch(arrayOf("video/*")) }
    data class VideoItem(val uri: Uri,val name: String,val size: Long,val addedSeconds: Long,val id: Long)

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); window.statusBarColor=bg; window.navigationBarColor=bg; if(Build.VERSION.SDK_INT>=23) window.decorView.systemUiVisibility=0; if(Build.VERSION.SDK_INT>=33) ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),10); buildUi() }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun label(v:String,s:Float=13f)=TextView(this).apply{text=v;textSize=s;setTextColor(muted)}
    private fun cardBackground(c:Int=card)=GradientDrawable().apply{setColor(c);cornerRadius=dp(18).toFloat()}

    private fun buildUi(){
        val scroll=ScrollView(this).apply{setBackgroundColor(bg);isFillViewport=true}
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(28))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(18))}
        header.addView(TextView(this).apply{text="Compresser";textSize=30f;setTextColor(this@MainActivity.text);typeface=android.graphics.Typeface.DEFAULT_BOLD})
        header.addView(TextView(this).apply{text="빠르고 간단한 영상 압축";textSize=14f;setTextColor(muted);setPadding(0,dp(4),0,0)});root.addView(header)
        val source=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBackground();setPadding(dp(16),dp(16),dp(16),dp(16))};source.addView(label("VIDEO",11f))
        source.addView(Button(this).apply{text="영상 선택 · 최근 추가";textSize=15f;setTextColor(this@MainActivity.text);background=cardBackground(card2);setOnClickListener{requestVideoAccessAndShowPicker()}},LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(10)})
        selectedText=TextView(this).apply{text="선택한 영상이 없습니다.";textSize=13f;setTextColor(muted);setPadding(dp(2),dp(12),dp(2),0);maxLines=4};source.addView(selectedText);root.addView(source,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        val qc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBackground();setPadding(dp(16),dp(16),dp(16),dp(16))};val qh=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};qh.addView(label("압축 강도",16f),LinearLayout.LayoutParams(0,-2,1f));qualityValue=TextView(this).apply{text="50 / 100";textSize=18f;setTextColor(accent);gravity=Gravity.CENTER;typeface=android.graphics.Typeface.DEFAULT_BOLD};qh.addView(qualityValue,LinearLayout.LayoutParams(dp(90),-2));qc.addView(qh)
        qualityBar=SeekBar(this).apply{max=99;progress=49;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){updateQualityText(p+1);updateEstimate()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){} })};qc.addView(qualityBar,LinearLayout.LayoutParams(-1,dp(48)))
        detailText=TextView(this).apply{textSize=12f;setTextColor(muted);setLineSpacing(0f,1.15f)};qc.addView(detailText);root.addView(qc,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        val oc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBackground();setPadding(dp(16),dp(16),dp(16),dp(16))};oc.addView(label("OPTIONS",11f));resolutionSpinner=makeSpinner(arrayOf("해상도 · 자동","480p","720p","1080p","최대 4K")){updateEstimate()};codecSpinner=makeSpinner(arrayOf("H.264 · 호환성 우선","H.265/HEVC · 용량 우선")){};oc.addView(resolutionSpinner,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(10)});oc.addView(codecSpinner,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});root.addView(oc,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        val rc=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBackground();setPadding(dp(16),dp(16),dp(16),dp(16))};rc.addView(label("RESULT",11f));estimateText=TextView(this).apply{textSize=13f;setTextColor(this@MainActivity.text);setLineSpacing(0f,1.15f)};rc.addView(estimateText);progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100};rc.addView(progress,LinearLayout.LayoutParams(-1,dp(8)).apply{topMargin=dp(14)});progressText=TextView(this).apply{text="0%";textSize=12f;setTextColor(accent);gravity=Gravity.END};rc.addView(progressText);statusText=TextView(this).apply{text="대기 중";textSize=12f;setTextColor(muted);maxLines=3};rc.addView(statusText);root.addView(rc,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        val actions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};startButton=makeButton("압축 시작",accent,Color.rgb(10,16,24)){startCompression()};startButton.isEnabled=false;cancelButton=makeButton("작업 취소",card2,text){workId?.let{WorkManager.getInstance(this@MainActivity).cancelWorkById(it)}};cancelButton.isEnabled=false;shareButton=makeButton("완료 파일 공유",card2,text){shareResults()};shareButton.isEnabled=false;actions.addView(startButton,LinearLayout.LayoutParams(-1,dp(52)));actions.addView(cancelButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});actions.addView(shareButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});root.addView(actions);scroll.addView(root);setContentView(scroll);updateQualityText(50)
    }

    private fun requestVideoAccessAndShowPicker(){if(Build.VERSION.SDK_INT>=33){if(checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED)showRecentVideos()else mediaPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO)}else if(Build.VERSION.SDK_INT>=23){if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED)showRecentVideos()else ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),11)}else showRecentVideos()}
    override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==11&&g.firstOrNull()==PackageManager.PERMISSION_GRANTED)showRecentVideos()else if(r==11)picker.launch(arrayOf("video/*"))}

    private fun showRecentVideos(){
        val videos=runCatching{loadRecentVideos()}.getOrElse{Toast.makeText(this,"영상 목록을 불러오지 못했습니다.",Toast.LENGTH_SHORT).show();picker.launch(arrayOf("video/*"));return}
        if(videos.isEmpty()){Toast.makeText(this,"기기에 영상이 없습니다.",Toast.LENGTH_SHORT).show();picker.launch(arrayOf("video/*"));return}
        val dialog=Dialog(this);val checkedUris=selectedUris.toMutableSet()
        val outer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=cardBackground(bg);setPadding(dp(16),dp(14),dp(16),dp(16))}
        val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};bar.addView(TextView(this).apply{text="최근 추가된 영상";textSize=20f;setTextColor(this@MainActivity.text);typeface=android.graphics.Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,1f));bar.addView(TextView(this).apply{text="✕";textSize=22f;setTextColor(muted);gravity=Gravity.CENTER;setOnClickListener{dialog.dismiss()}},LinearLayout.LayoutParams(dp(44),dp(44)));outer.addView(bar)
        outer.addView(TextView(this).apply{text="최근 추가 순 · ${videos.size}개 · 썸네일";textSize=12f;setTextColor(muted);setPadding(0,0,0,dp(10))})
        val search=EditText(this).apply{hint="영상 이름 검색";setHintTextColor(muted);setTextColor(this@MainActivity.text);textSize=14f;singleLine=true;background=cardBackground(card2);setPadding(dp(14),0,dp(14),0)};outer.addView(search,LinearLayout.LayoutParams(-1,dp(48)).apply{bottomMargin=dp(10)})
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val scroll=ScrollView(this).apply{addView(list)};outer.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val bottom=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val count=TextView(this).apply{text="${checkedUris.size}개 선택";textSize=13f;setTextColor(muted)};bottom.addView(count,LinearLayout.LayoutParams(0,dp(48),1f));bottom.addView(makeButton("선택",accent,Color.rgb(10,16,24)){val picked=videos.filter{checkedUris.contains(it.uri)}.map{it.uri};if(picked.isEmpty())Toast.makeText(this,"영상을 선택해주세요.",Toast.LENGTH_SHORT).show()else{applySelectedUris(picked);dialog.dismiss()}},LinearLayout.LayoutParams(dp(100),dp(48)));outer.addView(bottom)
        fun rebuild(q:String){list.removeAllViews();videos.filter{it.name.contains(q,ignoreCase=true)}.forEach{v->list.addView(videoRow(v,checkedUris,count))}}
        search.addTextChangedListener(SimpleTextWatcher{rebuild(search.text.toString())});rebuild("")
        dialog.setContentView(outer);dialog.setOnShowListener{dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94).toInt(),(resources.displayMetrics.heightPixels*.88).toInt())};dialog.show();dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94).toInt(),(resources.displayMetrics.heightPixels*.88).toInt())
    }

    private fun videoRow(v:VideoItem,checked:MutableSet<Uri>,count:TextView):View{
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=cardBackground(card);setPadding(dp(8),dp(7),dp(8),dp(7))};val thumb=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;setImageResource(android.R.drawable.ic_media_play);background=cardBackground(card2)};row.addView(thumb,LinearLayout.LayoutParams(dp(104),dp(70)))
        val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(4),0)};info.addView(TextView(this).apply{text=v.name;textSize=14f;setTextColor(this@MainActivity.text);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END});info.addView(TextView(this).apply{text="${formatBytes(v.size)}  ·  ${formatAddedDate(v.addedSeconds)}";textSize=11f;setTextColor(muted);setPadding(0,dp(5),0,0)});row.addView(info,LinearLayout.LayoutParams(0,-2,1f))
        val cb=CheckBox(this).apply{buttonTintList=android.content.res.ColorStateList.valueOf(accent);isChecked=checked.contains(v.uri)};row.addView(cb,LinearLayout.LayoutParams(dp(48),dp(48)));fun sync(){if(cb.isChecked)checked.add(v.uri)else checked.remove(v.uri);count.text="${checked.size}개 선택"};cb.setOnCheckedChangeListener{_,_->sync()};row.setOnClickListener{cb.isChecked=!cb.isChecked};
        lifecycleScope.launch(Dispatchers.IO){val b=loadVideoThumbnail(v);withContext(Dispatchers.Main){if(b!=null&&row.parent!=null)thumb.setImageBitmap(b)}};return row
    }
    private fun loadVideoThumbnail(v:VideoItem)=runCatching{if(Build.VERSION.SDK_INT>=29)contentResolver.loadThumbnail(v.uri,Size(dp(208),dp(140)),null)else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(contentResolver,v.id,MediaStore.Video.Thumbnails.MINI_KIND,null)}.getOrNull()
    private fun loadRecentVideos():List<VideoItem>{val c=if(Build.VERSION.SDK_INT>=29)MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)else MediaStore.Video.Media.EXTERNAL_CONTENT_URI;val p=arrayOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.DATE_ADDED);val out=mutableListOf<VideoItem>();contentResolver.query(c,p,null,null,"${MediaStore.Video.Media.DATE_ADDED} DESC")?.use{x->val id=x.getColumnIndexOrThrow(MediaStore.Video.Media._ID);val n=x.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);val s=x.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);val a=x.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);while(x.moveToNext()&&out.size<100){val i=x.getLong(id);out+=VideoItem(Uri.withAppendedPath(c,i.toString()),x.getString(n)? :"video",x.getLong(s),x.getLong(a),i)}};return out}
    private fun formatAddedDate(s:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.KOREA).format(Date(s*1000L))
    private fun applySelectedUris(u:List<Uri>){selectedUris.clear();selectedUris.addAll(u);selectedText.text="${u.size}개 선택됨  ·  ${formatBytes(u.sumOf{querySize(it)})}\n"+u.take(3).joinToString("\n"){"• ${displayName(it)}"}+if(u.size>3)"\n• 외 ${u.size-3}개"else"";updateEstimate();startButton.isEnabled=true}
    private fun makeSpinner(items:Array<String>,action:()->Unit)=Spinner(this).apply{background=cardBackground(card2);val a=object:ArrayAdapter<String>(this@MainActivity,android.R.layout.simple_spinner_item,items){override fun getView(p:Int,c:View?,parent:ViewGroup)=TextView(this@MainActivity).apply{text=getItem(p);textSize=14f;setTextColor(this@MainActivity.text);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(14),0)}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);adapter=a;setOnItemSelectedListener(SimpleItemSelected{action()})}
    private fun makeButton(v:String,bgColor:Int,fg:Int,action:()->Unit)=Button(this).apply{text=v;textSize=14f;setTextColor(fg);background=cardBackground(bgColor);isAllCaps=false;setOnClickListener{action()}}
    private fun updateQualityText(v:Int){val q=v.coerceIn(1,100);qualityValue.text="$q / 100";val ratio=15.0+(q-1)*75.0/99.0;val res=when{q<=20->"480p급 이하로 적극 축소";q<=50->"720p급까지 허용";q<=80->"1080p급까지 허용";else->"원본 해상도 우선, 최대 4K"};val br=when{q<=20->"약 0.35~1.5 Mbps";q<=50->"약 0.8~4 Mbps";q<=80->"약 2~8 Mbps";else->"약 4~16 Mbps"};val qu=when{q<=20->"화질 손실 큼 · 용량 최소화";q<=50->"화질/용량 균형";q<=80->"화질 우선";else->"화질 손실 최소화"};detailText.text="목표 용량 약 ${ratio.toInt()}%  ·  $qu\n해상도: $res  ·  비트레이트: $br\n오디오는 기본 AAC 계열 처리 · 실제 결과는 영상에 따라 달라짐"}
    private fun updateEstimate(){if(!::estimateText.isInitialized||selectedUris.isEmpty())return;val original=selectedUris.sumOf{querySize(it)};val q=qualityBar.progress+1;val ratio=(15.0+(q-1)*75.0/99.0)/100.0;val est=(original*ratio).toLong();estimateText.text="예상 결과  ${formatBytes(est)}  ·  약 ${(max(0.0,(1-ratio)*100)).toInt()}% 감소\n1-pass 인코딩 기준 예상치이며 실제 크기는 영상 내용에 따라 달라집니다."}
    private fun startCompression(){if(selectedUris.isEmpty())return;val r=OneTimeWorkRequestBuilder<CompressionWorker>().setInputData(workDataOf(CompressionWorker.KEY_URIS to selectedUris.map(Uri::toString).toTypedArray(),CompressionWorker.KEY_QUALITY to qualityBar.progress+1,CompressionWorker.KEY_RESOLUTION to resolutionSpinner.selectedItemPosition,CompressionWorker.KEY_CODEC to codecSpinner.selectedItemPosition)).build();workId=r.id;WorkManager.getInstance(this).enqueue(r);startButton.isEnabled=false;cancelButton.isEnabled=true;shareButton.isEnabled=false;observeWork(r.id)}
    private fun observeWork(id:java.util.UUID){WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this){i->if(i==null)return@observe;val p=i.progress.getInt("progress",0).coerceIn(0,100);progress.progress=p;progressText.text="$p%";val f=i.progress.getString("file")?:"";val lp=i.progress.getInt("fileProgress",p);statusText.text=when(i.state){WorkInfo.State.ENQUEUED->"대기 중…";WorkInfo.State.RUNNING->if(f.isBlank())"압축 준비 중…"else"압축 중 · $f · $lp%";WorkInfo.State.SUCCEEDED->{lastOutputUris=i.outputData.getStringArray(CompressionWorker.KEY_OUTPUT_URIS)?:emptyArray();shareButton.isEnabled=lastOutputUris.isNotEmpty();val b=i.outputData.getLong(CompressionWorker.KEY_ORIGINAL_BYTES,0);val a=i.outputData.getLong(CompressionWorker.KEY_OUTPUT_BYTES,0);if(b>0&&a>0)"완료  ·  ${formatBytes(b)} → ${formatBytes(a)}  ·  약 ${(100.0-a*100.0/b).toInt()}% 감소"else"완료  ·  Movies/Compresser에 저장됨"};WorkInfo.State.CANCELLED->"취소됨";WorkInfo.State.FAILED->"실패  ·  ${i.outputData.getString(CompressionWorker.KEY_ERROR)?:"알 수 없는 오류"}";WorkInfo.State.BLOCKED->"대기 중…"};if(i.state.isFinished){cancelButton.isEnabled=false;startButton.isEnabled=selectedUris.isNotEmpty()}}}
    private fun shareResults(){if(lastOutputUris.isEmpty())return;val u=lastOutputUris.map(Uri::parse);val intent=if(u.size==1)Intent(Intent.ACTION_SEND).apply{type="video/*";putExtra(Intent.EXTRA_STREAM,u.first());addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}else Intent(Intent.ACTION_SEND_MULTIPLE).apply{type="video/*";putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(u));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)};startActivity(Intent.createChooser(intent,"완료 파일 공유"))}
    private fun querySize(u:Uri):Long{contentResolver.query(u,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst())return it.getLong(0)};return 0}
    private fun displayName(u:Uri):String{contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())return it.getString(0)?:"video"};return u.lastPathSegment?:"video"}
    private fun formatBytes(b:Long):String{if(b<1024)return "$b B";val units=arrayOf("KB","MB","GB","TB");var v=b.toDouble();var i=-1;while(v>=1024&&i<units.lastIndex){v/=1024;i++};return String.format(Locale.KOREA,"%.1f %s",v,units[i])}
}
class SimpleItemSelected(private val action:()->Unit):android.widget.AdapterView.OnItemSelectedListener{override fun onItemSelected(p:android.widget.AdapterView<*>?,v:View?,pos:Int,id:Long)=action();override fun onNothingSelected(p:android.widget.AdapterView<*>?){} }
class SimpleTextWatcher(private val action:()->Unit):android.text.TextWatcher{override fun afterTextChanged(s:android.text.Editable?)=action();override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}}

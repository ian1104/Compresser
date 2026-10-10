plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ian1104.compresser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ian1104.compresser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    doFirst {
        val source = file("src/main/java/com/ian1104/compresser/MainActivity.kt")
        var s = source.readText()
        s = s.replace("singleLine=true", "setSingleLine(true)")
            .replace("singleLine = true", "setSingleLine(true)")
            .replace("x.getString(n)? :\"video\"", "x.getString(n) ?: \"video\"")
            .replace("x.getString(n)? : \"video\"", "x.getString(n) ?: \"video\"")

        val presetAnchor = "qualityBar=SeekBar(this).apply{max=99;progress=49;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){updateQualityText(p+1);updateEstimate()};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){}})};qc.addView(qualityBar,LinearLayout.LayoutParams(-1,dp(48)))"
        val presetReplacement = presetAnchor + "\n        val presetRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL};fun preset(name:String,q:Int,r:Int,c:Int)=Button(this).apply{text=name;textSize=11f;setTextColor(this@MainActivity.text);background=cardBackground(card2);isAllCaps=false;setOnClickListener{qualityBar.progress=q-1;resolutionSpinner.setSelection(r);codecSpinner.setSelection(c);updateQualityText(q);updateEstimate()}};presetRow.addView(preset(\"최고 화질\",90,0,0),LinearLayout.LayoutParams(0,dp(40),1f).apply{rightMargin=dp(4)});presetRow.addView(preset(\"균형\",55,2,0),LinearLayout.LayoutParams(0,dp(40),1f).apply{rightMargin=dp(4)});presetRow.addView(preset(\"강한 압축\",25,1,1),LinearLayout.LayoutParams(0,dp(40),1f));qc.addView(presetRow,LinearLayout.LayoutParams(-1,dp(40)).apply{bottomMargin=dp(8)})"
        if (!s.contains("val presetRow=")) s = s.replace(presetAnchor, presetReplacement)

        val actionAnchor = "actions.addView(startButton,LinearLayout.LayoutParams(-1,dp(52)));actions.addView(cancelButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});actions.addView(shareButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});root.addView(actions)"
        val actionReplacement = "actions.addView(startButton,LinearLayout.LayoutParams(-1,dp(52)));actions.addView(cancelButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});actions.addView(shareButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});val historyButton=makeButton(\"압축 기록\",card2,text){startActivity(Intent(this,HistoryActivity::class.java))};actions.addView(historyButton,LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)});root.addView(actions)"
        if (!s.contains("val historyButton=")) s = s.replace(actionAnchor, actionReplacement)
        source.writeText(s)
    }
}

# The JS bridge is looked up by reflection from the WebView.
-keepclassmembers class il.org.hatzolahair.crm.MainActivity$DownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

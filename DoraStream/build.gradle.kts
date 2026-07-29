version = 1

cloudstream {
    authors     = listOf("Dorara")
    language    = "hi"
    description = "Doraemon movies, seasons, specials and short movies from Dora Bash"
    status  = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Cartoon")
    iconUrl = "https://dorabash.in/wp-content/uploads/2025/11/20210525_121800-6.png"
}

dependencies {
    // Needed for the visible-WebView Cloudflare bypass: Dispatchers,
    // withContext, suspendCancellableCoroutine.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Needed for BottomSheetDialogFragment (CloudflareBypassDialog).
    implementation("com.google.android.material:material:1.12.0")
    // Needed for FragmentActivity / FragmentManager (ensureCloudflareSession).
    implementation("androidx.fragment:fragment-ktx:1.8.4")
}
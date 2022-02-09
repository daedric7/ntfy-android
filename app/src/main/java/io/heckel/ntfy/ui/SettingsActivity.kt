package io.heckel.ntfy.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.OnPreferenceClickListener
import com.google.gson.Gson
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.formatBytes
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.shortUrl
import io.heckel.ntfy.util.toPriorityString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Main settings
 *
 * The "nested screen" navigation stuff (for user management) has been taken from
 * https://github.com/googlearchive/android-preferences/blob/master/app/src/main/java/com/example/androidx/preference/sample/MainActivity.kt
 */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
    UserFragment.UserDialogListener {
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var userSettingsFragment: UserSettingsFragment

    private lateinit var repository: Repository
    private lateinit var serviceManager: SubscriberServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Log.d(TAG, "Create $this")

        repository = Repository.getInstance(this)
        serviceManager = SubscriberServiceManager(this)

        if (savedInstanceState == null) {
            settingsFragment = SettingsFragment() // Empty constructor!
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_layout, settingsFragment)
                .commit()
            title = getString(R.string.settings_title)
        } else {
            title = savedInstanceState.getCharSequence(TITLE_TAG)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                setTitle(R.string.settings_title)
            }
        }

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save current activity title so we can set it again after a configuration change
        outState.putCharSequence(TITLE_TAG, title)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        // Instantiate the new Fragment
        val fragmentClass = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass)
        fragment.arguments = pref.extras

        // Replace the existing Fragment with the new Fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_layout, fragment)
            .addToBackStack(null)
            .commit()
        title = pref.title

        // Save user settings fragment for later
        if (fragment is UserSettingsFragment) {
            userSettingsFragment = fragment
        }
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var repository: Repository
        private lateinit var serviceManager: SubscriberServiceManager
        private var autoDownloadSelection = AUTO_DOWNLOAD_SELECTION_NOT_SET

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.main_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            serviceManager = SubscriberServiceManager(requireActivity())
            autoDownloadSelection = repository.getAutoDownloadMaxSize() // Only used for <= Android P, due to permissions request

            // Important note: We do not use the default shared prefs to store settings. Every
            // preferenceDataStore is overridden to use the repository. This is convenient, because
            // everybody has access to the repository.

            // Notifications muted until (global)
            val mutedUntilPrefId = context?.getString(R.string.settings_notifications_muted_until_key) ?: return
            val mutedUntil: ListPreference? = findPreference(mutedUntilPrefId)
            mutedUntil?.value = repository.getGlobalMutedUntil().toString()
            mutedUntil?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val mutedUntilValue = value?.toLongOrNull() ?:return
                    when (mutedUntilValue) {
                        Repository.MUTED_UNTIL_SHOW_ALL -> repository.setGlobalMutedUntil(mutedUntilValue)
                        Repository.MUTED_UNTIL_FOREVER -> repository.setGlobalMutedUntil(mutedUntilValue)
                        Repository.MUTED_UNTIL_TOMORROW -> {
                            val date = Calendar.getInstance()
                            date.add(Calendar.DAY_OF_MONTH, 1)
                            date.set(Calendar.HOUR_OF_DAY, 8)
                            date.set(Calendar.MINUTE, 30)
                            date.set(Calendar.SECOND, 0)
                            date.set(Calendar.MILLISECOND, 0)
                            repository.setGlobalMutedUntil(date.timeInMillis/1000)
                        }
                        else -> {
                            val mutedUntilTimestamp = System.currentTimeMillis()/1000 + mutedUntilValue * 60
                            repository.setGlobalMutedUntil(mutedUntilTimestamp)
                        }
                    }
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getGlobalMutedUntil().toString()
                }
            }
            mutedUntil?.summaryProvider = Preference.SummaryProvider<ListPreference> { _ ->
                val mutedUntilValue = repository.getGlobalMutedUntil()
                when (mutedUntilValue) {
                    Repository.MUTED_UNTIL_SHOW_ALL -> getString(R.string.settings_notifications_muted_until_show_all)
                    Repository.MUTED_UNTIL_FOREVER -> getString(R.string.settings_notifications_muted_until_forever)
                    else -> {
                        val formattedDate = formatDateShort(mutedUntilValue)
                        getString(R.string.settings_notifications_muted_until_x, formattedDate)
                    }
                }
            }

            // Minimum priority
            val minPriorityPrefId = context?.getString(R.string.settings_notifications_min_priority_key) ?: return
            val minPriority: ListPreference? = findPreference(minPriorityPrefId)
            minPriority?.value = repository.getMinPriority().toString()
            minPriority?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val minPriorityValue = value?.toIntOrNull() ?:return
                    repository.setMinPriority(minPriorityValue)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getMinPriority().toString()
                }
            }
            minPriority?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val minPriorityValue = pref.value.toIntOrNull() ?: 1 // 1/low means all priorities
                when (minPriorityValue) {
                    1 -> getString(R.string.settings_notifications_min_priority_summary_any)
                    5 -> getString(R.string.settings_notifications_min_priority_summary_max)
                    else -> {
                        val minPriorityString = toPriorityString(minPriorityValue)
                        getString(R.string.settings_notifications_min_priority_summary_x_or_higher, minPriorityValue, minPriorityString)
                    }
                }
            }

            // Auto download
            val autoDownloadPrefId = context?.getString(R.string.settings_notifications_auto_download_key) ?: return
            val autoDownload: ListPreference? = findPreference(autoDownloadPrefId)
            autoDownload?.value = repository.getAutoDownloadMaxSize().toString()
            autoDownload?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val maxSize = value?.toLongOrNull() ?:return
                    repository.setAutoDownloadMaxSize(maxSize)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getAutoDownloadMaxSize().toString()
                }
            }
            autoDownload?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val maxSize = pref.value.toLongOrNull() ?: repository.getAutoDownloadMaxSize()
                when (maxSize) {
                    Repository.AUTO_DOWNLOAD_NEVER -> getString(R.string.settings_notifications_auto_download_summary_never)
                    Repository.AUTO_DOWNLOAD_ALWAYS -> getString(R.string.settings_notifications_auto_download_summary_always)
                    else -> getString(R.string.settings_notifications_auto_download_summary_smaller_than_x, formatBytes(maxSize, decimals = 0))
                }
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                autoDownload?.setOnPreferenceChangeListener { _, v ->
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                        ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD)
                        autoDownloadSelection = v.toString().toLongOrNull() ?: repository.getAutoDownloadMaxSize()
                        false // If permission is granted, auto-download will be enabled in onRequestPermissionsResult()
                    } else {
                        true
                    }
                }
            }

            // Auto delete
            val autoDeletePrefId = context?.getString(R.string.settings_notifications_auto_delete_key) ?: return
            val autoDelete: ListPreference? = findPreference(autoDeletePrefId)
            autoDelete?.value = repository.getAutoDeleteSeconds().toString()
            autoDelete?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val seconds = value?.toLongOrNull() ?:return
                    repository.setAutoDeleteSeconds(seconds)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getAutoDeleteSeconds().toString()
                }
            }
            autoDelete?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val seconds = pref.value.toLongOrNull() ?: repository.getAutoDeleteSeconds()
                when (seconds) {
                    Repository.AUTO_DELETE_NEVER -> getString(R.string.settings_notifications_auto_delete_summary_never)
                    Repository.AUTO_DELETE_ONE_DAY_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_day)
                    Repository.AUTO_DELETE_THREE_DAYS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_days)
                    Repository.AUTO_DELETE_ONE_WEEK_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_week)
                    Repository.AUTO_DELETE_ONE_MONTH_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_one_month)
                    Repository.AUTO_DELETE_THREE_MONTHS_SECONDS -> getString(R.string.settings_notifications_auto_delete_summary_three_months)
                    else -> getString(R.string.settings_notifications_auto_delete_summary_one_month) // Must match default const
                }
            }

            // Dark mode
            val darkModePrefId = context?.getString(R.string.settings_appearance_dark_mode_key) ?: return
            val darkMode: ListPreference? = findPreference(darkModePrefId)
            darkMode?.value = repository.getDarkMode().toString()
            darkMode?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val darkModeValue = value?.toIntOrNull() ?: return
                    repository.setDarkMode(darkModeValue)
                    AppCompatDelegate.setDefaultNightMode(darkModeValue)

                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getDarkMode().toString()
                }
            }
            darkMode?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val darkModeValue = pref.value.toIntOrNull() ?: repository.getDarkMode()
                when (darkModeValue) {
                    AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.settings_appearance_dark_mode_summary_light)
                    AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.settings_appearance_dark_mode_summary_dark)
                    else -> getString(R.string.settings_appearance_dark_mode_summary_system)
                }
            }

            // UnifiedPush enabled
            val upEnabledPrefId = context?.getString(R.string.settings_unified_push_enabled_key) ?: return
            val upEnabled: SwitchPreference? = findPreference(upEnabledPrefId)
            upEnabled?.isChecked = repository.getUnifiedPushEnabled()
            upEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setUnifiedPushEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getUnifiedPushEnabled()
                }
            }
            upEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_unified_push_enabled_summary_on)
                } else {
                    getString(R.string.settings_unified_push_enabled_summary_off)
                }
            }

            // UnifiedPush Base URL
            val appBaseUrl = context?.getString(R.string.app_base_url) ?: return
            val upBaseUrlPrefId = context?.getString(R.string.settings_unified_push_base_url_key) ?: return
            val upBaseUrl: EditTextPreference? = findPreference(upBaseUrlPrefId)
            upBaseUrl?.text = repository.getUnifiedPushBaseUrl() ?: ""
            upBaseUrl?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String, value: String?) {
                    val baseUrl = value ?: return
                    repository.setUnifiedPushBaseUrl(baseUrl)
                }
                override fun getString(key: String, defValue: String?): String? {
                    return repository.getUnifiedPushBaseUrl()
                }
            }
            upBaseUrl?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                if (TextUtils.isEmpty(pref.text)) {
                    getString(R.string.settings_unified_push_base_url_default_summary, appBaseUrl)
                } else {
                    pref.text
                }
            }

            // Broadcast enabled
            val broadcastEnabledPrefId = context?.getString(R.string.settings_advanced_broadcast_key) ?: return
            val broadcastEnabled: SwitchPreference? = findPreference(broadcastEnabledPrefId)
            broadcastEnabled?.isChecked = repository.getBroadcastEnabled()
            broadcastEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setBroadcastEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getBroadcastEnabled()
                }
            }
            broadcastEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_broadcast_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_broadcast_summary_disabled)
                }
            }

            // Export logs
            val exportLogsPrefId = context?.getString(R.string.settings_advanced_export_logs_key) ?: return
            val exportLogs: ListPreference? = findPreference(exportLogsPrefId)
            exportLogs?.isVisible = Log.getRecord()
            exportLogs?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            exportLogs?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
                when (v) {
                    EXPORT_LOGS_COPY_ORIGINAL -> copyLogsToClipboard(scrub = false)
                    EXPORT_LOGS_COPY_SCRUBBED -> copyLogsToClipboard(scrub = true)
                    EXPORT_LOGS_UPLOAD_ORIGINAL -> uploadLogsToNopaste(scrub = false)
                    EXPORT_LOGS_UPLOAD_SCRUBBED -> uploadLogsToNopaste(scrub = true)
                }
                false
            }

            val clearLogsPrefId = context?.getString(R.string.settings_advanced_clear_logs_key) ?: return
            val clearLogs: Preference? = findPreference(clearLogsPrefId)
            clearLogs?.isVisible = Log.getRecord()
            clearLogs?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            clearLogs?.onPreferenceClickListener = OnPreferenceClickListener {
                deleteLogs()
                false
            }

            // Record logs
            val recordLogsPrefId = context?.getString(R.string.settings_advanced_record_logs_key) ?: return
            val recordLogsEnabled: SwitchPreference? = findPreference(recordLogsPrefId)
            recordLogsEnabled?.isChecked = Log.getRecord()
            recordLogsEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setRecordLogsEnabled(value)
                    Log.setRecord(value)
                    exportLogs?.isVisible = value
                    clearLogs?.isVisible = value
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return Log.getRecord()
                }
            }
            recordLogsEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_record_logs_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_record_logs_summary_disabled)
                }
            }
            recordLogsEnabled?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.getSubscriptions().forEach { s ->
                        Log.addScrubTerm(shortUrl(s.baseUrl), Log.TermType.Domain)
                        Log.addScrubTerm(s.topic)
                    }
                }
                true
            }

            // Connection protocol
            val connectionProtocolPrefId = context?.getString(R.string.settings_advanced_connection_protocol_key) ?: return
            val connectionProtocol: ListPreference? = findPreference(connectionProtocolPrefId)
            connectionProtocol?.value = repository.getConnectionProtocol()
            connectionProtocol?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val proto = value ?: repository.getConnectionProtocol()
                    repository.setConnectionProtocol(proto)
                    restartService()
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getConnectionProtocol()
                }
            }
            connectionProtocol?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (pref.value) {
                    Repository.CONNECTION_PROTOCOL_WS -> getString(R.string.settings_advanced_connection_protocol_summary_ws)
                    else -> getString(R.string.settings_advanced_connection_protocol_summary_jsonhttp)
                }
            }

            // Version
            val versionPrefId = context?.getString(R.string.settings_about_version_key) ?: return
            val versionPref: Preference? = findPreference(versionPrefId)
            val version = getString(R.string.settings_about_version_format, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
            versionPref?.summary = version
            versionPref?.onPreferenceClickListener = OnPreferenceClickListener {
                val context = context ?: return@OnPreferenceClickListener false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ntfy version", version)
                clipboard.setPrimaryClip(clip)
                Toast
                    .makeText(context, getString(R.string.settings_about_version_copied_to_clipboard_message), Toast.LENGTH_LONG)
                    .show()
                true
            }
        }

        fun setAutoDownload() {
            val autoDownloadSelectionCopy = autoDownloadSelection
            if (autoDownloadSelectionCopy == AUTO_DOWNLOAD_SELECTION_NOT_SET) return
            val autoDownloadPrefId = context?.getString(R.string.settings_notifications_auto_download_key) ?: return
            val autoDownload: ListPreference? = findPreference(autoDownloadPrefId)
            autoDownload?.value = autoDownloadSelectionCopy.toString()
            repository.setAutoDownloadMaxSize(autoDownloadSelectionCopy)
        }

        private fun restartService() {
            serviceManager.restart() // Service will auto-restart
        }

        private fun copyLogsToClipboard(scrub: Boolean) {
            lifecycleScope.launch(Dispatchers.IO) {
                val log = Log.getFormatted(scrub = scrub)
                val context = context ?: return@launch
                requireActivity().runOnUiThread {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("ntfy logs", log)
                    clipboard.setPrimaryClip(clip)
                    if (scrub) {
                        showScrubDialog(getString(R.string.settings_advanced_export_logs_copied_logs))
                    } else {
                        Toast
                            .makeText(context, getString(R.string.settings_advanced_export_logs_copied_logs), Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }

        private fun uploadLogsToNopaste(scrub: Boolean) {
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d(TAG, "Uploading log to $EXPORT_LOGS_UPLOAD_URL ...")
                val log = Log.getFormatted(scrub = scrub)
                if (log.length > EXPORT_LOGS_UPLOAD_NOTIFY_SIZE_THRESHOLD) {
                    requireActivity().runOnUiThread {
                        Toast
                            .makeText(context, getString(R.string.settings_advanced_export_logs_uploading), Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                val gson = Gson()
                val request = Request.Builder()
                    .url(EXPORT_LOGS_UPLOAD_URL)
                    .put(log.toRequestBody())
                    .build()
                val client = OkHttpClient.Builder()
                    .callTimeout(1, TimeUnit.MINUTES) // Total timeout for entire request
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("Unexpected response ${response.code}")
                        }
                        val body = response.body?.string()?.trim()
                        if (body == null || body.isEmpty()) throw Exception("Return body is empty")
                        Log.d(TAG, "Logs uploaded successfully: $body")
                        val resp = gson.fromJson(body.toString(), NopasteResponse::class.java)
                        val context = context ?: return@launch
                        requireActivity().runOnUiThread {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("logs URL", resp.url)
                            clipboard.setPrimaryClip(clip)
                            if (scrub) {
                                showScrubDialog(getString(R.string.settings_advanced_export_logs_copied_url))
                            } else {
                                Toast
                                    .makeText(context, getString(R.string.settings_advanced_export_logs_copied_url), Toast.LENGTH_LONG)
                                    .show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error uploading logs", e)
                    val context = context ?: return@launch
                    requireActivity().runOnUiThread {
                        Toast
                            .makeText(context, getString(R.string.settings_advanced_export_logs_error_uploading, e.message), Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        }

        private fun showScrubDialog(title: String) {
            val scrubbed = Log.getScrubTerms()
            val scrubbedText = if (scrubbed.isNotEmpty()) {
                val scrubTerms = scrubbed.map { e -> "${e.key} -> ${e.value}"}.joinToString(separator = "\n")
                getString(R.string.settings_advanced_export_logs_scrub_dialog_text, scrubTerms)
            } else {
                getString(R.string.settings_advanced_export_logs_scrub_dialog_empty)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(scrubbedText)
                .setPositiveButton(R.string.settings_advanced_export_logs_scrub_dialog_button_ok) { _, _ -> /* Nothing */ }
                .create()
            dialog.show()
        }

        private fun deleteLogs() {
            lifecycleScope.launch(Dispatchers.IO) {
                Log.deleteAll()
                val context = context ?: return@launch
                requireActivity().runOnUiThread {
                    Toast
                        .makeText(context, getString(R.string.settings_advanced_clear_logs_deleted_toast), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }

        @Keep
        data class NopasteResponse(val url: String)
    }

    class UserSettingsFragment : PreferenceFragmentCompat() {
        private lateinit var repository: Repository

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.user_preferences, rootKey)
            repository = Repository.getInstance(requireActivity())
            reload()
        }

        data class UserWithMetadata(
            val user: User,
            val topics: List<String>
        )

        fun reload() {
            preferenceScreen.removeAll()
            lifecycleScope.launch(Dispatchers.IO) {
                val baseUrlsWithTopics = repository.getSubscriptions()
                    .groupBy { it.baseUrl }
                    .mapValues { e -> e.value.map { it.topic } }
                val usersByBaseUrl = repository.getUsers()
                    .map { user ->
                        val topics = baseUrlsWithTopics[user.baseUrl] ?: emptyList()
                        UserWithMetadata(user, topics)
                    }
                    .groupBy { it.user.baseUrl }
                activity?.runOnUiThread {
                    addUserPreferences(usersByBaseUrl)
                }
            }
        }

        private fun addUserPreferences(usersByBaseUrl: Map<String, List<UserWithMetadata>>) {
            val baseUrlsInUse = ArrayList(usersByBaseUrl.keys)
            usersByBaseUrl.forEach { entry ->
                val baseUrl = entry.key
                val users = entry.value

                val preferenceCategory = PreferenceCategory(preferenceScreen.context)
                preferenceCategory.title = shortUrl(baseUrl)
                preferenceScreen.addPreference(preferenceCategory)

                users.forEach { user ->
                    val preference = Preference(preferenceScreen.context)
                    preference.title = user.user.username
                    preference.summary = if (user.topics.isEmpty()) {
                        getString(R.string.settings_users_prefs_user_not_used)
                    } else if (user.topics.size == 1) {
                        getString(R.string.settings_users_prefs_user_used_by_one, user.topics[0])
                    } else {
                        getString(R.string.settings_users_prefs_user_used_by_many, user.topics.joinToString(", "))
                    }
                    preference.onPreferenceClickListener = OnPreferenceClickListener { _ ->
                        activity?.let {
                            UserFragment
                                .newInstance(user.user, baseUrlsInUse)
                                .show(it.supportFragmentManager, UserFragment.TAG)
                        }
                        true
                    }
                    preferenceCategory.addPreference(preference)
                }
            }

            // Add user
            val userAddCategory = PreferenceCategory(preferenceScreen.context)
            userAddCategory.title = getString(R.string.settings_users_prefs_user_add)
            preferenceScreen.addPreference(userAddCategory)

            val userAddPref = Preference(preferenceScreen.context)
            userAddPref.title = getString(R.string.settings_users_prefs_user_add_title)
            userAddPref.summary = getString(R.string.settings_users_prefs_user_add_summary)
            userAddPref.onPreferenceClickListener = OnPreferenceClickListener { _ ->
                activity?.let {
                    UserFragment
                        .newInstance(user = null, baseUrlsInUse = baseUrlsInUse)
                        .show(it.supportFragmentManager, UserFragment.TAG)
                }
                true
            }
            userAddCategory.addPreference(userAddPref)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setAutoDownload()
            }
        }
    }

    override fun onAddUser(dialog: DialogFragment, user: User) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.addUser(user) // New users are not used, so no service refresh required
            runOnUiThread {
                userSettingsFragment.reload()
            }
        }
    }

    override fun onUpdateUser(dialog: DialogFragment, user: User) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.updateUser(user)
            serviceManager.restart() // Editing does not change the user ID
            runOnUiThread {
                userSettingsFragment.reload()
            }
        }
    }

    override fun onDeleteUser(dialog: DialogFragment, baseUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.deleteUser(baseUrl)
            serviceManager.restart()
            runOnUiThread {
                userSettingsFragment.reload()
            }
        }
    }

    private fun setAutoDownload() {
        if (!this::settingsFragment.isInitialized) return
        settingsFragment.setAutoDownload()
    }

    companion object {
        private const val TAG = "NtfySettingsActivity"
        private const val TITLE_TAG = "title"
        private const val REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD = 2586
        private const val AUTO_DOWNLOAD_SELECTION_NOT_SET = -99L
        private const val EXPORT_LOGS_COPY_ORIGINAL = "copy_original"
        private const val EXPORT_LOGS_COPY_SCRUBBED = "copy_scrubbed"
        private const val EXPORT_LOGS_UPLOAD_ORIGINAL = "upload_original"
        private const val EXPORT_LOGS_UPLOAD_SCRUBBED = "upload_scrubbed"
        private const val EXPORT_LOGS_UPLOAD_URL = "https://nopaste.net/?f=json" // Run by binwiederhier; see https://github.com/binwiederhier/pcopy
        private const val EXPORT_LOGS_UPLOAD_NOTIFY_SIZE_THRESHOLD = 100 * 1024 // Show "Uploading ..." if log larger than X
    }
}

package io.github.selfupdater.sample

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.selfupdater.UpdateAvailable

internal class CatalogAdapter(
    private val onInstall: (UpdateAvailable) -> Unit,
) : RecyclerView.Adapter<CatalogAdapter.AppViewHolder>() {
    private val entries = mutableListOf<CatalogApp>()
    private val states = mutableMapOf<String, RowState>()
    private val expandedNotes = mutableSetOf<String>()
    private var operationsEnabled = true

    init {
        setHasStableIds(true)
    }

    val hasItems: Boolean
        get() = entries.isNotEmpty()

    val updateCount: Int
        get() = entries.count { it.action == CatalogAction.Update }

    fun submitList(newEntries: List<CatalogApp>) {
        val previousSize = entries.size
        entries.clear()
        if (previousSize > 0) notifyItemRangeRemoved(0, previousSize)
        entries.addAll(newEntries)
        val applicationIds = newEntries.mapTo(mutableSetOf()) { it.release.applicationId }
        states.keys.retainAll(applicationIds)
        expandedNotes.retainAll(applicationIds)
        if (newEntries.isNotEmpty()) notifyItemRangeInserted(0, newEntries.size)
    }

    fun markDownloading(applicationId: String, percent: Int) = updateState(
        applicationId,
        RowState.Downloading(percent),
    )

    fun markInstalling(applicationId: String) = updateState(applicationId, RowState.Installing)

    fun markWaitingForPermission(applicationId: String) = updateState(
        applicationId,
        RowState.WaitingForPermission,
    )

    fun markFailed(applicationId: String, message: String) = updateState(
        applicationId,
        RowState.Failed(message),
    )

    fun setOperationsEnabled(enabled: Boolean) {
        operationsEnabled = enabled
        if (entries.isNotEmpty()) notifyItemRangeChanged(0, entries.size)
    }

    private fun updateState(applicationId: String, state: RowState) {
        states[applicationId] = state
        val index = entries.indexOfFirst { it.release.applicationId == applicationId }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder = AppViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_catalog_app, parent, false),
    )

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val entry = entries[position]
        holder.bind(entry, states[entry.release.applicationId] ?: RowState.Idle)
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = entries[position].release.applicationId.hashCode().toLong()

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val appIcon: ImageView = view.findViewById(R.id.appIcon)
        private val displayName: TextView = view.findViewById(R.id.displayName)
        private val appId: TextView = view.findViewById(R.id.applicationId)
        private val version: TextView = view.findViewById(R.id.version)
        private val statusBadge: TextView = view.findViewById(R.id.statusBadge)
        private val mandatory: TextView = view.findViewById(R.id.mandatoryBadge)
        private val notes: TextView = view.findViewById(R.id.releaseNotes)
        private val notesToggle: Button = view.findViewById(R.id.notesToggle)
        private val progress: ProgressBar = view.findViewById(R.id.downloadProgress)
        private val status: TextView = view.findViewById(R.id.rowStatus)
        private val action: Button = view.findViewById(R.id.installButton)
        private val defaultButtonTint: ColorStateList? = action.backgroundTintList
        private val updateButtonTint: ColorStateList =
            ContextCompat.getColorStateList(view.context, R.color.button_update_tint)
                ?: ColorStateList.valueOf(ContextCompat.getColor(view.context, R.color.update_green))

        fun bind(entry: CatalogApp, state: RowState) {
            val context = itemView.context
            val release = entry.release
            displayName.text = entry.displayName
            appId.text = release.applicationId
            entry.installedVersion?.icon?.let(appIcon::setImageDrawable)
                ?: appIcon.setImageResource(R.drawable.ic_app_placeholder)
            version.text = entry.installedVersion?.let { installed ->
                context.getString(
                    R.string.available_and_installed_version,
                    release.version,
                    release.versionCode,
                    installed.versionName ?: context.getString(R.string.unknown_version_name),
                    installed.versionCode,
                )
            } ?: context.getString(R.string.available_version, release.version, release.versionCode)
            mandatory.visibility = if (release.mandatory) View.VISIBLE else View.GONE
            bindStatusBadge(entry.action)
            val releaseNotes = if (release.changelog.isEmpty()) {
                context.getString(R.string.no_release_notes)
            } else {
                release.changelog.joinToString("\n") { context.getString(R.string.release_note_bullet, it) }
            }
            notes.text = releaseNotes
            val canExpand = release.changelog.size > COLLAPSED_NOTE_COUNT || releaseNotes.length > LONG_NOTES_LENGTH
            val isExpanded = release.applicationId in expandedNotes
            notes.maxLines = if (isExpanded) Integer.MAX_VALUE else COLLAPSED_NOTE_LINES
            notes.ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
            notesToggle.visibility = if (canExpand) View.VISIBLE else View.GONE
            notesToggle.setText(if (isExpanded) R.string.show_less else R.string.show_more)
            notesToggle.setOnClickListener { toggleNotes(release.applicationId) }

            action.backgroundTintList = if (entry.action == CatalogAction.Update) {
                updateButtonTint
            } else {
                defaultButtonTint
            }
            action.setOnClickListener { onInstall(release) }
            when (state) {
                RowState.Idle -> {
                    progress.visibility = View.GONE
                    status.visibility = View.GONE
                    action.isEnabled = operationsEnabled
                    action.setText(
                        when (entry.action) {
                            CatalogAction.Install -> R.string.install_app
                            CatalogAction.Reinstall -> R.string.reinstall_app
                            CatalogAction.Update -> R.string.download_and_install_update
                            CatalogAction.NewerInstalled -> R.string.installed_version_newer
                        },
                    )
                    if (entry.action == CatalogAction.NewerInstalled) action.isEnabled = false
                }
                is RowState.Downloading -> {
                    progress.visibility = View.VISIBLE
                    progress.isIndeterminate = state.percent < 0
                    if (state.percent >= 0) progress.progress = state.percent
                    status.visibility = View.VISIBLE
                    status.text = if (state.percent >= 0) {
                        context.getString(R.string.downloading_percent, state.percent)
                    } else {
                        context.getString(R.string.downloading)
                    }
                    action.isEnabled = false
                    action.setText(R.string.downloading)
                }
                RowState.Installing -> {
                    progress.visibility = View.GONE
                    status.visibility = View.VISIBLE
                    status.setText(R.string.opening_installer)
                    action.isEnabled = false
                    action.setText(R.string.installing)
                }
                RowState.WaitingForPermission -> {
                    progress.visibility = View.GONE
                    status.visibility = View.VISIBLE
                    status.setText(R.string.waiting_for_permission)
                    action.isEnabled = false
                    action.setText(R.string.installing)
                }
                is RowState.Failed -> {
                    progress.visibility = View.GONE
                    status.visibility = View.VISIBLE
                    status.text = state.message
                    action.isEnabled = operationsEnabled
                    action.setText(R.string.retry_install)
                }
            }
        }

        private fun bindStatusBadge(action: CatalogAction) {
            val context = itemView.context
            val (text, background, color) = when (action) {
                CatalogAction.Update -> Triple(
                    R.string.update_available,
                    R.drawable.badge_update,
                    R.color.update_badge_text,
                )
                CatalogAction.Reinstall -> Triple(
                    R.string.up_to_date,
                    R.drawable.badge_current,
                    R.color.current_badge_text,
                )
                CatalogAction.Install -> Triple(
                    R.string.not_installed,
                    R.drawable.badge_not_installed,
                    R.color.not_installed_badge_text,
                )
                CatalogAction.NewerInstalled -> Triple(
                    R.string.newer_version_installed,
                    R.drawable.badge_newer,
                    R.color.newer_badge_text,
                )
            }
            statusBadge.setText(text)
            statusBadge.setBackgroundResource(background)
            statusBadge.setTextColor(ContextCompat.getColor(context, color))
        }
    }

    private fun toggleNotes(applicationId: String) {
        if (!expandedNotes.add(applicationId)) expandedNotes.remove(applicationId)
        val index = entries.indexOfFirst { it.release.applicationId == applicationId }
        if (index >= 0) notifyItemChanged(index)
    }

    internal sealed interface RowState {
        data object Idle : RowState
        data class Downloading(val percent: Int) : RowState
        data object Installing : RowState
        data object WaitingForPermission : RowState
        data class Failed(val message: String) : RowState
    }

    private companion object {
        const val COLLAPSED_NOTE_COUNT = 3
        const val COLLAPSED_NOTE_LINES = 3
        const val LONG_NOTES_LENGTH = 180
    }
}

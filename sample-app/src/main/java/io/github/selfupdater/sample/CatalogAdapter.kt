package io.github.selfupdater.sample

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private var operationsEnabled = true

    fun submitList(newEntries: List<CatalogApp>) {
        val previousSize = entries.size
        entries.clear()
        if (previousSize > 0) notifyItemRangeRemoved(0, previousSize)
        entries.addAll(newEntries)
        states.keys.retainAll(newEntries.mapTo(mutableSetOf()) { it.release.applicationId })
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

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val appId: TextView = view.findViewById(R.id.applicationId)
        private val version: TextView = view.findViewById(R.id.version)
        private val mandatory: TextView = view.findViewById(R.id.mandatoryBadge)
        private val notes: TextView = view.findViewById(R.id.releaseNotes)
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
            appId.text = release.applicationId
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
            notes.text = if (release.changelog.isEmpty()) {
                context.getString(R.string.no_release_notes)
            } else {
                release.changelog.joinToString("\n") { context.getString(R.string.release_note_bullet, it) }
            }

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
                        },
                    )
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
    }

    internal sealed interface RowState {
        data object Idle : RowState
        data class Downloading(val percent: Int) : RowState
        data object Installing : RowState
        data object WaitingForPermission : RowState
        data class Failed(val message: String) : RowState
    }
}

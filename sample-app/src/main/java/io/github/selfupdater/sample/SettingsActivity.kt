package io.github.selfupdater.sample

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.selfupdater.Updater
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var store: CloudflareCredentialsStore
    private lateinit var inputGroup: View
    private lateinit var connectedGroup: View
    private lateinit var clientId: EditText
    private lateinit var clientSecret: EditText
    private lateinit var saveButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var connection: Updater? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)

        store = CloudflareCredentialsStore(applicationContext)
        inputGroup = findViewById(R.id.credentialInputGroup)
        connectedGroup = findViewById(R.id.credentialConnectedGroup)
        clientId = findViewById(R.id.clientIdInput)
        clientSecret = findViewById(R.id.clientSecretInput)
        saveButton = findViewById(R.id.saveCredentialsButton)
        progress = findViewById(R.id.connectionProgress)
        status = findViewById(R.id.credentialStatusText)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        saveButton.setOnClickListener { saveAndConnect() }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { logout() }
        render(store.load() != null)
    }

    private fun saveAndConnect() {
        val id = clientId.text.toString().trim()
        val secret = clientSecret.text.toString().trim()
        if (id.isEmpty() || secret.isEmpty()) {
            status.setText(R.string.cloudflare_credentials_required)
            return
        }

        val credentials = CloudflareAccessCredentials(id, secret)
        setConnecting(true)
        connection?.close()
        val instance = UpdateConfiguration.updater(applicationContext, credentials)
        connection = instance
        lifecycleScope.launch {
            runCatching { instance.fetchCatalog() }
                .onSuccess {
                    if (connection !== instance) return@onSuccess
                    runCatching { store.save(credentials) }
                        .onSuccess { render(configured = true) }
                        .onFailure {
                            setConnecting(false)
                            status.setText(R.string.cloudflare_credentials_save_failed)
                        }
                }
                .onFailure { error ->
                    if (connection !== instance) return@onFailure
                    setConnecting(false)
                    status.text = error.message ?: getString(R.string.connection_failed)
                }
        }
    }

    private fun logout() {
        connection?.close()
        connection = null
        store.clear()
        render(configured = false)
        status.setText(R.string.cloudflare_logged_out)
    }

    private fun render(configured: Boolean) {
        clientId.text.clear()
        clientSecret.text.clear()
        inputGroup.visibility = if (configured) View.GONE else View.VISIBLE
        connectedGroup.visibility = if (configured) View.VISIBLE else View.GONE
        progress.visibility = View.GONE
        status.setText(if (configured) R.string.cloudflare_connected else R.string.cloudflare_not_configured)
        saveButton.isEnabled = true
    }

    private fun setConnecting(connecting: Boolean) {
        saveButton.isEnabled = !connecting
        progress.visibility = if (connecting) View.VISIBLE else View.GONE
        status.setText(if (connecting) R.string.testing_connection else R.string.cloudflare_not_configured)
    }

    override fun onDestroy() {
        connection?.close()
        super.onDestroy()
    }
}

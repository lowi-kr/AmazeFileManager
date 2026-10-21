/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.ui.activities

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.format.Formatter
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.amaze.filemanager.transfer.TransferProtocol
import com.amaze.filemanager.ui.views.Kit
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Send files to another device that is also running this app, over Wi-Fi Direct or over the same
 * Wi-Fi network / hotspot (connect by IP). The receiver is always asked before anything is saved.
 */
class WifiTransferActivity : Activity() {
    private lateinit var kit: Kit
    private val ui = Handler(Looper.getMainLooper())
    private val deviceName: String get() = Build.MODEL ?: "Android device"

    private lateinit var thisDeviceText: TextView
    private lateinit var folderText: TextView
    private lateinit var accessCard: LinearLayout
    private lateinit var peersBox: LinearLayout
    private lateinit var p2pStatus: TextView
    private lateinit var findButton: Button
    private lateinit var ipInput: EditText
    private lateinit var peerText: TextView
    private lateinit var sendButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var progressBox: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var p2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var peers: List<WifiP2pDevice> = emptyList()
    private var discovering = false
    private var inGroup = false

    @Volatile private var serverSocket: ServerSocket? = null

    @Volatile private var peerHost: String? = null

    @Volatile private var peerName: String? = null
    private val cancelFlag = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private var lastUiUpdate = 0L

    private val receiveDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AmazeTransfer")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kit = Kit(this)
        window.statusBarColor = Kit.BG
        window.navigationBarColor = Kit.BG
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Kit.BG)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        root.addView(kit.topBar("Wi-Fi P2P transfer") { finish() })
        val scroll = ScrollView(this)
        val col = kit.column(16)
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(Kit.MATCH, 0, 1f))
        buildContent(col)
        setContentView(root)
        root.requestApplyInsets()

        p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = p2pManager?.initialize(this, mainLooper, null)
        startServer()
    }

    override fun onResume() {
        super.onResume()
        val filter =
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
        registerReceiver(p2pReceiver, filter)
        refreshStatic()
    }

    override fun onPause() {
        try {
            unregisterReceiver(p2pReceiver)
        } catch (e: IllegalArgumentException) {
            // not registered
        }
        super.onPause()
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // closing anyway
        }
        stopDiscovery()
        if (inGroup) {
            try {
                p2pManager?.removeGroup(channel, null)
            } catch (e: SecurityException) {
                // permission was revoked
            }
        }
        if (Build.VERSION.SDK_INT >= 27) channel?.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun buildContent(col: LinearLayout) {
        val me = kit.card()
        me.addView(kit.label("This device", 16f, Kit.TXT, bold = true))
        thisDeviceText = kit.label("", 13f, Kit.MUTED)
        me.addView(thisDeviceText)
        folderText = kit.label("", 13f, Kit.MUTED)
        folderText.setPadding(0, kit.dp(8), 0, 0)
        me.addView(folderText)
        val folderButtons = LinearLayout(this)
        folderButtons.setPadding(0, kit.dp(10), 0, 0)
        folderButtons.addView(kit.smallButton("Open received files") { openReceivedFolder() })
        me.addView(folderButtons)
        col.addView(me)

        accessCard = kit.card()
        accessCard.addView(kit.label("All-files access is off", 15f, Kit.DANGER, bold = true))
        accessCard.addView(kit.label("Without it this device cannot save received files.", 13f, Kit.MUTED))
        accessCard.addView(
            kit.button("Allow access") { requestStorageAccess() },
            LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP).apply { topMargin = kit.dp(10) },
        )
        col.addView(accessCard)

        val p2p = kit.card()
        p2p.addView(kit.label("Nearby devices (Wi-Fi Direct)", 16f, Kit.TXT, bold = true))
        p2p.addView(kit.label("Open this screen on both devices, then find and tap the other one.", 13f, Kit.MUTED))
        findButton = kit.button("Find devices") { toggleDiscovery() }
        p2p.addView(findButton, LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP).apply { topMargin = kit.dp(10) })
        p2pStatus = kit.label("", 13f, Kit.MUTED)
        p2pStatus.setPadding(0, kit.dp(8), 0, 0)
        p2p.addView(p2pStatus)
        peersBox = LinearLayout(this)
        peersBox.orientation = LinearLayout.VERTICAL
        p2p.addView(peersBox)
        disconnectButton = kit.smallButton("Disconnect") { disconnectGroup() }
        disconnectButton.visibility = View.GONE
        p2p.addView(disconnectButton)
        col.addView(p2p)

        val lan = kit.card()
        lan.addView(kit.label("Same Wi-Fi network or hotspot", 16f, Kit.TXT, bold = true))
        lan.addView(kit.label("Type the address shown under This device on the other phone.", 13f, Kit.MUTED))
        ipInput = EditText(this)
        ipInput.hint = "192.168.1.20"
        // A decimal number field accepts only one dot, an IP address needs three
        ipInput.inputType = InputType.TYPE_CLASS_NUMBER
        ipInput.keyListener = DigitsKeyListener.getInstance("0123456789.")
        ipInput.setTextColor(Kit.TXT)
        ipInput.setHintTextColor(Kit.MUTED)
        lan.addView(ipInput, LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP).apply { topMargin = kit.dp(6) })
        lan.addView(kit.button("Connect") { connectByIp() })
        col.addView(lan)

        val send = kit.card()
        send.addView(kit.label("Send", 16f, Kit.TXT, bold = true))
        peerText = kit.label("", 14f, Kit.MUTED)
        send.addView(peerText)
        sendButton = kit.button("Choose files and send") { pickFiles() }
        send.addView(sendButton, LinearLayout.LayoutParams(Kit.MATCH, Kit.WRAP).apply { topMargin = kit.dp(10) })
        progressBox = LinearLayout(this)
        progressBox.orientation = LinearLayout.VERTICAL
        progressBox.visibility = View.GONE
        progressText = kit.label("", 13f, Kit.TXT)
        progressText.setPadding(0, kit.dp(12), 0, kit.dp(6))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        progressBox.addView(progressText)
        progressBox.addView(progressBar)
        progressBox.addView(kit.smallButton("Cancel") { cancelFlag.set(true) })
        send.addView(progressBox)
        col.addView(send)
        updatePeerUi()
    }

    private fun refreshStatic() {
        val addrs = TransferProtocol.localAddresses()
        val addrText = if (addrs.isEmpty()) "No network address yet" else "Address: " + addrs.joinToString(", ")
        thisDeviceText.text = "$deviceName\n$addrText   (port ${TransferProtocol.PORT})"
        folderText.text = "Received files go to:\n${receiveDir.path}"
        accessCard.visibility = if (hasStorageAccess()) View.GONE else View.VISIBLE
        updatePeerUi()
    }

    private fun updatePeerUi() {
        val host = peerHost
        if (host == null) {
            peerText.text = "No device connected yet"
            sendButton.isEnabled = false
            sendButton.alpha = 0.4f
        } else {
            peerText.text = "Connected to ${peerName ?: host}  ($host)"
            sendButton.isEnabled = !busy.get()
            sendButton.alpha = if (busy.get()) 0.4f else 1f
        }
        disconnectButton.visibility = if (inGroup) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- permissions

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    private fun p2pPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasP2pPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(p2pPermission()) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_P2P) {
            if (hasP2pPermission()) startDiscovery() else toast("Nearby device permission is needed to find devices")
        } else {
            refreshStatic()
        }
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private val p2pReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            p2pStatus.text = "Wi-Fi Direct is off. Turn Wi-Fi on."
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                }
            }
        }

    private fun toggleDiscovery() {
        if (discovering) {
            stopDiscovery()
            findButton.text = "Find devices"
            p2pStatus.text = ""
        } else {
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        val manager = p2pManager
        val ch = channel
        if (manager == null || ch == null) {
            p2pStatus.text = "This device does not support Wi-Fi Direct. Use the same network option below."
            return
        }
        if (!hasP2pPermission()) {
            requestPermissions(arrayOf(p2pPermission()), REQ_P2P)
            return
        }
        try {
            manager.discoverPeers(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        discovering = true
                        findButton.text = "Stop searching"
                        p2pStatus.text = "Searching for devices..."
                    }

                    override fun onFailure(reason: Int) {
                        discovering = false
                        findButton.text = "Find devices"
                        p2pStatus.text =
                            when (reason) {
                                WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
                                WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try again in a moment."
                                else -> "Could not search. Turn Wi-Fi (and Location, on Android 12 or older) on."
                            }
                    }
                },
            )
        } catch (e: SecurityException) {
            p2pStatus.text = "Permission missing for Wi-Fi Direct."
        }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        try {
            p2pManager?.stopPeerDiscovery(channel, null)
        } catch (e: SecurityException) {
            // permission was revoked
        }
    }

    private fun requestPeers() {
        try {
            p2pManager?.requestPeers(channel) { list ->
                peers = list.deviceList.toList()
                renderPeers()
            }
        } catch (e: SecurityException) {
            p2pStatus.text = "Permission missing for Wi-Fi Direct."
        }
    }

    private fun renderPeers() {
        peersBox.removeAllViews()
        if (peers.isEmpty() && discovering) {
            p2pStatus.text = "Searching for devices..."
            return
        }
        for (d in peers) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(kit.dp(4), kit.dp(10), kit.dp(4), kit.dp(10))
            row.addView(kit.label(d.deviceName.ifEmpty { d.deviceAddress }, 15f, Kit.TXT, bold = true))
            row.addView(kit.label(peerState(d.status), 12f, Kit.MUTED))
            row.setOnClickListener { connectTo(d) }
            peersBox.addView(row)
        }
        if (peers.isNotEmpty()) p2pStatus.text = "Tap a device to connect"
    }

    private fun peerState(status: Int): String =
        when (status) {
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.INVITED -> "Invitation sent"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Available"
        }

    private fun connectTo(device: WifiP2pDevice) {
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress
        config.wps.setup = WpsInfo.PBC
        try {
            p2pManager?.connect(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        toast("Invitation sent. Accept it on the other device.")
                    }

                    override fun onFailure(reason: Int) {
                        toast("Could not connect (code $reason)")
                    }
                },
            )
        } catch (e: SecurityException) {
            toast("Permission missing for Wi-Fi Direct")
        }
    }

    private fun requestConnection() {
        try {
            p2pManager?.requestConnectionInfo(channel) { info ->
                if (!info.groupFormed) {
                    if (inGroup) {
                        inGroup = false
                        peerHost = null
                        peerName = null
                        updatePeerUi()
                    }
                    return@requestConnectionInfo
                }
                inGroup = true
                updatePeerUi()
                if (info.isGroupOwner) {
                    p2pStatus.text = "Connected. Waiting for the other device to say hello..."
                } else {
                    val owner = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
                    p2pStatus.text = "Connected. Contacting $owner ..."
                    sayHello(owner, retries = 8)
                }
            }
        } catch (e: SecurityException) {
            p2pStatus.text = "Permission missing for Wi-Fi Direct."
        }
    }

    private fun disconnectGroup() {
        try {
            p2pManager?.removeGroup(channel, null)
        } catch (e: SecurityException) {
            // ignore
        }
        inGroup = false
        peerHost = null
        peerName = null
        updatePeerUi()
    }

    // ---------------------------------------------------------------- connecting

    private fun connectByIp() {
        val ip = ipInput.text.toString().trim()
        if (!Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$").matches(ip)) {
            toast("Type an address like 192.168.1.20")
            return
        }
        p2pStatus.text = ""
        sayHello(ip, retries = 1)
    }

    /** Introduces this device to [host]; on success it becomes the peer and learns our address. */
    private fun sayHello(
        host: String,
        retries: Int,
    ) {
        Thread {
            var name: String? = null
            var attempt = 0
            while (name == null && attempt < retries && !isDestroyed) {
                name = TransferProtocol.hello(host, TransferProtocol.PORT, deviceName)
                if (name == null) {
                    attempt++
                    if (attempt < retries) Thread.sleep(1000)
                }
            }
            runOnUiThread {
                if (name != null) {
                    peerHost = host
                    peerName = name
                    toast("Connected to $name")
                } else {
                    toast("No answer from $host. Is this screen open on that device?")
                }
                updatePeerUi()
            }
        }.start()
    }

    // ---------------------------------------------------------------- receiving

    private fun startServer() {
        if (serverSocket != null) return
        val t =
            Thread {
                var ss: ServerSocket? = null
                try {
                    ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(TransferProtocol.PORT))
                    serverSocket = ss
                    while (!ss.isClosed) {
                        val s = ss.accept()
                        s.use { handleIncoming(it) }
                    }
                } catch (e: IOException) {
                    if (ss?.isClosed != true) {
                        runOnUiThread { toast("Cannot listen for incoming files: ${e.message}") }
                    }
                }
            }
        t.isDaemon = true
        t.start()
    }

    private fun handleIncoming(socket: java.net.Socket) {
        cancelFlag.set(false)
        val progress = progressListener("Receiving")
        val result =
            TransferProtocol.handle(socket, deviceName, receiveDir, { askToAccept(it) }, progress)
        runOnUiThread {
            progressBox.visibility = View.GONE
            when (result.kind) {
                TransferProtocol.ReceiveKind.HELLO -> {
                    peerHost = result.peerAddress
                    peerName = result.peerName
                    updatePeerUi()
                    toast("${result.peerName} connected")
                }
                TransferProtocol.ReceiveKind.TRANSFER -> {
                    if (result.files.isNotEmpty()) {
                        MediaScannerConnection.scanFile(this, result.files.map { it.path }.toTypedArray(), null, null)
                    }
                    val bad = if (result.failed > 0) ", ${result.failed} damaged and not saved" else ""
                    toast("Received ${result.files.size} file(s)$bad")
                }
                TransferProtocol.ReceiveKind.CANCELLED -> toast("Receiving cancelled")
                TransferProtocol.ReceiveKind.FAILED -> if (result.message.isNotEmpty()) toast("Receive failed: ${result.message}")
                TransferProtocol.ReceiveKind.DECLINED -> Unit
            }
        }
    }

    private fun askToAccept(offer: TransferProtocol.Offer): Boolean {
        if (!hasStorageAccess()) {
            runOnUiThread { toast("Turn on all-files access to receive files") }
            return false
        }
        val answer = ArrayBlockingQueue<Boolean>(1)
        runOnUiThread {
            if (isDestroyed) {
                answer.offer(false)
            } else {
                val names = offer.files.take(5).joinToString("\n") { "  ${it.name}  (${size(it.size)})" }
                val more = if (offer.files.size > 5) "\n  and ${offer.files.size - 5} more" else ""
                AlertDialog.Builder(this)
                    .setTitle("${offer.senderName} wants to send files")
                    .setMessage("${offer.files.size} file(s), ${size(offer.totalBytes)}\n\n$names$more")
                    .setPositiveButton("Accept") { _, _ -> answer.offer(true) }
                    .setNegativeButton("Decline") { _, _ -> answer.offer(false) }
                    .setOnCancelListener { answer.offer(false) }
                    .show()
            }
        }
        return answer.poll(110, TimeUnit.SECONDS) ?: false
    }

    // ---------------------------------------------------------------- sending

    private fun pickFiles() {
        if (peerHost == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) sendUris(uris)
    }

    private fun sendUris(uris: List<Uri>) {
        val host = peerHost ?: return
        if (!busy.compareAndSet(false, true)) return
        cancelFlag.set(false)
        progressBox.visibility = View.VISIBLE
        progressText.text = "Preparing..."
        progressBar.progress = 0
        updatePeerUi()
        Thread {
            val files = uris.mapNotNull { describe(it) }
            val result =
                if (files.isEmpty()) {
                    TransferProtocol.SendResult(TransferProtocol.SendStatus.FAILED, 0, 0, "Could not read the chosen files")
                } else {
                    TransferProtocol.send(host, TransferProtocol.PORT, deviceName, files, progressListener("Sending"))
                }
            runOnUiThread {
                busy.set(false)
                progressBox.visibility = View.GONE
                updatePeerUi()
                val text =
                    when (result.status) {
                        TransferProtocol.SendStatus.OK -> "Sent ${result.filesOk} file(s)"
                        TransferProtocol.SendStatus.DECLINED -> "The other device declined"
                        TransferProtocol.SendStatus.NO_SPACE -> "The other device is out of space"
                        TransferProtocol.SendStatus.CANCELLED -> "Cancelled"
                        TransferProtocol.SendStatus.FAILED ->
                            if (result.filesFailed > 0) {
                                "${result.filesFailed} file(s) arrived damaged and were rejected"
                            } else {
                                "Failed: ${result.message}"
                            }
                    }
                toast(text)
            }
        }.start()
    }

    private fun describe(uri: Uri): TransferProtocol.OutgoingFile? {
        var name: String? = null
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            if (size < 0) size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            return null
        }
        if (size < 0) return null
        val fileName = name ?: uri.lastPathSegment ?: "file"
        return TransferProtocol.OutgoingFile(fileName, size) {
            contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $fileName")
        }
    }

    private fun progressListener(verb: String): TransferProtocol.Progress {
        val started = System.currentTimeMillis()
        return object : TransferProtocol.Progress {
            override val cancelled: Boolean get() = cancelFlag.get()

            override fun onProgress(
                doneBytes: Long,
                totalBytes: Long,
                fileName: String,
                fileIndex: Int,
                fileCount: Int,
            ) {
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate < 150 && doneBytes < totalBytes) return
                lastUiUpdate = now
                val seconds = maxOf(1L, now - started) / 1000.0
                val speed = (doneBytes / seconds).toLong()
                val percent = if (totalBytes > 0) (doneBytes * 1000 / totalBytes).toInt() else 0
                runOnUiThread {
                    progressBox.visibility = View.VISIBLE
                    progressBar.progress = percent
                    progressText.text =
                        "$verb ${fileIndex + 1} of $fileCount: ${fileName.substringAfterLast('/')}\n" +
                        "${size(doneBytes)} of ${size(totalBytes)}  -  ${size(speed)}/s"
                }
            }
        }
    }

    // ---------------------------------------------------------------- misc

    private fun openReceivedFolder() {
        val dir = receiveDir
        dir.mkdirs()
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setClass(this, MainActivity::class.java)
            intent.setDataAndType(FileProvider.getUriForFile(this, packageName, dir), "resource/folder")
            startActivity(intent)
        } catch (e: Exception) {
            toast("Could not open the folder")
        }
    }

    private fun size(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PICK = 5101
        private const val REQ_P2P = 5102
        private const val REQ_STORAGE = 5103
    }
}

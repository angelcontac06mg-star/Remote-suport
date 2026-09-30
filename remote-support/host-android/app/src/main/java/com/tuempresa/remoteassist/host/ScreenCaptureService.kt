package com.tuempresa.remoteassist.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.nio.charset.StandardCharsets

open class SO : SdpObserver {
    override fun onCreateSuccess(s: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_STOP = "com.tuempresa.remoteassist.host.STOP_SESSION"
        var onCode: ((String) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var videoSource: VideoSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var dc: DataChannel? = null
    private var track: VideoTrack? = null
    private val iceLock = Any()
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val pendingLocalIce = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescriptionReady = false
    @Volatile private var offerSent = false

    private val ice = buildList {
        add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        if (BuildConfig.TURN_URL.isNotBlank() && BuildConfig.TURN_USERNAME.isNotBlank() && BuildConfig.TURN_CREDENTIAL.isNotBlank()) {
            add(PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
                .setUsername(BuildConfig.TURN_USERNAME).setPassword(BuildConfig.TURN_CREDENTIAL).createIceServer())
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ra", "Soporte", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "ra").setContentTitle("Sesión de soporte activa")
            .setContentText("La pantalla se está compartiendo")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopIntent).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
        @Suppress("DEPRECATION") val data = i?.getParcelableExtra<Intent>("data")
        val url = i?.getStringExtra("url")
        if (data == null || url == null) { stopSelf(); return START_NOT_STICKY }
        setup(data); connect(url)
        return START_NOT_STICKY
    }

    private fun setup(data: Intent) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl!!.eglBaseContext))
            .createPeerConnectionFactory()
        capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        videoSource = factory!!.createVideoSource(true)
        textureHelper = SurfaceTextureHelper.create("cap", egl!!.eglBaseContext)
        capturer!!.initialize(textureHelper!!, this, videoSource!!.capturerObserver)
        val dm = resources.displayMetrics
        capturer!!.startCapture(dm.widthPixels / 2, dm.heightPixels / 2, 15)
        track = factory!!.createVideoTrack("v0", videoSource!!)
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun connect(url: String) {
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) { send(JSONObject().put("type", "host")) }
            override fun onMessage(w: WebSocket, text: String) {
                val d = JSONObject(text)
                when (d.getString("type")) {
                    "code" -> onCode?.invoke(d.getString("code"))
                    "joined" -> startOffer()
                    "answer" -> pc?.setRemoteDescription(object : SO() {
                        override fun onSetSuccess() {
                            synchronized(iceLock) {
                                remoteDescriptionReady = true
                                pendingRemoteIce.forEach { pc?.addIceCandidate(it) }
                                pendingRemoteIce.clear()
                            }
                        }
                        override fun onSetFailure(e: String?) { onStatus?.invoke("Respuesta WebRTC inválida: $e") }
                    }, SessionDescription(SessionDescription.Type.ANSWER, d.getString("sdp")))
                    "ice" -> {
                        val mid = if (d.has("sdpMid") && !d.isNull("sdpMid")) d.getString("sdpMid") else null
                        val candidate = IceCandidate(mid, d.getInt("sdpMLineIndex"), d.getString("candidate"))
                        synchronized(iceLock) {
                            if (remoteDescriptionReady) pc?.addIceCandidate(candidate) else pendingRemoteIce.add(candidate)
                        }
                    }
                    "peer-left" -> onStatus?.invoke("El visor se desconectó")
                    "session-ended", "error" -> onStatus?.invoke(d.optString("msg", "La sesión terminó"))
                }
            }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                onStatus?.invoke("Error de conexión: ${t.message ?: "revisa la dirección del servidor y tu red"}")
                stopSelf()
            }
            override fun onClosed(w: WebSocket, c: Int, r: String) { onStatus?.invoke("Sesión terminada"); stopSelf() }
        })
    }

    private fun startOffer() {
        dc?.close()
        pc?.close()
        pendingRemoteIce.clear()
        pendingLocalIce.clear()
        remoteDescriptionReady = false
        offerSent = false
        val cfg = PeerConnection.RTCConfiguration(ice)
        pc = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                synchronized(iceLock) {
                    if (!offerSent) pendingLocalIce.add(c) else sendIce(c)
                }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) { onStatus?.invoke("ICE: $s") }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })!!
        pc!!.addTrack(track, listOf("s0"))
        dc = pc!!.createDataChannel("ctl", DataChannel.Init())
        dc!!.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {}
            override fun onMessage(b: DataChannel.Buffer) {
                val bytes = ByteArray(b.data.remaining()); b.data.get(bytes)
                try { RemoteAccessibilityService.instance?.handle(JSONObject(String(bytes, StandardCharsets.UTF_8))) } catch (_: Exception) {}
            }
        })
        pc!!.createOffer(object : SO() {
            override fun onCreateSuccess(s: SessionDescription?) {
                if (s == null) { onStatus?.invoke("No se pudo crear la oferta WebRTC"); return }
                pc!!.setLocalDescription(object : SO() {
                    override fun onSetSuccess() {
                        synchronized(iceLock) {
                            send(JSONObject().put("type", "offer").put("sdp", s.description))
                            offerSent = true
                            pendingLocalIce.forEach { sendIce(it) }
                            pendingLocalIce.clear()
                        }
                    }
                    override fun onSetFailure(e: String?) { onStatus?.invoke("No se pudo iniciar WebRTC: $e") }
                }, s)
            }
            override fun onCreateFailure(e: String?) { onStatus?.invoke("No se pudo crear la oferta WebRTC: $e") }
        }, MediaConstraints())
    }

    private fun sendIce(c: IceCandidate) {
        send(JSONObject().put("type", "ice").put("candidate", c.sdp)
            .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
    }

    override fun onDestroy() {
        remoteDescriptionReady = false
        offerSent = false
        pendingRemoteIce.clear()
        pendingLocalIce.clear()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        dc?.close(); pc?.close(); ws?.close(1000, "Sesión terminada")
        capturer?.dispose(); track?.dispose(); videoSource?.dispose(); textureHelper?.dispose()
        factory?.dispose()
        egl?.release()
        super.onDestroy()
    }

}

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.min

open class SO : SdpObserver {
    override fun onCreateSuccess(s: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_STOP = "com.tuempresa.remoteassist.host.STOP_SESSION"
        @Volatile var onCode: ((String) -> Unit)? = null
        @Volatile var onStatus: ((String) -> Unit)? = null
        @Volatile var currentCode: String? = null
        @Volatile var currentStatus: String = "Listo para empezar"
        @Volatile var sessionActive: Boolean = false
    }

    // Render Free puede tardar más de 50 s en despertar después de inactividad.
    private val http = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val logTag = "RemoteAssist"
    private var ws: WebSocket? = null
    private var serverUrl: String? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private var resumeToken: String? = null
    @Volatile private var captureReady = false
    @Volatile private var captureFailed = false
    @Volatile private var stopping = false
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
    @Volatile private var remoteControlHintShown = false

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
            stopping = true
            handler.removeCallbacksAndMessages(null)
            send(JSONObject().put("type", "end-session"))
            publishCode(null)
            publishStatus("Sesión finalizada")
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionActive) return START_NOT_STICKY
        stopping = false
        captureReady = false
        captureFailed = false
        sessionActive = true
        publishCode(null)
        publishStatus("Preparando el teléfono…")
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("ra", "Soporte", NotificationManager.IMPORTANCE_LOW))
            val stopIntent = PendingIntent.getService(this, 1,
                Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openIntent = PendingIntent.getActivity(this, 2,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "ra").setContentTitle("RemoteAssist está activo")
                .setContentText("Toca para volver a la sesión; puedes detenerla aquí")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Detener", stopIntent).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, n)
            @Suppress("DEPRECATION") val data = i?.getParcelableExtra<Intent>("data")
            val url = i?.getStringExtra("url")
            if (data == null || url.isNullOrBlank()) {
                publishStatus("No se recibieron los permisos para iniciar")
                stopSelf()
                return START_NOT_STICKY
            }
            serverUrl = url
            // Request the room code before initializing WebRTC. Camera/projection setup can
            // be slow or fail on some phones; it must not prevent signaling from connecting.
            connect(url)
            try {
                setup(data)
                captureReady = true
            } catch (e: Exception) {
                captureFailed = true
                Log.e(logTag, "No se pudo preparar la captura de pantalla", e)
                publishStatus("No se pudo preparar la pantalla: ${e.message ?: e.javaClass.simpleName}. El código seguirá activo; detén e inicia otra sesión después de revisar el permiso.")
            }
        } catch (e: Exception) {
            Log.e(logTag, "No se pudo iniciar ScreenCaptureService", e)
            publishStatus("No se pudo iniciar la captura: ${e.message ?: e.javaClass.simpleName}")
        }
        // Android no reinicia una proyección de pantalla sin pedir permiso de nuevo.
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
            override fun onStop() {
                if (!stopping) {
                    send(JSONObject().put("type", "end-session"))
                    publishStatus("Android detuvo el permiso de compartir pantalla. Vuelve a iniciarlo para compartir otra vez.")
                    stopSelf()
                }
            }
        })
        videoSource = factory!!.createVideoSource(true)
        textureHelper = SurfaceTextureHelper.create("cap", egl!!.eglBaseContext)
        capturer!!.initialize(textureHelper!!, this, videoSource!!.capturerObserver)
        val dm = resources.displayMetrics
            val captureWidth = ((dm.widthPixels / 2).coerceAtLeast(320) / 2) * 2
            val captureHeight = ((dm.heightPixels / 2).coerceAtLeast(320) / 2) * 2
            capturer!!.startCapture(captureWidth, captureHeight, 15)
        track = factory!!.createVideoTrack("v0", videoSource!!)
        publishStatus("Pantalla lista. Conectando con Render…")
    }

    private fun publishStatus(value: String) {
        currentStatus = value
        onStatus?.invoke(value)
    }

    private fun publishCode(value: String?) {
        currentCode = value
        onCode?.invoke(value.orEmpty())
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    private fun connect(url: String) {
        if (stopping) return
        serverUrl = url
        publishStatus(if (reconnectAttempts == 0) "Conectando con el servidor…" else "Intentando reconectar…")
        try {
            ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(w: WebSocket, r: Response) {
                    if (stopping) { w.close(1000, "Sesión finalizada"); return }
                    reconnectAttempts = 0
                    val token = resumeToken
                    val code = currentCode
                    publishStatus(if (token != null && code != null) "Reconectando a tu sesión…" else "Servidor conectado. Generando código…")
                    val hostMessage = JSONObject().put("type", "host")
                    if (token != null && code != null) hostMessage.put("code", code).put("resumeToken", token)
                    w.send(hostMessage.toString())
                }

                override fun onMessage(w: WebSocket, text: String) {
                    if (stopping || w !== ws) return
                    try {
                        val d = JSONObject(text)
                        when (d.optString("type")) {
                            "code" -> {
                                resumeToken = d.optString("resumeToken").takeIf { it.isNotBlank() }
                                publishCode(d.getString("code"))
                                publishStatus("Listo. Comparte el código con el visor de la PC.")
                            }
                            "resumed" -> publishStatus("Sesión recuperada. Conserva el mismo código.")
                            "resume-expired" -> {
                                resumeToken = null
                                publishCode(null)
                                publishStatus("La sesión anterior venció. Generando un código nuevo…")
                                w.send(JSONObject().put("type", "host").toString())
                            }
                            "joined" -> handler.post {
                                when {
                                    captureReady -> startOffer()
                                    captureFailed -> publishStatus("La PC se conectó, pero Android no pudo preparar la captura de pantalla.")
                                    else -> publishStatus("La PC se conectó. Preparando la pantalla…")
                                }
                            }
                            "answer" -> pc?.setRemoteDescription(object : SO() {
                                override fun onSetSuccess() {
                                    synchronized(iceLock) {
                                        remoteDescriptionReady = true
                                        pendingRemoteIce.forEach { pc?.addIceCandidate(it) }
                                        pendingRemoteIce.clear()
                                    }
                                }
                                override fun onSetFailure(e: String?) { publishStatus("No se pudo negociar el video. Intenta iniciar otra sesión.") }
                            }, SessionDescription(SessionDescription.Type.ANSWER, d.getString("sdp")))
                            "ice" -> {
                                val mid = if (d.has("sdpMid") && !d.isNull("sdpMid")) d.getString("sdpMid") else null
                                val candidate = IceCandidate(mid, d.getInt("sdpMLineIndex"), d.getString("candidate"))
                                synchronized(iceLock) {
                                    if (remoteDescriptionReady) pc?.addIceCandidate(candidate) else pendingRemoteIce.add(candidate)
                                }
                            }
                            "peer-left" -> publishStatus("El visor se desconectó. El código sigue activo.")
                            "session-ended" -> {
                                resumeToken = null
                                publishCode(null)
                                publishStatus(if (d.optString("reason") == "expired") "El código venció. Inicia otra sesión." else "La sesión terminó. Puedes iniciar otra cuando quieras.")
                            }
                            "error" -> publishStatus(d.optString("msg", "El servidor no pudo iniciar la sesión"))
                        }
                    } catch (_: Exception) {
                        publishStatus("El servidor respondió con un mensaje que no se pudo procesar")
                    }
                }

                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                    if (stopping || w !== ws) return
                    resetPeer()
                    scheduleReconnect(t.message)
                }

                override fun onClosed(w: WebSocket, c: Int, r: String) {
                    if (stopping || w !== ws) return
                    resetPeer()
                    scheduleReconnect(null)
                }
            })
        } catch (_: Exception) {
            scheduleReconnect(null)
        }
    }

    private fun scheduleReconnect(reason: String?) {
        if (stopping || reconnectRunnable != null) return
        reconnectAttempts += 1
        val delaySeconds = min(30, 2 shl min(reconnectAttempts - 1, 4))
        val message = if (!reason.isNullOrBlank() && reason.contains("timeout", true))
            "Render está tardando en responder. Reintentando en ${delaySeconds}s…"
        else "Sin conexión con el servidor. Reintentando en ${delaySeconds}s…"
        publishStatus(message)
        val task = Runnable {
            reconnectRunnable = null
            val url = serverUrl
            if (!stopping && !url.isNullOrBlank()) connect(url)
        }
        reconnectRunnable = task
        handler.postDelayed(task, delaySeconds * 1000L)
    }

    private fun resetPeer() {
        synchronized(iceLock) {
            remoteDescriptionReady = false
            offerSent = false
            pendingRemoteIce.clear()
            pendingLocalIce.clear()
        }
        try { dc?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        dc = null
        pc = null
    }

    private fun startOffer() {
        try {
            resetPeer()
            remoteControlHintShown = false
            publishStatus("La PC se conectó. Preparando video…")
            val cfg = PeerConnection.RTCConfiguration(ice)
            pc = factory?.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    synchronized(iceLock) {
                        if (!offerSent) pendingLocalIce.add(c) else sendIce(c)
                    }
                }
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED)
                        publishStatus("Video conectado. La pantalla se está compartiendo.")
                    else if (s == PeerConnection.IceConnectionState.FAILED)
                        publishStatus("La red bloqueó la conexión directa. Puede hacer falta configurar TURN.")
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            }) ?: throw IllegalStateException("No se pudo crear PeerConnection")
            val currentPc = pc ?: return
            currentPc.addTrack(track ?: throw IllegalStateException("Video no disponible"), listOf("s0"))
            dc = currentPc.createDataChannel("ctl", DataChannel.Init())
            dc?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(l: Long) {}
                override fun onStateChange() {}
                override fun onMessage(b: DataChannel.Buffer) {
                    val bytes = ByteArray(b.data.remaining()); b.data.get(bytes)
                    try {
                        val accessibility = RemoteAccessibilityService.instance
                        if (accessibility != null) accessibility.handle(JSONObject(String(bytes, StandardCharsets.UTF_8)))
                        else if (!remoteControlHintShown) {
                            remoteControlHintShown = true
                            publishStatus("Pantalla conectada. Para habilitar toques, activa Accesibilidad de forma manual.")
                        }
                    } catch (_: Exception) {}
                }
            })
            currentPc.createOffer(object : SO() {
                override fun onCreateSuccess(s: SessionDescription?) {
                    if (s == null) { publishStatus("No se pudo preparar el video"); return }
                    currentPc.setLocalDescription(object : SO() {
                        override fun onSetSuccess() {
                            synchronized(iceLock) {
                                send(JSONObject().put("type", "offer").put("sdp", s.description))
                                offerSent = true
                                pendingLocalIce.forEach { sendIce(it) }
                                pendingLocalIce.clear()
                            }
                        }
                        override fun onSetFailure(e: String?) { publishStatus("No se pudo preparar la conexión de video") }
                    }, s)
                }
                override fun onCreateFailure(e: String?) { publishStatus("No se pudo preparar el video") }
            }, MediaConstraints())
        } catch (e: Exception) {
            Log.e(logTag, "No se pudo crear la oferta WebRTC", e)
            publishStatus("No se pudo iniciar el video: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sendIce(c: IceCandidate) {
        send(JSONObject().put("type", "ice").put("candidate", c.sdp)
            .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        reconnectRunnable = null
        sessionActive = false
        publishCode(null)
        if (!currentStatus.startsWith("No se pudo iniciar", true) && !currentStatus.startsWith("Android detuvo", true))
            publishStatus("Sesión finalizada")
        synchronized(iceLock) {
            remoteDescriptionReady = false
            offerSent = false
            pendingRemoteIce.clear()
            pendingLocalIce.clear()
        }
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { dc?.close() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}
        try { ws?.close(1000, "Sesión terminada") } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { track?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { textureHelper?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { egl?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

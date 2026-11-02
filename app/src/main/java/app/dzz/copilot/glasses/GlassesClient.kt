package app.dzz.copilot.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import app.dzz.copilot.data.PreferencesStore
import app.dzz.copilot.protocol.FrameAssembler
import app.dzz.copilot.protocol.QcFrame
import app.dzz.copilot.protocol.QcProtocol
import app.dzz.copilot.protocol.QcWifi
import app.dzz.copilot.protocol.ThumbnailChunks
import app.dzz.copilot.protocol.WifiHint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class GlassesClient(
    context: Context,
    private val store: PreferencesStore,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val thread = HandlerThread("dzz-glasses").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assembler = FrameAssembler()
    private val commandMutex = Mutex()
    private val thumbMutex = Mutex()
    private val stateLock = Any()
    private val hits = LinkedHashMap<String, ScanHit>()
    private val logLines = ArrayDeque<String>()
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val writeQueue = ArrayDeque<ByteArray>()
    private val thumbChunks = ThumbnailChunks()

    private var snap = GlassesSnapshot()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var includeUnnamed = false
    private var userGone = false

    /** True after the user disconnected on purpose; self-repair must not reconnect then. */
    val userDisconnected: Boolean get() = userGone

    /** Consecutive AI photos that failed; read by the health monitor. */
    @Volatile var photoFailStreak = 0
        private set
    private var reconnectAttempt = 0
    private var queueStarted = false
    private var writeInFlight = false
    private var writeRetries = 0

    /** Chunks dropped after every retry failed; read by the health monitor. */
    @Volatile var writeDrops = 0
        private set
    private var mtu = 23
    private var audioCount = 0
    private var pending: Pending? = null
    private var thumbWaiter: CompletableDeferred<ByteArray?>? = null
    private var thumbProgress: MutableStateFlow<Long>? = null
    private var photoReady: CompletableDeferred<Unit>? = null
    private var speakHold: Runnable? = null
    @Volatile private var transferring = false
    private var pendingCccd: BluetoothGattCharacteristic? = null
    private var cccdRetries = 0
    private var qcNotifyOk = false
    /** GATT service cache often hides QC until refresh+rediscover. */
    private var discoverPass = 0
    /** Auto retries after landing on Linked without QC. */
    private var qcHuntAttempt = 0
    private var qcForceCount = 0
    private var bondingForQc = false

    private val _snapshot = MutableStateFlow(snap)
    val snapshot: StateFlow<GlassesSnapshot> = _snapshot.asStateFlow()
    private val _devices = MutableStateFlow<List<ScanHit>>(emptyList())
    val devices: StateFlow<List<ScanHit>> = _devices.asStateFlow()
    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()
    private val _paired = MutableStateFlow<List<ScanHit>>(emptyList())
    val paired: StateFlow<List<ScanHit>> = _paired.asStateFlow()

    /** Raw Opus bytes from the glasses microphone (payloads of 0x59 frames), in arrival order. */
    private val _micOpus = kotlinx.coroutines.flow.MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val micOpus: kotlinx.coroutines.flow.SharedFlow<ByteArray> = _micOpus

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun startScan(includeUnnamed: Boolean) {
        val radio = adapter
        val scanner = radio?.bluetoothLeScanner
        if (radio?.isEnabled != true || scanner == null) {
            update {
                copy(
                    phase = LinkPhase.Failed,
                    status = "Bluetooth desligado",
                    lastError = "Ligue o Bluetooth para buscar os óculos.",
                )
            }
            return
        }
        this.includeUnnamed = includeUnnamed
        handler.post {
            stopScanInternal()
            synchronized(hits) { hits.clear() }
            _devices.value = emptyList()
            update { copy(phase = LinkPhase.Scanning, status = "Buscando óculos…", lastError = null) }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner.startScan(null, settings, scanCallback) }
                .onSuccess { scanning = true }
                .onFailure { log("A busca não iniciou: ${it.message}") }
            handler.removeCallbacks(stopScanRunnable)
            handler.postDelayed(stopScanRunnable, 12_000)
        }
    }

    fun stopScan() {
        handler.post { stopScanInternal() }
    }

    fun connect(address: String, name: String?, fromReconnect: Boolean = false) {
        userGone = false
        if (!fromReconnect) {
            reconnectAttempt = 0
            qcHuntAttempt = 0
            qcForceCount = 0
        }
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(qcHuntRunnable)
        store.setDevice(address, name ?: store.deviceName.value)
        handler.post {
            stopScanInternal()
            closeGatt()
            assembler.clear()
            update {
                copy(
                    phase = LinkPhase.Connecting,
                    status = if (fromReconnect) "Reconectando…" else "Conectando…",
                    address = address,
                    name = name ?: this.name,
                    lastError = null,
                    qcChannel = false,
                    alternateChannel = false,
                )
            }
            // Closing + opening GATT in the same tick is a common status-133 pattern.
            val delayMs = if (fromReconnect) 350L else 80L
            handler.postDelayed({ openGatt(address) }, delayMs)
        }
    }

    private fun openGatt(address: String) {
        if (userGone) return
        if (current().phase != LinkPhase.Connecting && current().phase != LinkPhase.Failed) return
        val device = adapter?.getRemoteDevice(address) ?: return
        gatt = device.connectGatt(
            appContext,
            false,
            callback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M,
            handler,
        )
        log("Conectando em $address (tentativa ${reconnectAttempt + 1})")
    }

    fun refreshPaired() {
        handler.post { _paired.value = bondedGlasses() }
    }

    fun connectPairedIfIdle() {
        handler.post {
            val list = bondedGlasses()
            _paired.value = list
            val phase = current().phase
            if (phase != LinkPhase.Idle && phase != LinkPhase.Failed) return@post
            val target = list.firstOrNull { it.audioRoute } ?: list.singleOrNull() ?: return@post
            log("Abrindo o canal de comando em ${target.name}.")
            connect(target.address, target.name)
        }
    }

    fun reconnectSaved() {
        val address = store.deviceAddress.value ?: return
        if (current().phase == LinkPhase.Ready || current().phase == LinkPhase.Connecting) return
        userGone = false
        connect(address, store.deviceName.value)
    }

    fun disconnect() {
        userGone = true
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(qcHuntRunnable)
        qcHuntAttempt = 0
        bondingForQc = false
        handler.post {
            stopScanInternal()
            closeGatt()
            assembler.clear()
            update { GlassesSnapshot(status = "Desconectado") }
            log("Desconectado")
        }
    }

    fun forget() {
        disconnect()
        store.setDevice(null, null)
    }

    fun takePhoto() = launchControl(QcProtocol.photo(), "foto")
    fun startVideo() = launchControl(QcProtocol.videoStart(), "vídeo")
    fun stopVideo() = launchControl(QcProtocol.videoStop(), "parado")
    fun startAudio() = launchControl(QcProtocol.audioStart(), "áudio")
    fun stopAudio() = launchControl(QcProtocol.audioStop(), "parado")
    fun findGlasses() = launchControl(QcProtocol.find(), "procurando")
    fun startTranslate() = launchControl(QcProtocol.translateStart(), "tradução")
    fun stopTranslate() = launchControl(QcProtocol.translateStop(), "parado")

    fun startSpeech() {
        handler.post { audioCount = 0 }
        launchControl(QcProtocol.speechStart(), "escuta")
    }

    fun stopSpeech() = launchControl(QcProtocol.speechStop(), "parado")

    /**
     * Tells the glasses that Aura is talking (0x48), like the DZZ app does while its AI reply plays:
     * start, a "hold" every 2 s, then stop. The glasses then treat the audio as an AI reply.
     */
    fun aiSpeaking(on: Boolean) {
        handler.post {
            speakHold?.let { handler.removeCallbacks(it) }
            speakHold = null
            if (!canWrite()) return@post
            if (!on) {
                enqueueWrite(QcProtocol.build(QcProtocol.AI_SPEAK, QcProtocol.aiSpeak(3)))
                return@post
            }
            enqueueWrite(QcProtocol.build(QcProtocol.AI_SPEAK, QcProtocol.aiSpeak(1)))
            val hold = object : Runnable {
                override fun run() {
                    if (speakHold !== this || !canWrite()) return
                    if (transferring) {
                        handler.postDelayed(this, 2_000)
                        return
                    }
                    enqueueWrite(QcProtocol.build(QcProtocol.AI_SPEAK, QcProtocol.aiSpeak(2)))
                    handler.postDelayed(this, 2_000)
                }
            }
            speakHold = hold
            handler.postDelayed(hold, 2_000)
        }
    }

    fun refreshBattery() {
        scope.launch {
            val frame = send(QcProtocol.BATTERY, byteArrayOf(), 2_000) ?: return@launch
            applyBattery(frame.payload)
        }
    }

    fun syncTime() {
        scope.launch { send(QcProtocol.SYNC_TIME, QcProtocol.timePayload(), 2_000) }
    }

    @Volatile private var inflightCapture: Deferred<ByteArray?>? = null

    /**
     * Takes an AI photo and pulls its thumbnail. A second caller while a capture runs waits for the
     * same photo instead of failing, so "look" twice in a row never turns into a connection error.
     */
    suspend fun captureThumbnail(): ByteArray? {
        if (!canWrite()) {
            log("Miniatura ignorada: canal QC não está pronto.")
            return null
        }
        inflightCapture?.takeIf { it.isActive }?.let {
            log("Foto IA: já tem uma captura em andamento, aguardando a mesma foto.")
            return it.await()
        }
        val job = scope.async { captureOnce() }
        inflightCapture = job
        return try { job.await() } finally { if (inflightCapture === job) inflightCapture = null }
    }

    private suspend fun captureOnce(): ByteArray? {
        if (!thumbMutex.tryLock()) return null
        transferring = true
        return try {
            handler.post { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
            update { copy(mode = "foto ia", lastError = null) }
            val started = SystemClock.elapsedRealtime()
            val ready = CompletableDeferred<Unit>()
            handler.post { photoReady = ready }
            // Same order as the DZZ app: ask for an AI photo, wait for "image ready" (0x73), then
            // pull the thumbnail chunk by chunk with 0xFD. Without the notice, try after 5 s anyway.
            val reply = send(QcProtocol.CONTROL, QcProtocol.aiPhoto(), 3_000)
            if (reply == null) log("Foto IA: os óculos não confirmaram o pedido; tentando buscar a imagem mesmo assim.")
            val notified = withTimeoutOrNull(5_000) { ready.await() } != null
            handler.post { if (photoReady === ready) photoReady = null }
            if (notified) log("Foto IA pronta nos óculos em ${SystemClock.elapsedRealtime() - started} ms")
            var jpeg = pullThumbnail()
            if (jpeg == null) {
                delay(600)
                jpeg = pullThumbnail()
            }
            val ms = SystemClock.elapsedRealtime() - started
            if (jpeg != null) {
                photoFailStreak = 0
                log("Foto IA recebida: ${jpeg.size} bytes em $ms ms (aviso dos óculos: $notified)")
            } else {
                photoFailStreak += 1
                log("Foto IA falhou após $ms ms (aviso dos óculos: $notified)")
            }
            jpeg
        } finally {
            handler.post { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
            transferring = false
            thumbMutex.unlock()
        }
    }

    /**
     * One pass of the 0xFD chunk transfer. A chunk that does not arrive within [CHUNK_STALL_MS] is
     * asked for again (resume from the first missing index) instead of failing the whole photo.
     */
    private suspend fun pullThumbnail(): ByteArray? {
        val result = CompletableDeferred<ByteArray?>()
        val progress = MutableStateFlow(0L)
        handler.post {
            thumbChunks.reset()
            thumbProgress = progress
            thumbWaiter = result
        }
        val started = SystemClock.elapsedRealtime()
        fire(QcProtocol.THUMBNAIL, ThumbnailChunks.request(0))
        var resends = 0
        val out = withTimeoutOrNull(THUMB_TOTAL_MS) {
            var lastCount = -1
            var lastAt = SystemClock.elapsedRealtime()
            while (!result.isCompleted) {
                delay(100)
                val count = progress.value.toInt()
                val now = SystemClock.elapsedRealtime()
                if (count != lastCount) {
                    lastCount = count
                    lastAt = now
                }
                if (now - lastAt > CHUNK_STALL_MS) {
                    if (count == 0 && now - started > FIRST_CHUNK_MS) break
                    if (resends >= MAX_CHUNK_RESENDS) break
                    resends += 1
                    lastAt = now
                    val missing = CompletableDeferred<Int>()
                    handler.post { missing.complete(thumbChunks.firstMissing()) }
                    val idx = missing.await()
                    if (idx < 0) break
                    Log.d(TAG, "miniatura: pedindo de novo o pedaço $idx ($count/${thumbChunks.total})")
                    fire(QcProtocol.THUMBNAIL, ThumbnailChunks.request(idx))
                }
            }
            if (result.isCompleted) result.await() else null
        }
        if (out != null) log("Miniatura: ${thumbChunks.total} pedaços em ${SystemClock.elapsedRealtime() - started} ms ($resends reenvios)")
        val got = thumbChunks.count to thumbChunks.total
        handler.post {
            if (thumbWaiter === result) thumbWaiter = null
            thumbProgress = null
        }
        if (out == null) log("Miniatura incompleta: ${got.first}/${got.second} pedaços.")
        return out
    }

    suspend fun send(cmd: Int, payload: ByteArray, timeoutMs: Long = 2_500): QcFrame? {
        if (!canWrite()) return null
        return commandMutex.withLock {
            val deferred = CompletableDeferred<QcFrame>()
            val packet = QcProtocol.build(cmd, payload)
            handler.post {
                pending = Pending(cmd, deferred)
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                enqueueWrite(packet)
            }
            try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                null
            } finally {
                handler.post {
                    if (pending?.deferred === deferred) pending = null
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                }
            }
        }
    }

    fun fire(cmd: Int, payload: ByteArray) {
        if (!canWrite()) return
        val packet = QcProtocol.build(cmd, payload)
        handler.post { enqueueWrite(packet) }
    }

    private fun launchControl(payload: ByteArray, mode: String) {
        scope.launch {
            if (!canWrite()) {
                log("Comando ignorado: o canal dos óculos ainda não está pronto.")
                update { copy(lastError = "Conecte um óculos com o canal QC antes de enviar comandos.") }
                return@launch
            }
            update { copy(mode = mode, lastError = null) }
            val frame = send(QcProtocol.CONTROL, payload)
            if (frame == null) {
                log("Sem resposta do modo $mode")
                update { copy(lastError = "Sem resposta do óculos no modo $mode.") }
            }
        }
    }

    private fun canWrite(): Boolean {
        val now = current()
        return now.phase == LinkPhase.Ready && writeChar != null && (now.qcChannel || now.alternateChannel)
    }

    private val stopScanRunnable = Runnable { stopScanInternal() }

    private val mtuFallback = Runnable {
        log("MTU sem resposta; seguindo com o padrão do rádio.")
        startNotifyQueue()
    }

    private val reconnectRunnable = Runnable {
        val address = store.deviceAddress.value ?: return@Runnable
        if (!userGone && store.keepAlive.value) {
            log("Tentando reconectar (#$reconnectAttempt)")
            connect(address, store.deviceName.value, fromReconnect = true)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (current().phase != LinkPhase.Ready || writeChar == null) return
            // Don't fight the thumbnail transfer for BLE bandwidth.
            if (thumbWaiter == null && writeQueue.isEmpty() && !writeInFlight) {
                enqueueWrite(QcProtocol.build(QcProtocol.HEARTBEAT, byteArrayOf()))
            }
            handler.postDelayed(this, 8_000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val recordName = if (Build.VERSION.SDK_INT >= 33) {
                result.scanRecord?.deviceName
            } else {
                @Suppress("DEPRECATION")
                result.scanRecord?.deviceName
            }
            val name = result.device.name ?: recordName ?: ""
            if (name.isBlank() && !includeUnnamed) return
            val hit = ScanHit(
                name = name.ifBlank { "Sem nome" },
                address = result.device.address,
                rssi = result.rssi,
                likely = DeviceNames.looksLikeGlasses(name),
            )
            synchronized(hits) {
                hits[hit.address] = hit
                _devices.value = hits.values.sortedWith(
                    compareByDescending<ScanHit> { it.likely }.thenByDescending { it.rssi },
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Busca falhou ($errorCode)")
            update { copy(phase = LinkPhase.Failed, status = "A busca falhou", lastError = "Código $errorCode") }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                discoverPass = 0
                update { copy(phase = LinkPhase.Discovering, status = "Lendo o óculos…") }
                gatt.discoverServices()
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                onLinkLost(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                update { copy(phase = LinkPhase.Failed, status = "Falha ao ler serviços", lastError = gattMessage(status)) }
                return
            }
            // Hunt real QC first; alternate only after several cache refresh retries.
            pickChannel(gatt, allowAlternate = discoverPass >= QC_DISCOVER_PASSES)
            logServices(gatt)
            val hasQc = writeChar?.uuid == QC_WRITE
            if (!hasQc && !bondingForQc && maybeBondForQc(gatt.device)) {
                bondingForQc = true
                discoverPass = 0
                update {
                    copy(
                        phase = LinkPhase.Discovering,
                        status = "Pareando para liberar o canal QC…",
                        qcChannel = false,
                    )
                }
                handler.postDelayed({
                    if (this@GlassesClient.gatt === gatt) {
                        refreshGattCache(gatt)
                        handler.postDelayed({
                            if (this@GlassesClient.gatt === gatt) {
                                runCatching { gatt.discoverServices() }
                            }
                        }, 900)
                    }
                }, 2_200)
                return
            }
            if (!hasQc && discoverPass < QC_DISCOVER_PASSES) {
                discoverPass += 1
                val waitMs = 500L + discoverPass * 400L
                log("Canal QC ausente — limpando cache GATT e relendo ($discoverPass/$QC_DISCOVER_PASSES) em ${waitMs}ms")
                update {
                    copy(
                        phase = LinkPhase.Discovering,
                        status = "Procurando canal QC ($discoverPass/$QC_DISCOVER_PASSES)…",
                        qcChannel = false,
                    )
                }
                refreshGattCache(gatt)
                handler.postDelayed({
                    if (this@GlassesClient.gatt === gatt) {
                        runCatching { gatt.discoverServices() }
                            .onFailure { log("Rediscover falhou: ${it.message}") }
                    }
                }, waitMs)
                return
            }
            discoverPass = 0
            bondingForQc = false
            // Serial GATT: MTU first, then CCCD, then QC battery in initialize(). No parallel reads.
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            handler.removeCallbacks(mtuFallback)
            handler.postDelayed(mtuFallback, 2_200)
            gatt.requestMtu(517)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GlassesClient.mtu = mtu
            log("MTU=$mtu status=$status")
            handler.removeCallbacks(mtuFallback)
            startNotifyQueue()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val target = pendingCccd
            pendingCccd = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (target?.uuid == QC_NOTIFY) qcNotifyOk = true
                cccdRetries = 0
                enableNextNotify()
                return
            }
            log("CCCD falhou (${gattMessage(status)}) em ${target?.uuid}")
            if (target != null && cccdRetries < 2) {
                cccdRetries += 1
                notifyQueue.addFirst(target)
                enableNextNotify()
            } else {
                cccdRetries = 0
                enableNextNotify()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write GATT falhou: ${gattMessage(status)}")
            }
            writeInFlight = false
            handler.removeCallbacks(writePaceFallback)
            drainWrite()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            accept(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) accept(characteristic.value ?: return)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) applyRead(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) {
                applyRead(characteristic, characteristic.value ?: return)
            }
        }
    }

    private fun pickChannel(gatt: BluetoothGatt, allowAlternate: Boolean) {
        val qcService = gatt.getService(QC_SERVICE)
        val fromService = qcService?.getCharacteristic(QC_WRITE)
        val characteristics = gatt.services.flatMap { it.characteristics }
        writeChar = when {
            fromService != null && fromService.canWrite() -> fromService
            fromService != null -> {
                log("QC write achada sem flag WRITE — tentando mesmo assim")
                fromService
            }
            else -> characteristics.firstOrNull { it.uuid == QC_WRITE && it.canWrite() }
                ?: characteristics.firstOrNull { it.uuid == QC_WRITE }
        }
        var alternate = false
        if (writeChar == null && allowAlternate && store.alternateChannel.value) {
            writeChar = characteristics.firstOrNull { ch ->
                ch.canWrite() &&
                    ch.uuid != BATTERY_LEVEL &&
                    ch.service?.uuid != BATTERY_SERVICE
            }
            alternate = writeChar != null
        }
        val real = writeChar?.uuid == QC_WRITE
        update {
            copy(
                qcChannel = real,
                alternateChannel = alternate,
                name = gatt.device.name ?: name,
            )
        }
        log(
            when {
                real -> "Canal QC encontrado (serviço ${qcService?.uuid ?: "via scan"})"
                alternate -> "Usando canal alternativo ${writeChar?.uuid}"
                else -> "Canal QC não apareceu (serviço ${QC_SERVICE} ausente neste discovery)"
            },
        )
    }

    /** Hidden Android API — clears stale BLE attribute cache so QC can appear. */
    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val refreshed = gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean
            log("GATT refresh=${refreshed ?: "null"}")
        }.onFailure { log("GATT refresh indisponível: ${it.message}") }
    }

    /** Manual retry from UI when stuck on Linked / sem QC. */
    fun reopenQcChannel() {
        handler.post {
            val link = gatt
            if (link == null) {
                log("Sem GATT para reabrir QC — reconecte.")
                reconnectSaved()
                return@post
            }
            if (current().qcChannel && writeChar?.uuid == QC_WRITE) {
                log("QC já está aberto.")
                handler.removeCallbacks(qcHuntRunnable)
                qcHuntAttempt = 0
                return@post
            }
            discoverPass = 0
            bondingForQc = false
            queueStarted = false
            notifyQueue.clear()
            update {
                copy(
                    phase = LinkPhase.Discovering,
                    status = "Reabrindo canal QC…",
                    qcChannel = false,
                    alternateChannel = false,
                    lastError = null,
                )
            }
            writeChar = null
            refreshGattCache(link)
            handler.postDelayed({
                if (gatt === link) runCatching { link.discoverServices() }
            }, 700)
        }
    }

    /** Drop GATT and reconnect — clears stubborn Android BLE caches better than refresh(). */
    fun forceQcReconnect(manual: Boolean = true) {
        handler.post {
            val address = current().address ?: store.deviceAddress.value
            if (address.isNullOrBlank()) {
                log("Sem endereço para forçar QC.")
                return@post
            }
            if (!manual && qcForceCount >= QC_FORCE_MAX) {
                update {
                    copy(
                        lastError = "QC não apareceu após reconexões. Feche o app DZZ oficial, desligue/ligue o Bluetooth e toque em Forçar reconexão.",
                    )
                }
                log(current().lastError.orEmpty())
                return@post
            }
            if (manual) qcForceCount = 0
            else qcForceCount += 1
            val name = current().name ?: store.deviceName.value
            log("Forçando reconexão GATT para liberar QC (auto=${!manual}, #$qcForceCount)…")
            handler.removeCallbacks(qcHuntRunnable)
            qcHuntAttempt = 0
            discoverPass = 0
            bondingForQc = false
            userGone = false
            reconnectAttempt = 0
            stopScanInternal()
            closeGatt()
            assembler.clear()
            update {
                copy(
                    phase = LinkPhase.Connecting,
                    status = "Reconectando para liberar QC…",
                    address = address,
                    name = name ?: this.name,
                    lastError = null,
                    qcChannel = false,
                    alternateChannel = false,
                )
            }
            handler.postDelayed({ openGatt(address) }, 450)
        }
    }

    private fun startNotifyQueue() {
        if (queueStarted) return
        queueStarted = true
        qcNotifyOk = false
        cccdRetries = 0
        pendingCccd = null
        val link = gatt ?: return
        notifyQueue.clear()
        val notify = link.services.flatMap { it.characteristics }
            .filter { it.canNotify() }
            .sortedByDescending { it.uuid == QC_NOTIFY }
        notifyQueue.addAll(notify)
        enableNextNotify()
    }

    private fun enableNextNotify() {
        if (notifyQueue.isEmpty()) {
            onChannelReady()
            return
        }
        val next = notifyQueue.removeFirst()
        val link = gatt ?: return
        link.setCharacteristicNotification(next, true)
        val descriptor = next.getDescriptor(CCCD)
        if (descriptor == null) {
            enableNextNotify()
            return
        }
        val indicate = (next.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        pendingCccd = next
        val started = if (Build.VERSION.SDK_INT >= 33) {
            writeDescriptorModern(link, descriptor, value)
        } else {
            descriptor.value = value
            link.writeDescriptor(descriptor)
        }
        if (!started) {
            pendingCccd = null
            log("Não iniciou CCCD em ${next.uuid}")
            enableNextNotify()
        }
    }

    private fun onChannelReady() {
        reconnectAttempt = 0
        val commandReady = writeChar != null && (current().qcChannel || current().alternateChannel)
        if (!commandReady) {
            // Still try standard battery (single GATT op after CCCD drained).
            gatt?.let { readStandardBattery(it) }
            val services = gatt?.services?.size ?: 0
            update {
                copy(
                    phase = LinkPhase.Linked,
                    status = "Conectado sem canal QC",
                    lastError = "BLE ok ($services serviços), mas de5bf72a não apareceu. Feche o app DZZ oficial e use Reabrir / Forçar reconexão.",
                )
            }
            log(current().status)
            scheduleQcHunt()
            return
        }
        handler.removeCallbacks(qcHuntRunnable)
        qcHuntAttempt = 0
        if (current().qcChannel) qcForceCount = 0
        val notifyWarning = if (current().qcChannel && !qcNotifyOk) {
            "Notify QC não confirmou; comandos podem falhar."
        } else {
            null
        }
        update {
            copy(
                phase = LinkPhase.Ready,
                status = when {
                    qcChannel -> "Conectado"
                    alternateChannel -> "Conectado por canal alternativo"
                    else -> "Conectado"
                },
                lastError = notifyWarning,
            )
        }
        log(current().status + (notifyWarning?.let { " ($it)" } ?: ""))
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 8_000)
        scope.launch { initialize() }
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        handler.postDelayed({
            if (current().phase == LinkPhase.Ready) {
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            }
        }, 5_000)
    }

    private fun scheduleQcHunt() {
        if (userGone) return
        handler.removeCallbacks(qcHuntRunnable)
        if (qcHuntAttempt >= QC_HUNT_MAX) {
            log("QC ainda ausente após $QC_HUNT_MAX tentativas — forçando reconexão GATT")
            qcHuntAttempt = 0
            forceQcReconnect(manual = false)
            return
        }
        qcHuntAttempt += 1
        val delayMs = when (qcHuntAttempt) {
            1 -> 1_800L
            2 -> 3_500L
            else -> 6_000L
        }
        log("Auto-hunt QC #$qcHuntAttempt em ${delayMs}ms")
        update {
            copy(status = "Conectado sem QC — tentando de novo ($qcHuntAttempt/$QC_HUNT_MAX)…")
        }
        handler.postDelayed(qcHuntRunnable, delayMs)
    }

    private val qcHuntRunnable = Runnable {
        if (userGone) return@Runnable
        val phase = current().phase
        val hasQc = current().qcChannel && writeChar?.uuid == QC_WRITE
        if (hasQc || phase == LinkPhase.Ready && current().qcChannel) return@Runnable
        if (phase != LinkPhase.Linked && phase != LinkPhase.Discovering) return@Runnable
        if (qcHuntAttempt >= QC_HUNT_MAX) {
            forceQcReconnect(manual = false)
        } else {
            reopenQcChannel()
        }
    }

    private fun maybeBondForQc(device: BluetoothDevice): Boolean {
        val state = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
        if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_BONDING) {
            log("Bond state=$state (QC ainda ausente)")
            return false
        }
        val started = runCatching { device.createBond() }.getOrDefault(false)
        log("createBond para QC → $started")
        return started
    }

    private suspend fun initialize() {
        // Battery / info only after MTU + CCCD — keeps GATT ops serial with commandMutex writes.
        send(QcProtocol.SYNC_TIME, QcProtocol.timePayload(), 1_500)
        val battery = send(QcProtocol.BATTERY, byteArrayOf(), 2_000)
        if (battery != null) applyBattery(battery.payload)
        val info = send(QcProtocol.DEVICE_INFO, byteArrayOf(), 2_000)
        if (info != null) {
            val text = printable(info.payload)
            if (text.isNotBlank()) update { copy(firmware = text) }
        }
    }

    private fun onLinkLost(status: Int) {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(mtuFallback)
        handler.removeCallbacks(qcHuntRunnable)
        readyReset()
        bondingForQc = false
        handler.post { closeGatt() }
        val message = if (status == BluetoothGatt.GATT_SUCCESS) "Conexão encerrada" else gattMessage(status)
        update {
            copy(
                phase = if (userGone) LinkPhase.Idle else LinkPhase.Failed,
                status = if (userGone) "Desconectado" else "Reconectando…",
                qcChannel = false,
                alternateChannel = false,
                mode = "parado",
                lastError = if (userGone) null else message,
            )
        }
        if (!userGone && store.keepAlive.value) {
            log(message)
            // GATT 133 and flaky links recover faster with a short first retry.
            val delayMs = when {
                reconnectAttempt == 0 -> 900L
                reconnectAttempt == 1 -> 1_800L
                else -> (2_500L shl (reconnectAttempt - 2).coerceAtMost(3)).coerceAtMost(20_000L)
            }
            reconnectAttempt++
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, delayMs)
        }
    }

    private fun readyReset() {
        queueStarted = false
        writeInFlight = false
        writeChar = null
        pending?.deferred?.cancel()
        pending = null
        pendingCccd = null
        cccdRetries = 0
        qcNotifyOk = false
        notifyQueue.clear()
        writeQueue.clear()
        handler.removeCallbacks(writePaceFallback)
        assembler.clear()
        thumbWaiter?.complete(null)
        thumbWaiter = null
        thumbProgress = null
        thumbChunks.reset()
        photoReady = null
        speakHold?.let { handler.removeCallbacks(it) }
        speakHold = null
    }

    private fun accept(value: ByteArray) {
        if (value.isEmpty()) return
        for (frame in assembler.push(value)) {
            val waiter = pending
            if (waiter != null && waiter.cmd == frame.cmd && !waiter.deferred.isCompleted) {
                waiter.deferred.complete(frame)
            }
            interpret(frame)
        }
    }

    private fun interpret(frame: QcFrame) {
        when (frame.cmd) {
            QcProtocol.BATTERY -> applyBattery(frame.payload)
            QcProtocol.DEVICE_INFO -> {
                val text = printable(frame.payload)
                if (text.isNotBlank()) update { copy(firmware = text) }
            }
            QcProtocol.GPT_UPLOAD -> {
                if (frame.payload.isNotEmpty()) _micOpus.tryEmit(frame.payload)
                audioCount += 1
                if (audioCount == 1 || audioCount % 10 == 0) {
                    update { copy(audioPackets = audioCount, mode = "escuta") }
                }
            }
            QcProtocol.THUMBNAIL -> if (thumbWaiter != null) collectThumb(frame.payload)
            QcProtocol.DATA_REPORT -> {
                // Device report: event 2 = media button / AI image ready (DZZ: loadData[6] == 2).
                val event = frame.payload.firstOrNull()?.toInt()?.and(0xFF)
                if (event == 2) photoReady?.takeIf { !it.isCompleted }?.complete(Unit)
                if (event != null && event != 2) Log.d(TAG, "aviso dos óculos 0x73 evento=$event")
            }
        }
        if (frame.cmd != QcProtocol.GPT_UPLOAD && frame.cmd != QcProtocol.HEARTBEAT) {
            applyWifi(QcWifi.parse(frame.cmd, frame.payload))
        }
        if (frame.payload.size >= 2 && (frame.payload[0].toInt() and 0xFF) == 0x05) {
            val level = frame.payload[1].toInt() and 0xFF
            if (level in 0..100) {
                update { copy(battery = level, charging = frame.payload.size > 2 && frame.payload[2].toInt() != 0) }
            }
        }
    }

    private fun collectThumb(payload: ByteArray) {
        val index = thumbChunks.add(payload)
        if (index < 0) {
            Log.d(TAG, "pedaço de miniatura inválido (${payload.size} bytes)")
            return
        }
        thumbProgress?.value = thumbChunks.count.toLong()
        if (index + 1 < thumbChunks.total) {
            fire(QcProtocol.THUMBNAIL, ThumbnailChunks.request(index + 1))
            return
        }
        val missing = thumbChunks.firstMissing()
        if (missing >= 0) {
            fire(QcProtocol.THUMBNAIL, ThumbnailChunks.request(missing))
            return
        }
        val jpeg = thumbChunks.jpeg()
        if (jpeg == null) {
            log("Miniatura com pedaços faltando ou JPEG inválido (${thumbChunks.count}/${thumbChunks.total}).")
            return
        }
        thumbWaiter?.takeIf { !it.isCompleted }?.complete(jpeg)
    }

    private fun applyBattery(payload: ByteArray) {
        if (payload.isEmpty()) return
        val level = payload[0].toInt() and 0xFF
        if (level !in 0..100) return
        update { copy(battery = level, charging = payload.size > 1 && (payload[1].toInt() and 0xFF) != 0) }
    }

    private fun applyWifi(hint: WifiHint) {
        if (hint.ssid == null && hint.password == null && hint.ip == null) return
        update {
            copy(
                wifiSsid = hint.ssid ?: wifiSsid,
                wifiPassword = hint.password ?: wifiPassword,
                wifiIp = hint.ip ?: wifiIp,
            )
        }
        if (hint.ssid != null || hint.ip != null) {
            log("Wi-Fi ${hint.ssid.orEmpty()} ${hint.ip.orEmpty()}".trim())
        }
    }

    private fun applyRead(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == BATTERY_LEVEL && value.isNotEmpty()) {
            val level = value[0].toInt() and 0xFF
            if (level in 0..100) update { copy(battery = level) }
        }
    }

    private fun readStandardBattery(gatt: BluetoothGatt) {
        val characteristic = gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL) ?: return
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return
        gatt.readCharacteristic(characteristic)
    }

    private fun enqueueWrite(packet: ByteArray) {
        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < packet.size) {
            val end = minOf(offset + chunkSize, packet.size)
            writeQueue.addLast(packet.copyOfRange(offset, end))
            offset = end
        }
        drainWrite()
    }

    private fun drainWrite() {
        if (writeInFlight) return
        val chunk = writeQueue.pollFirst() ?: return
        val characteristic = writeChar ?: return
        val link = gatt ?: return
        writeInFlight = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            writeCharacteristicModern(link, characteristic, chunk)
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = chunk
            link.writeCharacteristic(characteristic)
        }
        if (!ok) {
            // The stack is busy (another GATT op pending): keep the chunk and retry.
            writeInFlight = false
            writeRetries += 1
            if (writeRetries <= MAX_WRITE_RETRIES) {
                writeQueue.addFirst(chunk)
                handler.postDelayed({ drainWrite() }, 30L * writeRetries)
            } else {
                writeRetries = 0
                writeDrops += 1
                log("Falha ao escrever no óculos (descartado após $MAX_WRITE_RETRIES tentativas)")
                handler.postDelayed({ drainWrite() }, 40)
            }
            return
        }
        writeRetries = 0
        // WRITE_TYPE_DEFAULT always answers via onCharacteristicWrite; this is only a safety net.
        handler.removeCallbacks(writePaceFallback)
        handler.postDelayed(writePaceFallback, WRITE_FALLBACK_MS)
    }

    private val writePaceFallback = Runnable {
        if (!writeInFlight) return@Runnable
        writeInFlight = false
        drainWrite()
    }

    private fun stopScanInternal() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        update {
            if (phase == LinkPhase.Scanning) copy(phase = LinkPhase.Idle, status = "Desconectado") else this
        }
    }

    private fun writeDescriptorModern(
        link: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return link.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    }

    private fun writeCharacteristicModern(
        link: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Boolean {
        return link.writeCharacteristic(
            characteristic,
            chunk,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) == BluetoothStatusCodes.SUCCESS
    }

    private fun closeGatt() {
        queueStarted = false
        writeInFlight = false
        writeChar = null
        notifyQueue.clear()
        writeQueue.clear()
        handler.removeCallbacks(writePaceFallback)
        val link = gatt ?: return
        runCatching { link.disconnect() }
        runCatching { link.close() }
        gatt = null
    }

    private fun logServices(gatt: BluetoothGatt) {
        gatt.services.forEach { service ->
            log("serviço ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                log("  ${characteristic.uuid} prop=${characteristic.properties}")
            }
        }
    }

    private fun update(block: GlassesSnapshot.() -> GlassesSnapshot) {
        val next = synchronized(stateLock) {
            snap = snap.block()
            snap
        }
        _snapshot.value = next
    }

    private fun current(): GlassesSnapshot = synchronized(stateLock) { snap }

    private fun bondedGlasses(): List<ScanHit> {
        val radio = adapter ?: return emptyList()
        val devices = runCatching { radio.bondedDevices.orEmpty() }.getOrElse {
            log("Não foi possível ler os aparelhos pareados: ${it.message}")
            return emptyList()
        }
        return devices.mapNotNull { device ->
            val name = runCatching { device.name }.getOrNull() ?: return@mapNotNull null
            if (!DeviceNames.looksLikeGlasses(name)) return@mapNotNull null
            ScanHit(
                name = name,
                address = device.address,
                rssi = 0,
                likely = true,
                audioRoute = audioConnected(device),
            )
        }
    }

    private fun audioConnected(device: BluetoothDevice): Boolean {
        val link = manager ?: return false
        return listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP).any { profile ->
            runCatching {
                link.getConnectionState(device, profile) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        val stamp = clock.format(LocalTime.now())
        synchronized(logLines) {
            logLines.addLast("$stamp $line")
            while (logLines.size > 40) logLines.removeFirst()
            _diagnostics.value = logLines.joinToString("\n")
        }
    }

    private fun gattMessage(status: Int): String = when (status) {
        133 -> "O rádio Bluetooth falhou (133). Feche o app DZZ oficial, desligue e ligue o Bluetooth e tente de novo."
        8 -> "A conexão expirou. Aproxime os óculos."
        19 -> "O óculos encerrou a conexão."
        else -> "Bluetooth retornou o código $status."
    }

    private class Pending(val cmd: Int, val deferred: CompletableDeferred<QcFrame>)

    companion object {
        private const val TAG = "DzzGlasses"
        private const val QC_DISCOVER_PASSES = 4
        private const val QC_HUNT_MAX = 3
        private const val QC_FORCE_MAX = 2
        private const val MAX_WRITE_RETRIES = 6
        private const val WRITE_FALLBACK_MS = 600L
        private const val CHUNK_STALL_MS = 1_300L
        private const val FIRST_CHUNK_MS = 4_000L
        private const val MAX_CHUNK_RESENDS = 12
        private const val THUMB_TOTAL_MS = 40_000L
        val QC_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
        val QC_WRITE: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
        val QC_NOTIFY: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        private val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val JPEG_END = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        private fun BluetoothGattCharacteristic.canWrite(): Boolean {
            val mask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            return properties and mask != 0
        }

        private fun BluetoothGattCharacteristic.canNotify(): Boolean {
            val mask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
            return properties and mask != 0
        }

        private fun containsJpeg(payload: ByteArray): Boolean = payload.indexOfSlice(JPEG_START) >= 0

        private fun ByteArray.indexOfSlice(needle: ByteArray, from: Int = 0): Int {
            if (needle.isEmpty() || size < from + needle.size) return -1
            for (index in from..size - needle.size) {
                var same = true
                for (offset in needle.indices) {
                    if (this[index + offset] != needle[offset]) {
                        same = false
                        break
                    }
                }
                if (same) return index
            }
            return -1
        }

        private fun printable(payload: ByteArray): String {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.length >= 4) parts += current.toString()
                current.setLength(0)
            }
            for (byte in payload) {
                val code = byte.toInt() and 0xFF
                if (code in 32..126) current.append(code.toChar()) else flush()
            }
            flush()
            return parts.joinToString(" · ")
        }
    }
}

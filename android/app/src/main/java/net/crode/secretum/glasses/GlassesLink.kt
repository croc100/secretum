package net.crode.secretum.glasses

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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * 안경(ESP32) BLE 연결.
 *
 * 안드로이드 GATT는 동시에 한 작업만 처리하므로 모든 쓰기/설정을 [gattOp] 직렬 큐로 보낸다.
 * (이걸 안 하면 연결 직후 CCCD 쓰기 등이 조용히 씹히는 게 BLE 앱 불안정의 1순위 원인)
 * 끊기면 자동 재연결.
 */
@SuppressLint("MissingPermission") // 권한은 MainActivity에서 확보 후 connect() 호출
class GlassesLink(private val context: Context, private val scope: CoroutineScope) {

    enum class State { DISCONNECTED, SCANNING, CONNECTING, READY }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val _buttons = MutableSharedFlow<ButtonEvent>(extraBufferCapacity = 16)
    val buttons: SharedFlow<ButtonEvent> = _buttons

    private val images = MutableSharedFlow<ImageAssembler.Result>(extraBufferCapacity = 4)

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private val assembler = ImageAssembler()
    private var wantConnected = false
    private var scanJob: Job? = null

    private val opMutex = Mutex()
    private val captureMutex = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null

    // ---------- 연결 ----------

    fun connect() {
        wantConnected = true
        if (_state.value != State.DISCONNECTED) return
        startScan()
    }

    fun disconnect() {
        wantConnected = false
        scanJob?.cancel()
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        gatt?.disconnect()
        gatt?.close()
        cleanup()
    }

    private fun startScan() {
        val scanner = adapter.bluetoothLeScanner ?: return
        _state.value = State.SCANNING
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GlassesProtocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanJob = scope.launch {
            delay(15_000)
            if (_state.value == State.SCANNING) {
                scanner.stopScan(scanCallback)
                _state.value = State.DISCONNECTED
                if (wantConnected) { delay(5_000); connect() }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_state.value != State.SCANNING) return
            adapter.bluetoothLeScanner?.stopScan(this)
            scanJob?.cancel()
            _state.value = State.CONNECTING
            // TODO(security): 출시 전 본딩 + 특정 기기 주소 고정. 지금은 서비스 UUID만 맞으면 붙음.
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private fun cleanup() {
        pendingOp?.completeExceptionally(IOException("disconnected"))
        pendingOp = null
        ctrl = null
        gatt = null
        _state.value = State.DISCONNECTED
    }

    private suspend fun setup(g: BluetoothGatt) {
        try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            gattOp { it.requestMtu(517) }
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            gattOp(10_000) { it.discoverServices() }

            val svc = g.getService(GlassesProtocol.SERVICE) ?: throw IOException("service not found")
            ctrl = svc.getCharacteristic(GlassesProtocol.CHR_CTRL)
            for (uuid in listOf(GlassesProtocol.CHR_IMG, GlassesProtocol.CHR_BTN)) {
                val chr = svc.getCharacteristic(uuid) ?: throw IOException("characteristic $uuid missing")
                g.setCharacteristicNotification(chr, true)
                val cccd = chr.getDescriptor(GlassesProtocol.CCCD) ?: throw IOException("CCCD missing")
                gattOp {
                    it.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                }
            }
            _state.value = State.READY
            haptic(Haptic.READY)
        } catch (e: Exception) {
            g.disconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch { setup(g) }
            } else {
                // 정상 끊김이든 연결 실패(status 133 등)든 GATT를 닫고 처음부터 재시도
                g.close()
                cleanup()
                if (wantConnected) scope.launch { delay(2_000); connect() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = completeOp(status)
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = completeOp(status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = completeOp(status)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = completeOp(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                GlassesProtocol.CHR_IMG -> assembler.feed(value)?.let { images.tryEmit(it) }
                GlassesProtocol.CHR_BTN -> ButtonEvent.parse(value)?.let { _buttons.tryEmit(it) }
            }
        }
    }

    // ---------- GATT 직렬 큐 ----------

    private fun completeOp(status: Int) {
        pendingOp?.complete(status)
    }

    /** start가 true면 콜백(completeOp)까지 대기. GATT_SUCCESS가 아니면 예외. */
    private suspend fun gattOp(timeoutMs: Long = 5_000, start: (BluetoothGatt) -> Boolean) = opMutex.withLock {
        val g = gatt ?: throw IOException("not connected")
        val d = CompletableDeferred<Int>()
        pendingOp = d
        try {
            if (!start(g)) throw IOException("gatt op rejected")
            val status = withTimeout(timeoutMs) { d.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("gatt status $status")
        } finally {
            pendingOp = null
        }
    }

    private suspend fun writeCtrl(bytes: ByteArray) {
        val c = ctrl ?: throw IOException("not ready")
        gattOp {
            it.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        }
    }

    // ---------- 공개 API ----------

    /** 안경 카메라로 1장 촬영해 JPEG 반환. 손상되면 1회 재시도. */
    suspend fun capture(size: FrameSize = FrameSize.VGA, quality: Int = 12, timeoutMs: Long = 8_000): ByteArray =
        captureMutex.withLock {
            var lastError: Exception? = null
            repeat(2) {
                try {
                    return@withLock captureOnce(size, quality, timeoutMs)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("capture failed")
        }

    private suspend fun captureOnce(size: FrameSize, quality: Int, timeoutMs: Long): ByteArray = coroutineScope {
        if (_state.value != State.READY) throw IOException("glasses not ready")
        // 트리거 전에 구독부터 (SharedFlow는 replay 없음)
        val result = async(start = CoroutineStart.UNDISPATCHED) { images.first() }
        writeCtrl(byteArrayOf(GlassesProtocol.OP_CAPTURE, size.code.toByte(), quality.toByte()))
        when (val r = withTimeout(timeoutMs) { result.await() }) {
            is ImageAssembler.Result.Image -> r.jpeg
            is ImageAssembler.Result.Error -> throw IOException("glasses image error ${r.code}")
        }
    }

    /** fire-and-forget 햅틱. */
    fun haptic(h: Haptic) {
        if (_state.value != State.READY) return
        scope.launch { runCatching { writeCtrl(byteArrayOf(GlassesProtocol.OP_HAPTIC, h.id.toByte())) } }
    }
}

package eu.darken.octi.syncs.jserver.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.octi.common.coroutine.AppScope
import eu.darken.octi.common.coroutine.DispatcherProvider
import eu.darken.octi.common.debug.logging.Logging.Priority.*
import eu.darken.octi.common.debug.logging.asLog
import eu.darken.octi.common.debug.logging.log
import eu.darken.octi.common.debug.logging.logTag
import eu.darken.octi.common.flow.DynamicStateFlow
import eu.darken.octi.common.flow.setupCommonEventHandlers
import eu.darken.octi.module.core.ModuleId
import eu.darken.octi.sync.core.*
import eu.darken.octi.sync.core.encryption.PayloadEncryption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant


@Suppress("BlockingMethodInNonBlockingContext")
class JServerConnector @AssistedInject constructor(
    @Assisted val credentials: JServer.Credentials,
    @AppScope private val scope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val endpointFactory: JServerEndpoint.Factory,
    private val syncSettings: SyncSettings,
    private val supportedModuleIds: Set<@JvmSuppressWildcards ModuleId>,
) : SyncConnector {

    private val endpoint by lazy {
        endpointFactory.create(credentials.serverAdress).also {
            it.setCredentials(credentials)
        }
    }

    private val crypti by lazy { PayloadEncryption(credentials.encryptionKeyset) }

    data class State(
        override val readActions: Int = 0,
        override val writeActions: Int = 0,
        override val lastReadAt: Instant? = null,
        override val lastWriteAt: Instant? = null,
        override val lastError: Exception? = null,
        override val quota: SyncConnectorState.Quota? = null,
        override val devices: Collection<DeviceId>? = null,
        override val isAvailable: Boolean = true,
    ) : SyncConnectorState

    private val _state = DynamicStateFlow(
        parentScope = scope + dispatcherProvider.IO,
        loggingTag = TAG,
    ) {
        State()
    }

    override val state: Flow<State> = _state.flow
    private val _data = MutableStateFlow<SyncRead?>(null)
    override val data: Flow<SyncRead?> = _data

    private val writeQueue = MutableSharedFlow<SyncWrite>()
    private val writeLock = Mutex()
    private val readLock = Mutex()

    override val identifier: ConnectorId = ConnectorId(
        type = "jserver",
        subtype = credentials.serverAdress.domain,
        account = credentials.accountId.id,
    )

    init {
        writeQueue
            .onEach { toWrite ->
                writeAction {
                    writeServer(toWrite)
                }
            }
            .retry {
                delay(5000)
                true
            }
            .setupCommonEventHandlers(TAG) { "writeQueue" }
            .launchIn(scope)
    }

    override suspend fun write(toWrite: SyncWrite) {
        log(TAG) { "write(toWrite=$toWrite)" }
        writeQueue.emit(toWrite)
    }

    override suspend fun deleteAll() {
        log(TAG, INFO) { "wipe()" }
        writeAction {
            // TODO
        }
    }

    override suspend fun deleteDevice(deviceId: DeviceId) {
        log(TAG, INFO) { "deleteDevice(deviceId=$deviceId)" }
        writeAction {
            // TODO
        }
    }

    suspend fun createLinkCode(): LinkingData {
        log(TAG) { "createLinkCode()" }
        val linkCode = endpoint.createLinkCode()

        return LinkingData(
            serverAdress = credentials.serverAdress,
            linkCode = linkCode,
            encryptionKeyset = credentials.encryptionKeyset,
        )
    }

    private suspend fun readServer(): JServerData {
        log(TAG, DEBUG) { "readServer(): Starting..." }
        val deviceIds = endpoint.listDevices()
        log(TAG, VERBOSE) { "readServer(): Found devices: $deviceIds" }

        val devices = deviceIds.map { deviceId ->
            val modules = supportedModuleIds.mapNotNull { moduleId ->
                val readData = endpoint.readModule(deviceId = deviceId, moduleId = moduleId)

                if (readData.payload.size == 0) {
                    log(TAG, WARN) { "readServer(): Module payload is empty: $moduleId" }
                    return@mapNotNull null
                }

                JServerModuleData(
                    connectorId = identifier,
                    deviceId = deviceId,
                    moduleId = moduleId,
                    modifiedAt = Instant.now(),
//                    payload = crypti.decrypt(readData.payload),
                    payload = readData.payload,
                ).also {
                    log(TAG, VERBOSE) { "readServer(): Module data: $it" }
                }
            }
            JServerDeviceData(
                deviceId = deviceId,
                modules = modules,
            )
        }

        return JServerData(
            connectorId = identifier,
            devices = devices
        )
    }

    private suspend fun writeServer(data: SyncWrite) {
        log(TAG, DEBUG) { "writeServer(): $data)" }
        data.modules.forEach { module ->
            endpoint.writeModule(
                moduleId = module.moduleId,
//                payload = crypti.encrypt(module.payload),
                payload = module.payload,
            )
        }
        log(TAG, VERBOSE) { "writeServer(): Done" }
    }

    private fun getStorageStats(): SyncConnectorState.Quota {
        log(TAG, VERBOSE) { "getStorageStats()" }

        return SyncConnectorState.Quota()
    }

    private suspend fun readAction(block: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        log(TAG, VERBOSE) { "readAction(block=$block)" }

        _state.updateBlocking { copy(readActions = readActions + 1) }

        var newStorageQuota: SyncConnectorState.Quota? = null

        try {
            block()

            val lastStats = _state.value().quota?.updatedAt
            if (lastStats == null || Duration.between(lastStats, Instant.now()) > Duration.ofSeconds(60)) {
                log(TAG) { "readAction(block=$block): Updating storage stats" }
                newStorageQuota = getStorageStats()
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "readAction(block=$block) failed: ${e.asLog()}" }
            throw e
        } finally {
            _state.updateBlocking {
                copy(
                    readActions = readActions - 1,
                    quota = newStorageQuota ?: quota,
                    lastReadAt = Instant.now(),
                )
            }
        }

        log(TAG, VERBOSE) { "readAction(block=$block) finished after ${System.currentTimeMillis() - start}ms" }
    }

    private suspend fun writeAction(block: suspend () -> Unit) = withContext(NonCancellable) {
        val start = System.currentTimeMillis()
        log(TAG, VERBOSE) { "writeAction(block=$block)" }

        _state.updateBlocking { copy(writeActions = writeActions + 1) }

        try {
            writeLock.withLock {
                try {
                    block()
                } catch (e: Exception) {
                    log(TAG, ERROR) { "writeAction(block=$block) failed: ${e.asLog()}" }
                    throw e
                }
            }
        } finally {
            _state.updateBlocking {
                log(TAG, VERBOSE) { "writeAction(block=$block) finished" }
                copy(
                    writeActions = writeActions - 1,
                    lastWriteAt = Instant.now(),
                )
            }
            log(TAG, VERBOSE) { "writeAction(block=$block) finished after ${System.currentTimeMillis() - start}ms" }
        }
    }

    override suspend fun sync(options: SyncOptions) {
        log(TAG) { "sync(options=$options)" }

        if (options.writeData) {
            // TODO
        }

        if (options.readData) {
            log(TAG) { "read()" }
            try {
                readAction {
                    _data.value = readServer()
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to read: ${e.asLog()}" }
                _state.updateBlocking { copy(lastError = e) }
            }
        }

        if (options.stats) {
            try {
                val knownDeviceIds = endpoint.listDevices()
                _state.updateBlocking { copy(devices = knownDeviceIds) }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to list of known devices: ${e.asLog()}" }
            }
        }
    }

    suspend fun checkHealth(): JServerApi.Health {
        log(TAG) { "checkHealth()" }
        return endpoint.getHealth()
    }

    @AssistedFactory
    interface Factory {
        fun create(account: JServer.Credentials): JServerConnector
    }

    companion object {
        private val TAG = logTag("Sync", "JServer", "Connector")
    }
}
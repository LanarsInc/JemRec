package com.jemcik.jemrec.ui

import android.app.Application
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.Intent
import androidx.core.content.FileProvider
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.summary.SummaryRunner
import com.jemcik.jemrec.transcripts.export.ExportDocument
import com.jemcik.jemrec.transcripts.export.ExportLabels
import com.jemcik.jemrec.transcripts.export.TranscriptExport
import com.jemcik.jemrec.transcripts.export.TranscriptExportFile
import com.jemcik.jemrec.transcripts.export.TranscriptFormat
import com.jemcik.jemrec.summary.CallSummary
import com.jemcik.jemrec.summary.SummaryModel
import com.jemcik.jemrec.summary.SummaryWorker
import com.jemcik.jemrec.transcribe.ModelChoice
import com.jemcik.jemrec.transcribe.Sides
import com.jemcik.jemrec.transcribe.TranscribeMode
import com.jemcik.jemrec.transcribe.TranscriptionScheduler
import com.jemcik.jemrec.transcribe.TranscriptionLanguageChoice
import com.jemcik.jemrec.transcribe.TranscribeSettings
import com.jemcik.jemrec.transcribe.TranscriptionEstimate
import com.jemcik.jemrec.transcribe.TranscriptionWorker
import com.jemcik.jemrec.transcribe.model.DownloadableModel
import com.jemcik.jemrec.transcribe.model.ModelDownloadWorker
import com.jemcik.jemrec.transcribe.model.ModelRepository
import com.jemcik.jemrec.transcribe.model.SpeechModel
import com.jemcik.jemrec.transcripts.Transcript
import com.jemcik.jemrec.transcripts.TranscriptRepository
import com.jemcik.jemrec.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the speech model for this transcript stands. */
sealed interface ModelState {
    data object Checking : ModelState
    /** Not on the phone: [model], [bytes] to download. */
    data class Missing(val model: SpeechModel, val bytes: Long, val partialBytes: Long) : ModelState
    data class Downloading(val model: SpeechModel, val percent: Int) : ModelState
    data class DownloadFailed(val model: SpeechModel, val reason: String) : ModelState
    data class Ready(val label: String) : ModelState
}

/** Where the summary model stands on this phone. */
sealed interface SummaryModelState {
    data object Checking : SummaryModelState
    /** No summary model fits this phone: the smallest needs a phone with [needsGb] GB. */
    data class TooSmall(val needsGb: Int) : SummaryModelState
    data class Missing(val model: SummaryModel, val partialBytes: Long) : SummaryModelState
    data class Downloading(val model: SummaryModel, val percent: Int) : SummaryModelState
    data class DownloadFailed(val model: SummaryModel, val reason: String) : SummaryModelState
    data class Ready(val model: SummaryModel) : SummaryModelState
}

/** Which half of the Transcript screen is showing. */
enum class TranscriptTab { TRANSCRIPT, SUMMARY }

/** Where a transcription of this recording stands. */
sealed interface JobState {
    data object Idle : JobState
    data object Queued : JobState
    /** [percent] across the whole call; null while the model loads. */
    data class Running(val percent: Int?) : JobState
    data class Failed(val reason: String) : JobState
}

/** What the Transcript screen shows. */
data class TranscriptUi(
    val recording: Recording? = null,
    val transcript: Transcript? = null,
    val model: ModelState = ModelState.Checking,
    val job: JobState = JobState.Idle,
    /** How long a transcription is expected to take, in ms; null when the model is not there. */
    val estimateMs: Long? = null,
    /** The language this recording will be transcribed in: an ISO code, or null to detect. */
    val language: String? = null,
    /** Chosen for this recording alone, over Settings ("auto" to detect); null when Settings decides. */
    val languageOverride: String? = null,
    val tab: TranscriptTab = TranscriptTab.TRANSCRIPT,
    val summary: CallSummary? = null,
    /** The model that wrote [summary]. */
    val summaryModelId: String? = null,
    val summaryWallMs: Long? = null,
    val summaryModel: SummaryModelState = SummaryModelState.Checking,
    val summaryJob: JobState = JobState.Idle,
    /** A line to bring into view, from a tap on a summary point: its time in ms. */
    val jumpToMs: Long? = null,
)

/** One speech model's row in Settings. */
data class ModelRow(
    val model: SpeechModel,
    /** On the phone and verified. */
    val installed: Boolean,
    /** Downloading now: how far; null otherwise. */
    val downloading: Int?,
    /** Left from an interrupted download, in bytes. */
    val partialBytes: Long,
    val recommended: Boolean,
)

/** One summary model's row in Settings. */
data class SummaryModelRow(
    val model: SummaryModel,
    /** This phone can hold it (SummaryModel.fits). */
    val fits: Boolean,
    val installed: Boolean,
    val downloading: Int?,
    val partialBytes: Long,
)

/** Settings > Transcripts and summaries. */
data class TranscriptsSettingsUi(
    /** The Settings language: an ISO code or "auto", which is also what not having chosen means. */
    val language: String = TranscriptionLanguageChoice.AUTO,
    val models: List<ModelRow> = emptyList(),
    /** The model chosen as the general one, or the recommendation. */
    val chosenModel: SpeechModel? = null,
    val languageModels: Boolean = true,
    val sides: Sides = Sides.AUTO,
    val wifiOnly: Boolean = true,
    /** Imported model files, debug build only. */
    val imported: List<String> = emptyList(),
    val summaryModels: List<SummaryModelRow> = emptyList(),
    val mode: TranscribeMode = TranscribeMode.AFTER_CALL,
    val chargingOnly: Boolean = true,
    val summariseAfter: Boolean = true,
    /** What the models take on the phone, and what the transcripts and summaries do. */
    val modelsBytes: Long = 0,
    val transcriptsBytes: Long = 0,
    /** The one summaries use: chosen and fitting, else the best that fits. */
    val summaryModel: SummaryModel? = null,
)

/**
 * The Transcript screen's state and actions, beside MainViewModel the way
 * AnswerController is: transcripts have state of their own (the database, the
 * work queue, the model file) that the rest of UiState does not need.
 *
 * Opening a recording watches three things for it until another is opened:
 * its transcript in the database, its transcription in WorkManager, and the
 * download of the model it would use.
 */
class TranscriptsController(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TranscriptUi())
    val state: StateFlow<TranscriptUi> = _state.asStateFlow()

    private val _settings = MutableStateFlow(TranscriptsSettingsUi())
    val settingsState: StateFlow<TranscriptsSettingsUi> = _settings.asStateFlow()
    private var watchingModels: List<Job> = emptyList()

    private val repository by lazy { TranscriptRepository.of(app) }
    private val settings by lazy { TranscribeSettings.of(app) }
    private var watching: List<Job> = emptyList()

    fun open(recording: Recording) {
        watching.forEach { it.cancel() }
        _state.value = TranscriptUi(recording = recording)
        watching = listOf(
            scope.launch {
                repository.observe(recording.name).collect { t -> _state.update { it.copy(transcript = t) } }
            },
            scope.launch {
                WorkManager.getInstance(app).getWorkInfosByTagFlow(TranscriptionWorker.tagFor(recording.name))
                    .collect { infos -> _state.update { it.copy(job = jobOf(infos)) } }
            },
            scope.launch(Dispatchers.IO) { refreshModel() },
            scope.launch {
                repository.observeSummary(recording.name).collect { entry ->
                    _state.update {
                        it.copy(
                            summary = entry?.let { e -> CallSummary.parse(e.json) },
                            summaryModelId = entry?.modelId,
                            summaryWallMs = entry?.wallMs,
                        )
                    }
                }
            },
            scope.launch {
                WorkManager.getInstance(app).getWorkInfosByTagFlow(SummaryWorker.tagFor(recording.name))
                    .collect { infos -> _state.update { it.copy(summaryJob = summaryJobOf(infos)) } }
            },
            scope.launch(Dispatchers.IO) { refreshSummaryModel() },
        )
    }

    fun showTab(tab: TranscriptTab) = _state.update { it.copy(tab = tab) }

    /** The transcript as one text, each line with its time and who said it: for Copy all. */
    fun plainText(): String? {
        val t = _state.value.transcript ?: return null
        return TranscriptExport.plainText(SummaryRunner.linesOf(t, _state.value.recording?.caller))
    }

    /**
     * The transcript, and its summary where the format has room, written as
     * [format] and handed to Android's share sheet. Nothing leaves the phone
     * unless the user picks where to send it there.
     */
    fun export(format: TranscriptFormat) {
        val ui = _state.value
        val recording = ui.recording ?: return
        val transcript = ui.transcript ?: return
        scope.launch(Dispatchers.IO) {
            val doc = ExportDocument(
                title = recording.caller ?: recording.whenLabel,
                segments = SummaryRunner.linesOf(transcript, recording.caller),
                summary = ui.summary,
                note = null,
                tags = emptyList(),
                language = transcript.facts.language,
                model = transcript.facts.modelId,
            )
            val text = TranscriptExport.render(format, doc, EXPORT_LABELS)
            val file = TranscriptExportFile.write(app, TranscriptExport.fileName(format, recording.name), text) ?: return@launch
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.recordings", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(format.mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { app.startActivity(Intent.createChooser(send, "Share transcript").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** From a summary point: back to the transcript, at the line said at [ms]. */
    fun jumpTo(ms: Long) = _state.update { it.copy(tab = TranscriptTab.TRANSCRIPT, jumpToMs = ms) }

    fun jumped() = _state.update { it.copy(jumpToMs = null) }

    fun summarise() {
        val recording = _state.value.recording ?: return
        SummaryWorker.enqueue(app, recording.name, recording.caller)
        _state.update { it.copy(summaryJob = JobState.Queued) }
    }

    fun cancelSummary() {
        val recording = _state.value.recording ?: return
        SummaryWorker.cancel(app, recording.name)
    }

    fun downloadSummaryModel() {
        val model = when (val m = _state.value.summaryModel) {
            is SummaryModelState.Missing -> m.model
            is SummaryModelState.DownloadFailed -> m.model
            else -> return
        }
        ModelDownloadWorker.enqueue(app, model, settings.modelsWifiOnly)
        watchSummaryDownload(model)
    }

    private fun refreshSummaryModel() {
        val model = SummaryWorker.chosenModel(app)
        val state = when {
            model == null -> SummaryModelState.TooSmall(SummaryModel.entries.minOf { it.needsGb })
            ModelRepository.isInstalled(app, model) -> SummaryModelState.Ready(model)
            else -> SummaryModelState.Missing(model, ModelRepository.partialBytes(app, model))
        }
        _state.update { it.copy(summaryModel = state) }
        if (state is SummaryModelState.Missing) scope.launch { watchSummaryDownload(state.model) }
    }

    private fun watchSummaryDownload(model: SummaryModel) {
        watching += scope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workNameFor(model)).collect { infos ->
                val info = infos.lastOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        _state.update { it.copy(summaryModel = SummaryModelState.Downloading(model, ModelDownloadWorker.percentOf(info.progress) ?: 0)) }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> launch(Dispatchers.IO) { refreshSummaryModel() }
                    WorkInfo.State.FAILED -> _state.update {
                        it.copy(summaryModel = SummaryModelState.DownloadFailed(model, downloadReason(info.outputData.getString(ModelDownloadWorker.KEY_ERROR))))
                    }
                }
            }
        }
    }

    /** Shows [ui] as it is, watching nothing: for the screen tests, which draw states the phone never has to be put in. */
    internal fun showForTest(ui: TranscriptUi) {
        watching.forEach { it.cancel() }
        watching = emptyList()
        _state.value = ui
    }

    fun close() {
        watching.forEach { it.cancel() }
        watching = emptyList()
        _state.value = TranscriptUi()
    }

    fun transcribe() {
        val recording = _state.value.recording ?: return
        TranscriptionWorker.enqueue(app, recording.name, recording.uri, recording.caller)
        // Queued at once, before WorkManager says so: the button goes away on
        // the tap, and a second tap cannot queue the same call twice (it did,
        // on the A24, 2026-10-29).
        _state.update { it.copy(job = JobState.Queued) }
    }

    fun cancel() {
        val recording = _state.value.recording ?: return
        TranscriptionWorker.cancel(app, recording.name)
    }

    /** This recording's own language, or null to go back to Settings'. */
    fun setLanguage(code: String?) {
        val recording = _state.value.recording ?: return
        scope.launch(Dispatchers.IO) {
            repository.setLanguage(recording.name, code)
            refreshModel()
        }
    }

    fun downloadModel() {
        val model = (_state.value.model as? ModelState.Missing)?.model
            ?: (_state.value.model as? ModelState.DownloadFailed)?.model ?: return
        ModelDownloadWorker.enqueue(app, model, settings.modelsWifiOnly)
        watchDownload(model)
    }

    /** Which model this recording needs, whether it is here, and how long a run would take. */
    private suspend fun refreshModel() {
        val recording = _state.value.recording ?: return
        val override = repository.languageFor(recording.name)
        val language = ModelChoice.language(override, settings.language)
        _state.update { it.copy(languageOverride = override) }
        val (model, ready) = ModelChoice.speechModelFor(app, language)
        if (ready != null) {
            val rtf = settings.measuredRtf(ready.id) ?: model?.realTimeFactor ?: TranscriptionEstimate.DEFAULT_RTF
            val load = settings.measuredLoadMs(ready.id) ?: model?.seedLoadMs ?: 0L
            val estimate = TranscriptionEstimate.estimateMs(recording.durationMillis, TranscriptionEstimate.RunCost(load, rtf))
            _state.update { it.copy(model = ModelState.Ready(ready.label), estimateMs = estimate, language = language) }
        } else if (model != null) {
            _state.update {
                it.copy(
                    model = ModelState.Missing(model, model.sizeBytes, ModelRepository.partialBytes(app, model)),
                    estimateMs = null,
                    language = language,
                )
            }
            watchDownload(model)
        }
    }

    private fun watchDownload(model: SpeechModel) {
        watching += scope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workNameFor(model)).collect { infos ->
                val info = infos.lastOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        val percent = ModelDownloadWorker.percentOf(info.progress) ?: 0
                        _state.update { it.copy(model = ModelState.Downloading(model, percent)) }
                    }
                    WorkInfo.State.SUCCEEDED -> launch(Dispatchers.IO) { refreshModel() }
                    WorkInfo.State.FAILED -> _state.update {
                        it.copy(model = ModelState.DownloadFailed(model, downloadReason(info.outputData.getString(ModelDownloadWorker.KEY_ERROR))))
                    }
                    WorkInfo.State.CANCELLED -> launch(Dispatchers.IO) { refreshModel() }
                }
            }
        }
    }

    // ---- Settings > Transcripts and summaries --------------------------------------------------

    /** Reads the settings and starts following each model's download, while the screen is up. */
    fun openSettings() {
        if (pinned) return
        watchingModels.forEach { it.cancel() }
        refreshSettings()
        watchingModels = (SpeechModel.entries + SummaryModel.entries).map { model ->
            scope.launch {
                WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workNameFor(model)).collect { infos ->
                    val info = infos.lastOrNull()
                    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
                    downloads = if (running) downloads + (model to (ModelDownloadWorker.percentOf(info!!.progress) ?: 0)) else downloads - model
                    refreshSettings()
                }
            }
        }
    }

    fun closeSettings() {
        watchingModels.forEach { it.cancel() }
        watchingModels = emptyList()
    }

    private var downloads: Map<DownloadableModel, Int> = emptyMap()

    private fun refreshSettings() {
        scope.launch(Dispatchers.IO) {
            val installed = ModelRepository.installedModels(app).toSet()
            val recommended = ModelChoice.recommended()
            val total = SummaryWorker.totalMemory(app)
            _settings.value = TranscriptsSettingsUi(
                language = settings.language ?: TranscriptionLanguageChoice.AUTO,
                models = SpeechModel.entries.map { m ->
                    ModelRow(m, m in installed, downloads[m], ModelRepository.partialBytes(app, m), m == recommended)
                },
                chosenModel = SpeechModel.fromId(settings.speechModelId) ?: recommended,
                languageModels = settings.languageModels,
                sides = settings.sides,
                wifiOnly = settings.modelsWifiOnly,
                imported = ModelRepository.importedModels(app).map { it.name },
                summaryModels = SummaryModel.entries.map { m ->
                    SummaryModelRow(m, m.fits(total), ModelRepository.isInstalled(app, m), downloads[m], ModelRepository.partialBytes(app, m))
                },
                summaryModel = SummaryWorker.chosenModel(app),
                mode = settings.mode,
                chargingOnly = settings.chargingOnly,
                summariseAfter = settings.summariseAfterTranscript,
                modelsBytes = ModelRepository.bytesUsed(app),
                transcriptsBytes = TranscriptDatabase.bytesUsed(app),
            )
        }
    }

    fun setSettingsLanguage(code: String) { settings.language = code; refreshSettings() }
    fun chooseModel(model: SpeechModel) { settings.speechModelId = model.id; refreshSettings() }
    fun setLanguageModels(on: Boolean) { settings.languageModels = on; refreshSettings() }
    fun setSides(sides: Sides) { settings.sides = sides; refreshSettings() }
    fun setWifiOnly(on: Boolean) { settings.modelsWifiOnly = on; refreshSettings() }
    fun setMode(mode: TranscribeMode) { settings.mode = mode; TranscriptionScheduler.apply(app); refreshSettings() }
    fun setChargingOnly(on: Boolean) { settings.chargingOnly = on; TranscriptionScheduler.apply(app); refreshSettings() }
    fun setSummariseAfter(on: Boolean) { settings.summariseAfterTranscript = on; refreshSettings() }

    fun chooseSummaryModel(model: SummaryModel) { settings.summaryModelId = model.id; refreshSettings() }

    fun download(model: DownloadableModel) {
        ModelDownloadWorker.enqueue(app, model, settings.modelsWifiOnly)
        downloads = downloads + (model to 0)
        refreshSettings()
    }

    fun cancelDownload(model: DownloadableModel) {
        ModelDownloadWorker.cancel(app, model)
    }

    fun delete(model: DownloadableModel) {
        scope.launch(Dispatchers.IO) {
            ModelRepository.delete(app, model)
            refreshSettings()
        }
    }

    /** Shows [ui] as it is: for the screen tests. */
    internal fun showSettingsForTest(ui: TranscriptsSettingsUi) {
        closeSettings()
        pinned = true
        _settings.value = ui
    }

    /** Set by the screen tests: the state shown is theirs, and nothing on the phone is read over it. */
    private var pinned = false

    companion object {
        /** The Markdown export's headings: the app speaks English. */
        private val EXPORT_LABELS = ExportLabels(
            summary = "Summary", notes = "Notes", transcript = "Transcript", keyPoints = "Key points",
            decisions = "Decisions", actionItems = "To do", keyFacts = "Facts", language = "Language", model = "Model",
        )

        /** The newest work for the recording decides: a finished one leaves Idle, the transcript shows. */
        internal fun jobOf(infos: List<WorkInfo>): JobState {
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            if (running != null) {
                return JobState.Running(TranscriptionWorker.percentOf(running.progress)?.takeIf { it > 0 })
            }
            if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return JobState.Queued
            val failed = infos.lastOrNull { it.state == WorkInfo.State.FAILED } ?: return JobState.Idle
            return when (val error = failed.outputData.getString(TranscriptionWorker.KEY_ERROR)) {
                TranscriptionWorker.ERROR_STOPPED, null -> JobState.Idle
                TranscriptionWorker.ERROR_NO_MODEL -> JobState.Failed("The speech model is not on the phone.")
                else -> JobState.Failed(error)
            }
        }

        internal fun summaryJobOf(infos: List<WorkInfo>): JobState {
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            if (running != null) return JobState.Running(running.progress.getInt(SummaryWorker.KEY_PERCENT, -1).takeIf { it > 0 })
            if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return JobState.Queued
            val failed = infos.lastOrNull { it.state == WorkInfo.State.FAILED } ?: return JobState.Idle
            return when (val error = failed.outputData.getString(SummaryWorker.KEY_ERROR)) {
                SummaryWorker.ERROR_STOPPED, null -> JobState.Idle
                SummaryWorker.ERROR_NO_MODEL -> JobState.Failed("The summary model is not on the phone.")
                SummaryWorker.ERROR_NO_ROOM -> JobState.Failed("Not enough free memory right now. Close other apps and try again.")
                SummaryWorker.ERROR_NO_TRANSCRIPT -> JobState.Failed("There is no transcript to summarise.")
                else -> JobState.Failed(error)
            }
        }

        internal fun downloadReason(error: String?): String = when (error) {
            ModelDownloadWorker.ERROR_NO_SPACE -> "Not enough free space on the phone."
            ModelDownloadWorker.ERROR_VERIFICATION_FAILED -> "The download arrived damaged twice. Try again later."
            else -> "The download did not finish."
        }
    }
}

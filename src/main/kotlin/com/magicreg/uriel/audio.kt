package com.magicreg.uriel

import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.jvm.JVMAudioInputStream
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchDetectionResult
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm
import javax.sound.midi.MidiChannel
import javax.sound.midi.Synthesizer
import javax.sound.sampled.*
import kotlin.math.ln
import kotlin.math.roundToInt


enum class AudioDeviceType {
    INPUT, OUTPUT, ANY
}

fun initAudioFunctions() {
    addFunction(Action("audio", null) { params ->
        var type: AudioDeviceType? = null
        var device: String? = null
        var action: String? = null
        var args = mutableListOf<Any?>()

        for (param in params) {
            if (type == null) {
                val key = getKey(param).toUpperCase()
                type = if (key.isEmpty()) AudioDeviceType.ANY else AudioDeviceType.valueOf(key)
            }
            else if (device == null)
                device = execute(param).toString()
            else if (action == null)
                action = getKey(param)
            else
                args.add(execute(param))
        }

        if (type == null)
            type = AudioDeviceType.ANY
        if (device == null)
            getAudioDevices(type)
        else if (action == null || action.isEmpty())
            getAudioDevice(device, type)
        else
            doAudioAction(action, getAudioDevice(device, type)!!, args)
    })
}

fun getAudioDevices(type: AudioDeviceType = AudioDeviceType.ANY): List<String> {
    initDevices()
    val devices = mutableListOf<String>()
    for (device in AUDIO_DEVICES) {
        val devType = device["type"]
        if (type == AudioDeviceType.ANY || devType == "ANY" || type.toString() == devType)
            devices.add(device["name"].toString())
    }
    return devices
}

fun getAudioDevice(label: String, type: AudioDeviceType = AudioDeviceType.ANY): Mixer? {
    val txt = label.toLowerCase()
    for (device in AUDIO_DEVICES) {
        val devType = device["type"]
        if (type == AudioDeviceType.ANY || devType == "ANY" || type.toString() == devType) {
            val name = device["name"]!!
            if (name.toLowerCase().contains(txt))
                return AudioSystem.getMixer(MIXER_INFOS[name])
        }
    }
    return null
}

fun doAudioAction(action: String, mixer: Mixer, params: List<Any?>): Any? {
    return when (action.toLowerCase()) {
        "detect", "pitch", "detectpitch", "pitchdetection" -> {
            if (params.isEmpty())
                PitchEstimationAlgorithm.values().map { it.toString().toLowerCase() }
            else {
                val target = if (params.size < 2) null else execute(params[1])
                detectPitch(mixer, params[0].toString(), target)
            }
        }
        else -> throw RuntimeException("Unknown audio action: $action")
    }
}

fun hertzToNote(hertz: Float): Note {
    val level = ln(hertz) / LOG_2 - 4
    val step = ((level - NOTE_BASE) * 12).roundToInt()
    return Note(NOTE_NAMES[step % 12] + (step / 12))
}

private val AUDIO_DEVICES = mutableListOf<Map<String,String>>()
private val MIXER_INFOS = mutableMapOf<String,Mixer.Info>()
private val NOTE_NAMES = "A,A#,B,C,C#,D,D#,E,F,F#,G,G#".split(",")
private val NOTE_BASE = 0.031359713524659
private val LOG_2 = ln(2.0)
private const val sampleRate = 44100f
private const val bufferSize = 1024
private const val numberOfSamples = 1024
private const val sampleSizeInBits = 16
private const val channels = 1
private const val signed = true
private const val bigEndian = true
private const val overlap = 0

private fun initDevices() {
    if (AUDIO_DEVICES.isNotEmpty())
        return
    for (info in AudioSystem.getMixerInfo()) {
        val type = getAudioDeviceType(AudioSystem.getMixer(info))
        if (type != null) {
            AUDIO_DEVICES.add(mapOf(
                    "name" to info.name,
                    "description" to info.description,
                    "type" to type.toString()
            ))
            MIXER_INFOS[info.name] = info
        }
    }
}

private fun getAudioDeviceType(mixer: Mixer): AudioDeviceType? {
    val inputs = mixer.targetLineInfo.size
    val outputs = mixer.sourceLineInfo.size
    if (inputs > 0 && outputs > 0)
        return AudioDeviceType.ANY
    if (inputs > 0)
        return AudioDeviceType.INPUT
    if (outputs > 0)
        return AudioDeviceType.OUTPUT
    return null
}

private fun detectPitch(mixer: Mixer, algoName: String, target: Any?) {
    val format = AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian)
    val dataLineInfo = DataLine.Info(TargetDataLine::class.java, format)
    val line = mixer.getLine(dataLineInfo) as TargetDataLine
    line.open(format, numberOfSamples)
    line.start()

    val stream = AudioInputStream(line)
    val audioStream = JVMAudioInputStream(stream)
    val dispatcher = AudioDispatcher(audioStream, bufferSize, overlap)
    val algo = PitchEstimationAlgorithm.valueOf(algoName.toUpperCase())
    val handler = MyHandler(target)
    dispatcher.addAudioProcessor(PitchProcessor(algo, sampleRate, bufferSize, handler))
    Thread(dispatcher, "Audio dispatching").start()
}

private class MyHandler(private val target: Any?) : PitchDetectionHandler {

    private var lastNote: Note? = null

    override fun handlePitch(pitchDetectionResult: PitchDetectionResult, audioEvent: AudioEvent) {
        if (pitchDetectionResult.pitch != -1f) {
            val timeStamp = audioEvent.timeStamp
            val pitch = pitchDetectionResult.pitch
            val note = hertzToNote(pitch)
            if (target is MidiChannel)
                playNote(target, note)
            else if (target is Synthesizer)
                playNote(target.channels[0], note)
            else if (target == null)
                print(String.format("   %.2fHz = %s at %.2fs         \r", pitch, note, timeStamp))
            // TODO: what to do with other types of targets ?
        }
    }

    private fun playNote(channel: MidiChannel, note: Note) {
        if (lastNote != null && lastNote != note)
            channel.noteOff(lastNote!!.midi)
        channel.noteOn(note.midi, 127)
        lastNote = note
    }
}
